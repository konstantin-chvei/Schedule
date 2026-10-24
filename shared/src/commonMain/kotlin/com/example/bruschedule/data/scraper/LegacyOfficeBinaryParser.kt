package com.example.bruschedule.data.scraper

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import no.synth.kmpzip.io.ByteArrayOutputStream

internal class LegacyXlsHtmlParser {
    fun toHtml(bytes: ByteArray): String {
        val compound = CompoundBinaryFileReader(bytes)
        val workbook = compound.readStream("Workbook") ?: compound.readStream("Book")
            ?: error("В XLS не найден лист Workbook")
        val records = readBiffRecords(workbook, 0)
        require(records.none { it.id == BIFF_FILE_PASS }) { "Зашифрованные XLS не поддерживаются" }
        val codePage = records.firstOrNull { it.id == BIFF_CODE_PAGE }
            ?.data?.takeIf { it.size >= 2 }?.u16(0) ?: DEFAULT_CODE_PAGE
        val sharedStrings = readSharedStrings(records, codePage)
        val customFormats = readCustomFormats(records, codePage)
        val xfFormats = readXfFormats(records)
        val date1904 = records.firstOrNull { it.id == BIFF_DATE_MODE }
            ?.data?.takeIf { it.size >= 2 }?.u16(0) == 1
        val sheets = records.filter { it.id == BIFF_BOUNDSHEET }
            .mapNotNull { readBoundSheet(it.data, codePage) }
            .take(MAX_SHEETS)
        require(sheets.isNotEmpty()) { "В XLS не найдены листы расписания" }

        val htmlTables = sheets.mapNotNull { sheet ->
            require(sheet.offset in 0 until workbook.size) { "Некорректное смещение листа XLS" }
            val sheetRecords = readBiffRecords(workbook, sheet.offset)
            val sheetData = parseSheet(
                records = sheetRecords,
                sharedStrings = sharedStrings,
                codePage = codePage,
                xfFormats = xfFormats,
                customFormats = customFormats,
                date1904 = date1904,
            )
            if (sheetData.cells.isEmpty()) return@mapNotNull null
            renderSheet(sheet.name, sheetData)
        }
        require(htmlTables.isNotEmpty()) { "В XLS не найдены заполненные ячейки" }
        return "<html><body>${htmlTables.joinToString("\n")}</body></html>"
    }

    private fun readSharedStrings(records: List<BiffRecord>, codePage: Int): List<String> {
        val sstIndex = records.indexOfFirst { it.id == BIFF_SST }
        if (sstIndex < 0) return emptyList()
        val sstRecords = buildList {
            add(records[sstIndex].data)
            var next = sstIndex + 1
            while (next < records.size && records[next].id == BIFF_CONTINUE) {
                add(records[next].data)
                next++
            }
        }
        val reader = SstReader(sstRecords)
        if (reader.remainingBytes < 8) return emptyList()
        reader.readU32()
        val stringCount = reader.readU32().coerceAtMost(MAX_SHARED_STRINGS.toLong()).toInt()
        return buildList {
            repeat(stringCount) {
                if (reader.remainingBytes < 3) return@buildList
                val characterCount = reader.readU16()
                val flags = reader.readU8()
                val richTextRuns = if (flags and 0x08 != 0) reader.readU16() else 0
                val extendedBytes = if (flags and 0x04 != 0) reader.readU32().toInt() else 0
                val value = reader.readCharacters(characterCount, flags and 0x01 != 0, codePage)
                repeat((richTextRuns * 4 + extendedBytes).coerceAtMost(MAX_SST_METADATA_BYTES)) {
                    if (reader.remainingBytes > 0) reader.readU8()
                }
                add(value)
            }
        }
    }

    private fun readCustomFormats(records: List<BiffRecord>, codePage: Int): Map<Int, String> =
        records.filter { it.id == BIFF_FORMAT }.mapNotNull { record ->
            val data = record.data
            if (data.size < 5) return@mapNotNull null
            val formatIndex = data.u16(0)
            val characterCount = data.u16(2)
            val flags = data.u8(4)
            formatIndex to decodeBiffCharacters(data, 5, characterCount, flags and 0x01 != 0, codePage)
        }.toMap()

    private fun readXfFormats(records: List<BiffRecord>): List<Int> =
        records.filter { it.id == BIFF_XF && it.data.size >= 4 }.map { it.data.u16(2) }

    private fun readBoundSheet(data: ByteArray, codePage: Int): BiffSheet? {
        if (data.size < 8) return null
        val offset = data.u32(0).toInt()
        val characterCount = data.u8(6)
        val flags = data.u8(7)
        val name = decodeBiffCharacters(data, 8, characterCount, flags and 0x01 != 0, codePage)
        return BiffSheet(offset, name.ifBlank { "Лист" })
    }

    private fun parseSheet(
        records: List<BiffRecord>,
        sharedStrings: List<String>,
        codePage: Int,
        xfFormats: List<Int>,
        customFormats: Map<Int, String>,
        date1904: Boolean,
    ): BiffSheetData {
        val cells = mutableMapOf<Int, MutableMap<Int, String>>()
        val mergedRanges = mutableListOf<BiffMergedRange>()
        records.forEach { record ->
            val data = record.data
            when (record.id) {
                BIFF_LABEL_SST -> if (data.size >= 10) {
                    val row = data.u16(0)
                    val column = data.u16(2)
                    val stringIndex = data.u32(6).toInt()
                    sharedStrings.getOrNull(stringIndex)?.let { putCell(cells, row, column, it) }
                }
                BIFF_LABEL -> if (data.size >= 9) {
                    val row = data.u16(0)
                    val column = data.u16(2)
                    val characterCount = data.u16(6)
                    val flags = data.u8(8)
                    putCell(
                        cells,
                        row,
                        column,
                        decodeBiffCharacters(data, 9, characterCount, flags and 0x01 != 0, codePage),
                    )
                }
                BIFF_NUMBER -> if (data.size >= 14) {
                    val numberFormat = resolveNumberFormat(data.u16(4), xfFormats)
                    val number = data.f64(6)
                    putCell(
                        cells,
                        data.u16(0),
                        data.u16(2),
                        formatNumber(number, numberFormat, customFormats, date1904),
                    )
                }
                BIFF_RK -> if (data.size >= 10) {
                    val numberFormat = resolveNumberFormat(data.u16(4), xfFormats)
                    val number = decodeRk(data.u32(6))
                    putCell(
                        cells,
                        data.u16(0),
                        data.u16(2),
                        formatNumber(number, numberFormat, customFormats, date1904),
                    )
                }
                BIFF_MUL_RK -> readMultipleRk(data, cells, xfFormats, customFormats, date1904)
                BIFF_BOOL_ERR -> if (data.size >= 8) {
                    val value = if (data.u8(7) == 0) data.u8(6).toString() else ""
                    putCell(cells, data.u16(0), data.u16(2), value)
                }
                BIFF_FORMULA -> if (data.size >= 14) {
                    val numberFormat = resolveNumberFormat(data.u16(4), xfFormats)
                    val result = readFormulaResult(data, numberFormat, codePage, customFormats, date1904)
                    if (result.isNotBlank()) putCell(cells, data.u16(0), data.u16(2), result)
                }
                BIFF_MERGED_CELLS -> readMergedRanges(data, mergedRanges)
            }
        }
        val maxRow = cells.keys.maxOrNull() ?: -1
        val maxColumn = cells.values.flatMap { it.keys }.maxOrNull() ?: -1
        require(maxRow < MAX_ROWS && maxColumn < MAX_COLUMNS) { "Таблица XLS превышает допустимый размер" }
        return BiffSheetData(cells, mergedRanges, maxRow, maxColumn)
    }

    private fun readMultipleRk(
        data: ByteArray,
        cells: MutableMap<Int, MutableMap<Int, String>>,
        xfFormats: List<Int>,
        customFormats: Map<Int, String>,
        date1904: Boolean,
    ) {
        if (data.size < 12) return
        val row = data.u16(0)
        val firstColumn = data.u16(2)
        val lastColumn = data.u16(data.size - 2)
        var offset = 4
        var column = firstColumn
        while (offset + 6 <= data.size - 2 && column <= lastColumn) {
            val rawRk = data.u32(offset + 2)
            val numberFormat = resolveNumberFormat(data.u16(offset), xfFormats)
            putCell(
                cells,
                row,
                column,
                formatNumber(decodeRk(rawRk), numberFormat, customFormats, date1904),
            )
            offset += 6
            column++
        }
    }

    private fun readMergedRanges(data: ByteArray, target: MutableList<BiffMergedRange>) {
        if (data.size < 2) return
        val count = minOf(data.u16(0), (data.size - 2) / 8)
        repeat(count) { index ->
            val offset = 2 + index * 8
            target += BiffMergedRange(
                firstRow = data.u16(offset),
                lastRow = data.u16(offset + 2),
                firstColumn = data.u16(offset + 4),
                lastColumn = data.u16(offset + 6),
            )
        }
    }

    private fun readFormulaResult(
        data: ByteArray,
        numberFormat: Int,
        codePage: Int,
        customFormats: Map<Int, String>,
        date1904: Boolean,
    ): String {
        if (data.size < 14) return ""
        if (data.u8(12) == 0xff && data.u8(13) == 0xff) {
            return when (data.u8(6)) {
                0 -> if (data.u8(8) == 0) "0" else "1"
                1, 2, 3 -> ""
                else -> ""
            }
        }
        val number = data.f64(6)
        return formatNumber(number, numberFormat, customFormats, date1904)
    }

    private fun renderSheet(name: String, data: BiffSheetData): String = buildString {
        append("<h2>").append(escapeHtml(name)).append("</h2><table><tbody>")
        for (row in 0..data.maxRow) {
            append("<tr>")
            var column = 0
            while (column <= data.maxColumn) {
                val merge = data.mergedRanges.firstOrNull { range ->
                    range.firstRow == row && range.firstColumn == column
                }
                if (merge != null) {
                    val value = data.cells[row]?.get(column).orEmpty()
                    append("<td")
                    if (merge.lastColumn > merge.firstColumn) {
                        append(" colspan=\"").append(merge.lastColumn - merge.firstColumn + 1).append('"')
                    }
                    if (merge.lastRow > merge.firstRow) {
                        append(" rowspan=\"").append(merge.lastRow - merge.firstRow + 1).append('"')
                    }
                    append('>').append(escapeHtml(value)).append("</td>")
                    column = merge.lastColumn + 1
                } else if (data.mergedRanges.any { range ->
                        row in range.firstRow..range.lastRow && column in range.firstColumn..range.lastColumn
                    }
                ) {
                    column++
                } else {
                    append("<td>").append(escapeHtml(data.cells[row]?.get(column).orEmpty())).append("</td>")
                    column++
                }
            }
            append("</tr>")
        }
        append("</tbody></table>")
    }

    private fun putCell(cells: MutableMap<Int, MutableMap<Int, String>>, row: Int, column: Int, value: String) {
        if (row !in 0 until MAX_ROWS || column !in 0 until MAX_COLUMNS || value.isBlank()) return
        cells.getOrPut(row) { mutableMapOf() }[column] = value
    }

    private fun resolveNumberFormat(xfIndex: Int, xfFormats: List<Int>): Int =
        xfFormats.getOrNull(xfIndex) ?: xfIndex

    private fun formatNumber(
        value: Double,
        numberFormat: Int,
        customFormats: Map<Int, String>,
        date1904: Boolean,
    ): String {
        if (!value.isFinite()) return ""
        val customFormat = customFormats[numberFormat].orEmpty().lowercase()
        val isTime = numberFormat in TIME_FORMATS || customFormat.contains('h') ||
            customFormat.contains(":") && customFormat.contains('m')
        val isDate = numberFormat in DATE_FORMATS || customFormat.contains('y') || customFormat.contains('d')
        if (isTime && value >= 0.0 && value < 1.0) return formatExcelTime(value)
        if (isDate && value >= 0.0 && value <= MAX_EXCEL_SERIAL) {
            val days = value.toInt()
            val baseDate = if (date1904) LocalDate(1904, 1, 1) else LocalDate(1899, 12, 30)
            val date = runCatching { baseDate.plus(DatePeriod(days = days)) }.getOrNull()
                ?: return value.toString()
            val dateText = date.toString()
            return if (isTime && value % 1.0 > 0.0) "$dateText ${formatExcelTime(value % 1.0)}" else dateText
        }
        val rounded = value.toLong()
        return if (value == rounded.toDouble()) rounded.toString() else value.toString()
    }

    private fun formatExcelTime(fraction: Double): String {
        val minutes = (fraction * MINUTES_PER_DAY + 0.5).toInt().coerceIn(0, MINUTES_PER_DAY - 1)
        return "${(minutes / 60).toString().padStart(2, '0')}:${(minutes % 60).toString().padStart(2, '0')}"
    }

    private fun decodeRk(raw: Long): Double {
        val isInteger = raw and 0x02L != 0L
        val dividedBy100 = raw and 0x01L != 0L
        val value = if (isInteger) {
            (raw shr 2).toInt().toDouble()
        } else {
            Double.fromBits((raw and 0xfffffffCL) shl 32)
        }
        return if (dividedBy100) value / 100.0 else value
    }

    private fun readBiffRecords(bytes: ByteArray, startOffset: Int): List<BiffRecord> {
        val records = mutableListOf<BiffRecord>()
        var offset = startOffset
        var count = 0
        while (offset + 4 <= bytes.size && count++ < MAX_BIFF_RECORDS) {
            val id = bytes.u16(offset)
            val length = bytes.u16(offset + 2)
            if (id == 0 && length == 0) break
            val dataStart = offset + 4
            val dataEnd = dataStart + length
            require(dataEnd <= bytes.size) { "Повреждённая запись в XLS" }
            records += BiffRecord(id, bytes.copyOfRange(dataStart, dataEnd), offset)
            offset = dataEnd
            if (id == BIFF_EOF) break
        }
        return records
    }

    private fun decodeBiffCharacters(
        data: ByteArray,
        offset: Int,
        count: Int,
        unicode: Boolean,
        codePage: Int,
    ): String {
        val byteCount = count * if (unicode) 2 else 1
        if (offset < 0 || byteCount < 0 || offset + byteCount > data.size) return ""
        return if (unicode) decodeUtf16Le(data, offset, count) else decodeCodePage(data, offset, count, codePage)
    }

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("\r\n", "\n")
        .replace("\r", "\n")
        .replace("\n", "<br>")

    private data class BiffSheet(val offset: Int, val name: String)
    private data class BiffRecord(val id: Int, val data: ByteArray, val offset: Int)
    private data class BiffMergedRange(
        val firstRow: Int,
        val lastRow: Int,
        val firstColumn: Int,
        val lastColumn: Int,
    )
    private data class BiffSheetData(
        val cells: Map<Int, Map<Int, String>>,
        val mergedRanges: List<BiffMergedRange>,
        val maxRow: Int,
        val maxColumn: Int,
    )

    private companion object {
        const val BIFF_EOF = 0x000a
        const val BIFF_FILE_PASS = 0x002f
        const val BIFF_CODE_PAGE = 0x0042
        const val BIFF_BOUNDSHEET = 0x0085
        const val BIFF_SST = 0x00fc
        const val BIFF_CONTINUE = 0x003c
        const val BIFF_LABEL_SST = 0x00fd
        const val BIFF_NUMBER = 0x0203
        const val BIFF_LABEL = 0x0204
        const val BIFF_BOOL_ERR = 0x0205
        const val BIFF_FORMULA = 0x0006
        const val BIFF_RK = 0x027e
        const val BIFF_MUL_RK = 0x00bd
        const val BIFF_MERGED_CELLS = 0x00e5
        const val BIFF_FORMAT = 0x041e
        const val BIFF_XF = 0x00e0
        const val BIFF_DATE_MODE = 0x0022
        const val DEFAULT_CODE_PAGE = 1251
        const val MAX_BIFF_RECORDS = 200_000
        const val MAX_SHARED_STRINGS = 100_000
        const val MAX_SST_METADATA_BYTES = 1_000_000
        const val MAX_SHEETS = 20
        const val MAX_ROWS = 10_000
        const val MAX_COLUMNS = 256
        const val MINUTES_PER_DAY = 24 * 60
        const val MAX_EXCEL_SERIAL = 2_958_465.0
        val TIME_FORMATS = setOf(18, 19, 20, 21, 22, 45, 46, 47)
        val DATE_FORMATS = setOf(14, 15, 16, 17, 22, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 50, 51, 52, 53, 54, 55, 56, 57, 58)
    }
}

private class CompoundBinaryFileReader(private val bytes: ByteArray) {
    private val sectorSize: Int
    private val miniSectorSize: Int
    private val miniStreamCutoff: Int
    private val fat: List<Long>
    private val directory: List<CompoundDirectoryEntry>
    private val miniFat: List<Long>
    private val rootMiniStream: ByteArray

    init {
        require(bytes.size >= HEADER_BYTES) { "Файл Office слишком короткий" }
        require(bytes.copyOfRange(0, SIGNATURE.size).contentEquals(SIGNATURE)) {
            "Ожидался бинарный контейнер OLE Office"
        }
        require(bytes.u16(28) == BYTE_ORDER_LITTLE_ENDIAN) { "Неизвестный порядок байтов OLE" }
        sectorSize = 1 shl bytes.u16(30)
        miniSectorSize = 1 shl bytes.u16(32)
        require(sectorSize in setOf(512, 4096) && miniSectorSize == 64) {
            "Неподдерживаемый размер сектора OLE"
        }
        miniStreamCutoff = bytes.u32(56).toInt().coerceAtLeast(miniSectorSize)
        fat = readFat()
        val directoryBytes = readRegularChain(bytes.u32(48), expectedSize = null)
        directory = readDirectoryEntries(directoryBytes)
        miniFat = readMiniFat()
        val root = directory.firstOrNull { it.type == DIRECTORY_ROOT }
        rootMiniStream = if (root == null || root.size == 0L) {
            byteArrayOf()
        } else {
            readRegularChain(root.startSector, root.size.toInt())
        }
    }

    fun readStream(name: String): ByteArray? {
        val entry = directory.firstOrNull { it.type == DIRECTORY_STREAM && it.name.equals(name, ignoreCase = true) }
            ?: return null
        require(entry.size <= MAX_STREAM_BYTES) { "Поток Office превышает допустимый размер" }
        if (entry.size == 0L) return byteArrayOf()
        val size = entry.size.toInt()
        return if (entry.size < miniStreamCutoff) {
            readMiniChain(entry.startSector, size)
        } else {
            readRegularChain(entry.startSector, size)
        }
    }

    private fun readFat(): List<Long> {
        val requestedFatSectors = bytes.u32(44).toInt()
        require(requestedFatSectors in 1..MAX_FAT_SECTORS) { "Повреждённая таблица FAT OLE" }
        val fatSectorIds = mutableListOf<Long>()
        repeat(HEADER_DIFAT_ENTRIES) { index ->
            val id = bytes.u32(76 + index * 4)
            if (id != FREE_SECTOR && id != END_OF_CHAIN) fatSectorIds += id
        }
        var difatSector = bytes.u32(68)
        val difatSectorCount = minOf(bytes.u32(72).toInt(), MAX_FAT_SECTORS)
        var difatIndex = 0
        while (difatIndex < difatSectorCount && difatSector != FREE_SECTOR && difatSector != END_OF_CHAIN) {
            val sector = readSector(difatSector)
            val entriesPerSector = sectorSize / 4
            repeat(entriesPerSector - 1) { index ->
                val id = sector.u32(index * 4)
                if (id != FREE_SECTOR && id != END_OF_CHAIN) fatSectorIds += id
            }
            difatSector = sector.u32((entriesPerSector - 1) * 4)
            difatIndex++
        }
        val selectedFatSectors = fatSectorIds.take(requestedFatSectors)
        require(selectedFatSectors.size == requestedFatSectors) { "В OLE не хватает секторов FAT" }
        return buildList {
            selectedFatSectors.forEach { sectorId ->
                val sector = readSector(sectorId)
                for (offset in 0 until sectorSize step 4) add(sector.u32(offset))
            }
        }
    }

    private fun readMiniFat(): List<Long> {
        val sectorCount = bytes.u32(64).toInt()
        if (sectorCount == 0) return emptyList()
        require(sectorCount in 1..MAX_FAT_SECTORS) { "Повреждённая MiniFAT OLE" }
        val miniFatBytes = readRegularChain(bytes.u32(60), sectorCount * sectorSize)
        return buildList {
            for (offset in 0 until miniFatBytes.size step 4) add(miniFatBytes.u32(offset))
        }
    }

    private fun readDirectoryEntries(directoryBytes: ByteArray): List<CompoundDirectoryEntry> = buildList {
        for (offset in 0 until directoryBytes.size step DIRECTORY_ENTRY_BYTES) {
            if (offset + DIRECTORY_ENTRY_BYTES > directoryBytes.size) break
            val type = directoryBytes.u8(offset + 66)
            if (type == DIRECTORY_EMPTY) continue
            val nameLength = directoryBytes.u16(offset + 64)
            val nameCharacterCount = ((nameLength - 2).coerceAtLeast(0) / 2).coerceAtMost(31)
            val name = decodeUtf16Le(directoryBytes, offset, nameCharacterCount)
            val size = directoryBytes.u64(offset + 120)
            require(size <= MAX_STREAM_BYTES) { "Поток Office превышает допустимый размер" }
            add(
                CompoundDirectoryEntry(
                    name = name,
                    type = type,
                    startSector = directoryBytes.u32(offset + 116),
                    size = size,
                ),
            )
        }
    }

    private fun readRegularChain(startSector: Long, expectedSize: Int?): ByteArray {
        if (expectedSize == 0 || startSector == END_OF_CHAIN || startSector == FREE_SECTOR) {
            require(expectedSize == null || expectedSize == 0) { "Оборванная цепочка секторов OLE" }
            return byteArrayOf()
        }
        val output = ByteArrayOutputStream()
        val visited = mutableSetOf<Long>()
        var current = startSector
        while (current != END_OF_CHAIN && current != FREE_SECTOR) {
            require(current < fat.size && visited.add(current)) { "Повреждённая цепочка секторов OLE" }
            require(visited.size <= MAX_CHAIN_SECTORS) { "Слишком длинная цепочка секторов OLE" }
            val sector = readSector(current)
            val remaining = expectedSize?.minus(output.size()) ?: MAX_STREAM_BYTES - output.size()
            if (remaining <= 0) break
            val count = minOf(sector.size, remaining)
            output.write(sector, 0, count)
            current = fat[current.toInt()]
        }
        require(expectedSize == null || output.size() == expectedSize) { "Оборванный поток OLE" }
        return output.toByteArray()
    }

    private fun readMiniChain(startMiniSector: Long, expectedSize: Int): ByteArray {
        require(miniFat.isNotEmpty() && rootMiniStream.isNotEmpty()) { "В OLE отсутствует MiniFAT" }
        val output = ByteArrayOutputStream()
        val visited = mutableSetOf<Long>()
        var current = startMiniSector
        while (current != END_OF_CHAIN && current != FREE_SECTOR) {
            require(current < miniFat.size && visited.add(current)) { "Повреждённая MiniFAT OLE" }
            require(visited.size <= MAX_CHAIN_SECTORS) { "Слишком длинная MiniFAT OLE" }
            val start = current.toInt() * miniSectorSize
            require(start >= 0 && start + miniSectorSize <= rootMiniStream.size) { "Сектор MiniFAT вне контейнера" }
            val count = minOf(miniSectorSize, expectedSize - output.size())
            if (count <= 0) break
            output.write(rootMiniStream, start, count)
            current = miniFat[current.toInt()]
        }
        require(output.size() == expectedSize) { "Оборванный поток MiniFAT OLE" }
        return output.toByteArray()
    }

    private fun readSector(sectorId: Long): ByteArray {
        require(sectorId >= 0 && sectorId <= Int.MAX_VALUE) { "Некорректный номер сектора OLE" }
        val startLong = (sectorId + 1) * sectorSize
        require(startLong >= 0 && startLong + sectorSize <= bytes.size) { "Сектор OLE вне контейнера" }
        val start = startLong.toInt()
        return bytes.copyOfRange(start, start + sectorSize)
    }

    private data class CompoundDirectoryEntry(
        val name: String,
        val type: Int,
        val startSector: Long,
        val size: Long,
    )

    private companion object {
        const val HEADER_BYTES = 512
        const val DIRECTORY_ENTRY_BYTES = 128
        const val DIRECTORY_EMPTY = 0
        const val DIRECTORY_STREAM = 2
        const val DIRECTORY_ROOT = 5
        const val BYTE_ORDER_LITTLE_ENDIAN = 0xfffe
        const val MAX_STREAM_BYTES = 30 * 1024 * 1024
        const val MAX_CHAIN_SECTORS = 100_000
        const val MAX_FAT_SECTORS = 20_000
        const val HEADER_DIFAT_ENTRIES = 109
        const val FREE_SECTOR = 0xffffffffL
        const val END_OF_CHAIN = 0xfffffffeL
        val SIGNATURE = byteArrayOf(0xd0.toByte(), 0xcf.toByte(), 0x11, 0xe0.toByte(), 0xa1.toByte(), 0xb1.toByte(), 0x1a, 0xe1.toByte())
    }
}

internal class LegacyDocHtmlParser {
    fun toHtml(bytes: ByteArray): String {
        val compound = CompoundBinaryFileReader(bytes)
        val wordDocument = compound.readStream("WordDocument")
            ?: error("В DOC не найден поток WordDocument")
        require(wordDocument.size >= FIB_CLX_OFFSET + 8) { "Повреждённый файл DOC" }
        require(wordDocument.u16(0) == WORD_FIB_IDENTIFIER) { "Неверный заголовок DOC" }
        val flags = wordDocument.u16(FIB_FLAGS_OFFSET)
        require(flags and FIB_ENCRYPTED_MASK == 0) { "Зашифрованные DOC не поддерживаются" }
        val tableStreamName = if (flags and FIB_WHICH_TABLE_MASK != 0) "1Table" else "0Table"
        val tableStream = compound.readStream(tableStreamName)
            ?: error("В DOC не найден поток $tableStreamName")
        val codePage = when (wordDocument.u16(FIB_LANGUAGE_OFFSET)) {
            0x0419, 0x0423, 0x0402 -> 1251
            else -> 1252
        }
        val mainTextCharacterCount = wordDocument.u32(FIB_CCP_TEXT_OFFSET).toInt()
        require(mainTextCharacterCount in 1..MAX_WORD_CHARACTERS) { "В DOC не найден текст расписания" }
        val clxOffset = wordDocument.u32(FIB_CLX_OFFSET).toInt()
        val clxLength = wordDocument.u32(FIB_CLX_LENGTH_OFFSET).toInt()
        require(
            clxOffset >= 0 && clxLength > 0 && clxOffset <= tableStream.size &&
                clxLength <= tableStream.size - clxOffset,
        ) {
            "Повреждена таблица текста DOC"
        }
        val text = extractWordText(
            wordDocument = wordDocument,
            tableStream = tableStream,
            clxOffset = clxOffset,
            clxLength = clxLength,
            characterCount = mainTextCharacterCount,
            codePage = codePage,
        )
        return convertWordTextToHtml(text)
    }

    private fun extractWordText(
        wordDocument: ByteArray,
        tableStream: ByteArray,
        clxOffset: Int,
        clxLength: Int,
        characterCount: Int,
        codePage: Int,
    ): WordTextData {
        var cursor = clxOffset
        val clxEnd = clxOffset + clxLength
        var pieceTableOffset = -1
        while (cursor < clxEnd) {
            when (tableStream.u8(cursor)) {
                CLX_PRC_MARKER -> {
                    require(cursor + 3 <= clxEnd) { "Повреждена запись форматирования DOC" }
                    val propertyLength = tableStream.u16(cursor + 1)
                    cursor += 3 + propertyLength
                    require(cursor <= clxEnd) { "Повреждена запись форматирования DOC" }
                }
                CLX_PIECE_TABLE_MARKER -> {
                    pieceTableOffset = cursor + 1
                    break
                }
                else -> error("Неизвестная структура текста DOC")
            }
        }
        require(pieceTableOffset >= 0) { "В DOC не найдена таблица фрагментов текста" }
        val pieceTableLength = clxEnd - pieceTableOffset
        require(pieceTableLength >= 16 && (pieceTableLength - 4) % 12 == 0) {
            "Повреждена таблица фрагментов текста DOC"
        }
        val pieceCount = (pieceTableLength - 4) / 12
        require(pieceCount in 1..MAX_WORD_PIECES) { "Слишком много фрагментов текста DOC" }
        val descriptorsOffset = pieceTableOffset + (pieceCount + 1) * 4
        val paragraphProperties = WordPapxRowTerminatorLookup(wordDocument, tableStream)
        val extracted = StringBuilder()
        val rowTerminatorOffsets = mutableSetOf<Int>()
        for (pieceIndex in 0 until pieceCount) {
            if (extracted.length >= characterCount) break
            val cpStart = tableStream.u32(pieceTableOffset + pieceIndex * 4)
            val cpEnd = tableStream.u32(pieceTableOffset + (pieceIndex + 1) * 4)
            require(cpStart <= Int.MAX_VALUE && cpEnd <= Int.MAX_VALUE && cpEnd >= cpStart) {
                "Некорректный диапазон символов DOC"
            }
            val pieceCharacterCount = minOf(cpEnd.toInt(), characterCount) - cpStart.toInt()
            if (pieceCharacterCount <= 0) continue
            val descriptorOffset = descriptorsOffset + pieceIndex * PCD_BYTES
            val compressedAddress = tableStream.u32(descriptorOffset + 2)
            val compressed = compressedAddress and FC_COMPRESSED_MASK != 0L
            val storedOffset = compressedAddress and FC_OFFSET_MASK
            val physicalOffset = if (compressed) storedOffset / 2 else storedOffset
            val bytesPerCharacter = if (compressed) 1 else 2
            val byteCount = pieceCharacterCount * bytesPerCharacter
            require(physicalOffset >= 0 && physicalOffset + byteCount <= wordDocument.size) {
                "Фрагмент текста DOC выходит за границы потока"
            }
            val physicalByteOffset = physicalOffset.toInt()
            repeat(pieceCharacterCount) { characterOffset ->
                val sourceOffset = physicalByteOffset + characterOffset * bytesPerCharacter
                val character = if (compressed) {
                    decodeCodePage(wordDocument, sourceOffset, 1, codePage).firstOrNull() ?: '\u0000'
                } else {
                    wordDocument.u16(sourceOffset).toChar()
                }
                if (character == WORD_CELL_MARK && paragraphProperties.isRowTerminator(sourceOffset)) {
                    rowTerminatorOffsets += extracted.length
                }
                extracted.append(character)
            }
        }
        val text = extracted.toString().take(characterCount)
        return WordTextData(text, rowTerminatorOffsets.filter { it < text.length }.toSet())
    }

    private fun convertWordTextToHtml(text: WordTextData): String {
        val cells = mutableListOf<String?>()
        val cell = StringBuilder()
        text.text.forEachIndexed { index, character ->
            when {
                character == WORD_CELL_MARK -> {
                    cells += cleanWordCell(cell.toString())
                    cell.clear()
                    if (index in text.rowTerminatorOffsets) cells += null
                }
                character == '\r' || character == '\n' || character == '\u000c' -> {
                    if (cell.isNotEmpty() && cell.last() != '\n') cell.append('\n')
                }
                character == '\t' -> cell.append(' ')
                character.code >= 0x20 || character == '\u00a0' -> cell.append(character)
            }
        }
        if (cell.isNotEmpty()) cells += cleanWordCell(cell.toString())
        val firstTimeIndex = cells.indexOfFirst { it != null && parseWordTime(it) != null }
        require(firstTimeIndex > 0) { "В DOC не найдены строки времени расписания" }
        val headerCells = cells.take(firstTimeIndex).filterNotNull()
        val groups = headerCells.flatMap(::groupHeadersIn)
            .distinctBy(::normalizeWordToken)
            .take(MAX_WORD_GROUPS)
        require(groups.isNotEmpty()) { "В DOC не найдены заголовки учебных групп" }

        var currentDay = ""
        var currentDate = ""
        headerCells.forEach { header ->
            if (isWordDay(header)) currentDay = header
            wordDate(header)?.let { currentDate = it }
        }
        val rows = mutableListOf<WordScheduleRow>()
        var pending: WordScheduleRow? = null
        fun flush() {
            val row = pending ?: return
            val values = row.groupValues.take(groups.size).toMutableList()
            while (values.size < groups.size) values += ""
            rows += row.copy(groupValues = values)
            pending = null
        }
        cells.drop(firstTimeIndex).forEach { cellValue ->
            if (cellValue == null) {
                flush()
                return@forEach
            }
            val value = cellValue
            val time = parseWordTime(value)
            if (time != null) {
                flush()
                pending = WordScheduleRow(currentDay, currentDate, time, emptyList())
                return@forEach
            }
            if (isWordDay(value)) {
                flush()
                currentDay = value
                return@forEach
            }
            val date = wordDate(value)
            if (date != null && value.trim() == date) {
                flush()
                currentDate = date
                return@forEach
            }
            if (pending != null && value.isNotBlank() && groupHeadersIn(value).isEmpty()) {
                pending = pending!!.copy(groupValues = pending!!.groupValues + value)
            }
        }
        flush()
        require(rows.isNotEmpty()) { "В DOC не найдены пары расписания" }

        return buildString {
            append("<html><body><table><thead><tr>")
            append("<th>День недели</th><th>Дата</th><th>Время</th>")
            groups.forEach { group -> append("<th>").append(escapeHtml(group)).append("</th>") }
            append("</tr></thead><tbody>")
            rows.forEach { row ->
                append("<tr><td>").append(escapeHtml(row.day))
                    .append("</td><td>").append(escapeHtml(row.date))
                    .append("</td><td>").append(escapeHtml(row.time)).append("</td>")
                row.groupValues.forEach { value -> append("<td>").append(escapeHtml(value)).append("</td>") }
                append("</tr>")
            }
            append("</tbody></table></body></html>")
        }
    }

    private fun groupHeadersIn(value: String): List<String> = GROUP_HEADER.findAll(value).mapNotNull { match ->
        val code = match.groupValues.getOrNull(1)?.replace(WHITESPACE, "")?.replace('–', '-')
            ?.replace('—', '-')?.trim().orEmpty()
        code.takeIf(String::isNotBlank)?.let { "Группа $it" }
    }.toList()

    private fun isWordDay(value: String): Boolean {
        val normalized = value.lowercase().replace('ё', 'е').trim()
        return WORD_DAYS.any { day -> normalized == day || normalized.startsWith(day) && normalized.length < 40 }
    }

    private fun wordDate(value: String): String? {
        val iso = ISO_WORD_DATE.find(value)?.value
        if (iso != null) return iso
        val dmy = DMY_WORD_DATE.find(value)?.value ?: return null
        return dmy
    }

    private fun parseWordTime(value: String): String? {
        val text = value.trim().replace(WHITESPACE, " ")
            .replace(Regex("^\\d{1,2}\\s+(?=\\d)"), "")
        val match = WORD_TIME.matchEntire(text) ?: return null
        val startHour = match.groupValues[1].toIntOrNull() ?: return null
        val startMinute = match.groupValues[2].toIntOrNull() ?: return null
        val endHour = match.groupValues[3].toIntOrNull() ?: return null
        val endMinute = match.groupValues[4].toIntOrNull() ?: return null
        if (startHour !in 6..23 || endHour !in 6..24 || startMinute !in 0..59 || endMinute !in 0..59) return null
        return "${startHour.toString().padStart(2, '0')}:${startMinute.toString().padStart(2, '0')}-" +
            "${endHour.toString().padStart(2, '0')}:${endMinute.toString().padStart(2, '0')}"
    }

    private fun cleanWordCell(value: String): String = value.split('\n')
        .map { it.replace(WHITESPACE, " ").trim() }
        .filter(String::isNotBlank)
        .joinToString("\n")

    private fun normalizeWordToken(value: String): String = value.lowercase().filter(Char::isLetterOrDigit)

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("\r\n", "\n")
        .replace("\r", "\n")
        .replace("\n", "<br>")

    private data class WordTextData(val text: String, val rowTerminatorOffsets: Set<Int>)

    private data class WordScheduleRow(
        val day: String,
        val date: String,
        val time: String,
        val groupValues: List<String>,
    )

    private companion object {
        const val WORD_FIB_IDENTIFIER = 0xa5ec
        const val FIB_LANGUAGE_OFFSET = 6
        const val FIB_FLAGS_OFFSET = 10
        const val FIB_CCP_TEXT_OFFSET = 0x4c
        const val FIB_CLX_OFFSET = 0x1a2
        const val FIB_CLX_LENGTH_OFFSET = 0x1a6
        const val FIB_ENCRYPTED_MASK = 0x0100
        const val FIB_WHICH_TABLE_MASK = 0x0200
        const val CLX_PRC_MARKER = 0x01
        const val CLX_PIECE_TABLE_MARKER = 0x02
        const val FC_COMPRESSED_MASK = 0x40000000L
        const val FC_OFFSET_MASK = 0x3fffffffL
        const val PCD_BYTES = 8
        const val MAX_WORD_CHARACTERS = 2_000_000
        const val MAX_WORD_PIECES = 100_000
        const val MAX_WORD_GROUPS = 96
        const val WORD_CELL_MARK = '\u0007'
        val WORD_DAYS = listOf(
            "понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье",
            "панядзел", "аўторак", "серада", "чацвер", "пятніца", "субота", "нядзеля",
        )
        val GROUP_HEADER = Regex(
            "групп[аы]?\\s*([а-яёa-z]{1,3}\\s*[-–—]?\\s*\\d{1,3}|\\d{1,2}(?:[-–—]\\d{1,3})?)",
            RegexOption.IGNORE_CASE,
        )
        val WORD_TIME = Regex(
            "(?:\\d{1,2}\\s+)?(\\d{1,2})(?:[:.]?)(\\d{2})\\s*[-–—−]\\s*(\\d{1,2})(?:[:.]?)(\\d{2})",
        )
        val ISO_WORD_DATE = Regex("\\b\\d{4}-\\d{1,2}-\\d{1,2}\\b")
        val DMY_WORD_DATE = Regex("\\b\\d{1,2}\\.\\d{1,2}(?:\\.\\d{4})?\\b")
        val WHITESPACE = Regex("\\s+")
    }
}

private class WordPapxRowTerminatorLookup(
    private val wordDocument: ByteArray,
    private val tableStream: ByteArray,
) {
    private val pageRanges = readPageRanges()
    private val parsedPages = mutableMapOf<Int, List<PapxRun>>()

    fun isRowTerminator(wordDocumentOffset: Int): Boolean {
        val fc = wordDocumentOffset.toLong()
        var low = 0
        var high = pageRanges.lastIndex
        var range: PapxPageRange? = null
        while (low <= high) {
            val middle = (low + high) ushr 1
            val candidate = pageRanges[middle]
            when {
                fc < candidate.startFc -> high = middle - 1
                fc >= candidate.endFc -> low = middle + 1
                else -> {
                    range = candidate
                    break
                }
            }
        }
        val pageRange = range ?: return false
        val runs = parsedPages.getOrPut(pageRange.pageNumber) { readPapxRuns(pageRange.pageNumber) }
        return runs.any { run -> fc in run.startFc until run.endFc && run.isTtp }
    }

    private fun readPageRanges(): List<PapxPageRange> {
        val offsetLong = runCatching { wordDocument.u32(FIB_PLCF_BTE_PAPX_OFFSET) }.getOrNull() ?: return emptyList()
        val lengthLong = runCatching { wordDocument.u32(FIB_PLCF_BTE_PAPX_LENGTH_OFFSET) }.getOrNull() ?: return emptyList()
        if (offsetLong > Int.MAX_VALUE || lengthLong > Int.MAX_VALUE) return emptyList()
        val offset = offsetLong.toInt()
        val length = lengthLong.toInt()
        if (
            length < 12 || offset < 0 || offset > tableStream.size || length > tableStream.size - offset ||
                (length - 4) % 8 != 0
        ) {
            return emptyList()
        }
        val count = (length - 4) / 8
        if (count !in 1..MAX_PAPX_PAGES) return emptyList()
        val fcOffset = offset
        val pageNumberOffset = offset + (count + 1) * 4
        val boundaries = (0..count).map { index -> tableStream.u32(fcOffset + index * 4) }
        if (boundaries.zipWithNext().any { (start, end) -> end <= start }) return emptyList()
        return buildList {
            repeat(count) { index ->
                val pageNumber = tableStream.u32(pageNumberOffset + index * 4).toInt() and PAPX_PAGE_NUMBER_MASK
                val startFc = boundaries[index]
                val endFc = boundaries[index + 1]
                val pageOffset = pageNumber.toLong() * WORD_PAGE_BYTES
                if (pageOffset + WORD_PAGE_BYTES <= wordDocument.size) {
                    add(PapxPageRange(startFc, endFc, pageNumber))
                }
            }
        }
    }

    private fun readPapxRuns(pageNumber: Int): List<PapxRun> {
        val pageOffset = pageNumber * WORD_PAGE_BYTES
        if (pageOffset < 0 || pageOffset + WORD_PAGE_BYTES > wordDocument.size) return emptyList()
        val count = wordDocument.u8(pageOffset + WORD_PAGE_BYTES - 1)
        if (count !in 1..MAX_PAPX_RUNS) return emptyList()
        val rgbxOffset = pageOffset + (count + 1) * 4
        if (rgbxOffset + count >= pageOffset + WORD_PAGE_BYTES - 1) return emptyList()
        return buildList {
            repeat(count) { runIndex ->
                val startFc = wordDocument.u32(pageOffset + runIndex * 4)
                val endFc = wordDocument.u32(pageOffset + (runIndex + 1) * 4)
                if (endFc <= startFc) return@repeat
                val bxPap = wordDocument.u8(rgbxOffset + runIndex)
                val papxOffset = pageOffset + bxPap * 2
                val pageEnd = pageOffset + WORD_PAGE_BYTES - 1
                if (papxOffset < rgbxOffset + count || papxOffset + 2 > pageEnd) return@repeat
                val cb = wordDocument.u8(papxOffset)
                val grpprlLength: Int
                val grpprlOffset: Int
                if (cb == 0) {
                    val cbPrime = wordDocument.u8(papxOffset + 1)
                    grpprlLength = cbPrime * 2
                    grpprlOffset = papxOffset + 2
                } else {
                    grpprlLength = cb * 2 - 1
                    grpprlOffset = papxOffset + 1
                }
                val propertiesStart = grpprlOffset + 2
                val propertiesEnd = minOf(grpprlOffset + grpprlLength, pageEnd)
                val isTtp = propertiesStart <= propertiesEnd && hasTtpSprm(propertiesStart, propertiesEnd)
                add(PapxRun(startFc, endFc, isTtp))
            }
        }
    }

    private fun hasTtpSprm(start: Int, end: Int): Boolean {
        var cursor = start
        while (cursor + 2 <= end) {
            val sprm = wordDocument.u16(cursor)
            if (sprm == SPRM_PF_TTP) return cursor + 2 < end && wordDocument.u8(cursor + 2) == 1
            val operandOffset = cursor + 2
            val spra = (sprm ushr 13) and 0x07
            val operandLength = when (spra) {
                0, 1 -> 1
                2, 4, 5 -> 2
                3 -> 4
                6 -> {
                    if (operandOffset >= end) return false
                    when (sprm) {
                        SPRM_T_DEF_TABLE -> {
                            if (operandOffset + 1 >= end) return false
                            val cellCount = wordDocument.u8(operandOffset + 1)
                            2 + (cellCount + 1) * 2 + cellCount * 20
                        }
                        SPRM_P_CHG_TABS -> {
                            val byteCount = wordDocument.u8(operandOffset)
                            if (byteCount == 0xff || operandOffset + byteCount + 1 > end) return false
                            byteCount + 1
                        }
                        else -> wordDocument.u8(operandOffset) + 1
                    }
                }
                else -> 3
            }
            if (operandLength < 0 || operandOffset + operandLength > end) return false
            cursor = operandOffset + operandLength
        }
        return false
    }

    private data class PapxPageRange(val startFc: Long, val endFc: Long, val pageNumber: Int)
    private data class PapxRun(val startFc: Long, val endFc: Long, val isTtp: Boolean)

    private companion object {
        const val FIB_PLCF_BTE_PAPX_OFFSET = 0x102
        const val FIB_PLCF_BTE_PAPX_LENGTH_OFFSET = 0x106
        const val WORD_PAGE_BYTES = 512
        const val MAX_PAPX_PAGES = 20_000
        const val MAX_PAPX_RUNS = 29
        const val PAPX_PAGE_NUMBER_MASK = 0x003fffff
        const val SPRM_PF_TTP = 0x2417
        const val SPRM_T_DEF_TABLE = 0xd608
        const val SPRM_P_CHG_TABS = 0xc615
    }
}

private class SstReader(private val segments: List<ByteArray>) {
    private var segmentIndex = 0
    private var segmentOffset = 0
    val remainingBytes: Int get() = segments.drop(segmentIndex).sumOf { it.size } - segmentOffset

    fun readU8(): Int = readRawByte()

    fun readU16(): Int = readU8() or (readU8() shl 8)

    fun readU32(): Long = readU8().toLong() or
        (readU8().toLong() shl 8) or
        (readU8().toLong() shl 16) or
        (readU8().toLong() shl 24)

    fun readCharacters(count: Int, initialUnicode: Boolean, codePage: Int): String = buildString {
        var remaining = count.coerceAtMost(MAX_STRING_CHARS)
        var unicode = initialUnicode
        while (remaining > 0) {
            if (segmentIndex >= segments.size) break
            val segment = segments[segmentIndex]
            if (segmentOffset >= segment.size) {
                segmentIndex++
                segmentOffset = 0
                if (segmentIndex < segments.size) unicode = readRawByte() and 0x01 != 0
                continue
            }
            val bytesPerCharacter = if (unicode) 2 else 1
            val charactersAvailable = (segment.size - segmentOffset) / bytesPerCharacter
            if (charactersAvailable == 0) {
                segmentIndex++
                segmentOffset = 0
                if (segmentIndex < segments.size) unicode = readRawByte() and 0x01 != 0
                continue
            }
            val characters = minOf(remaining, charactersAvailable)
            if (unicode) {
                repeat(characters) {
                    append(((segment.u8(segmentOffset + 1) shl 8) or segment.u8(segmentOffset)).toChar())
                    segmentOffset += 2
                }
            } else {
                repeat(characters) {
                    append(decodeCodePageByte(segment.u8(segmentOffset++), codePage))
                }
            }
            remaining -= characters
            if (remaining > 0 && segmentOffset >= segment.size) {
                segmentIndex++
                segmentOffset = 0
                if (segmentIndex < segments.size) unicode = readRawByte() and 0x01 != 0
            }
        }
    }

    private fun normalizeCursor() {
        while (segmentIndex < segments.size && segmentOffset >= segments[segmentIndex].size) {
            segmentIndex++
            segmentOffset = 0
        }
    }

    private fun readRawByte(): Int {
        normalizeCursor()
        require(segmentIndex < segments.size) { "Повреждена таблица общих строк XLS" }
        return segments[segmentIndex].u8(segmentOffset++)
    }

    private companion object {
        const val MAX_STRING_CHARS = 100_000
    }
}

private fun decodeUtf16Le(bytes: ByteArray, offset: Int, characters: Int): String = buildString {
    repeat(characters.coerceAtLeast(0)) { index ->
        val position = offset + index * 2
        if (position + 1 < bytes.size) append(((bytes.u8(position + 1) shl 8) or bytes.u8(position)).toChar())
    }
}

private fun decodeCodePage(bytes: ByteArray, offset: Int, count: Int, codePage: Int): String = buildString {
    repeat(count.coerceAtLeast(0)) { index ->
        append(decodeCodePageByte(bytes.u8(offset + index), codePage))
    }
}

private fun decodeCodePageByte(value: Int, codePage: Int): Char {
    if (value < 0x80) return value.toChar()
    if (codePage in setOf(1200, 1201)) return value.toChar()
    if (codePage == 1251 || codePage == 0) {
        if (value in 0xc0..0xff) return (0x0410 + value - 0xc0).toChar()
        return WINDOWS_1251_HIGH[value - 0x80]
    }
    if (value in 0x80..0x9f) return WINDOWS_1252_HIGH[value - 0x80]
    return value.toChar()
}

private fun escapeHtml(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("\r\n", "\n")
    .replace("\r", "\n")
    .replace("\n", "<br>")

private fun ByteArray.u8(offset: Int): Int {
    require(offset >= 0 && offset < size) { "Чтение за пределами файла Office" }
    return this[offset].toInt() and 0xff
}

private fun ByteArray.u16(offset: Int): Int = u8(offset) or (u8(offset + 1) shl 8)

private fun ByteArray.u32(offset: Int): Long = u8(offset).toLong() or
    (u8(offset + 1).toLong() shl 8) or
    (u8(offset + 2).toLong() shl 16) or
    (u8(offset + 3).toLong() shl 24)

private fun ByteArray.u64(offset: Int): Long {
    val low = u32(offset)
    val high = u32(offset + 4)
    require(high == 0L) { "Поток Office превышает допустимый размер" }
    return low
}

private fun ByteArray.f64(offset: Int): Double {
    val bits = u32(offset) or (u32(offset + 4) shl 32)
    return Double.fromBits(bits)
}

private val WINDOWS_1251_HIGH = "ЂЃ‚ѓ„…†‡€‰Љ‹ЊЌЋЏђ‘’“”•–—�™љ›њќћџ ЎўЈ¤Ґ¦§Ё©Є«¬­®Ї°±Ііґµ¶·ё№є»јЅѕї"
private val WINDOWS_1252_HIGH = "€�‚ƒ„…†‡ˆ‰Š‹Œ�Ž��‘’“”•–—˜™š›œ�žŸ"
