package com.example.bruschedule.data.scraper

import com.gyanoba.kexcel.Excel
import com.gyanoba.kexcel.sheet.CellIndex
import com.gyanoba.kexcel.sheet.CellValue
import com.gyanoba.kexcel.sheet.DateCellValue
import com.gyanoba.kexcel.sheet.DateTimeCellValue
import com.gyanoba.kexcel.sheet.TimeCellValue
import io.github.yuroyami.kitepdf.PdfDocument
import no.synth.kmpzip.io.ByteArrayOutputStream
import no.synth.kmpzip.io.readBytes
import no.synth.kmpzip.zip.ZipInputStream

enum class ScheduleDocumentFormat(val extension: String, val displayName: String, val isSupported: Boolean) {
    PDF(".pdf", "PDF", true),
    XLSX(".xlsx", "XLSX", true),
    DOCX(".docx", "DOCX", true),
    ZIP(".zip", "ZIP", true),
    XLS(".xls", "XLS 97–2003", true),
    DOC(".doc", "DOC 97–2003", true),
}

class ScheduleDocumentHtmlAdapter {
    fun formatForUrl(url: String): ScheduleDocumentFormat? {
        val path = url.substringBefore('#').substringBefore('?').lowercase()
        return ScheduleDocumentFormat.entries
            .sortedByDescending { it.extension.length }
            .firstOrNull { path.endsWith(it.extension) }
    }

    fun downloadUrl(url: String): String {
        val host = url.substringAfter("://", "").substringBefore('/').substringBefore(':')
        if (!host.equals("dropbox.com", ignoreCase = true) &&
            !host.endsWith(".dropbox.com", ignoreCase = true)
        ) return url

        val fragmentIndex = url.indexOf('#')
        val fragment = if (fragmentIndex >= 0) url.substring(fragmentIndex) else ""
        val withoutFragment = if (fragmentIndex >= 0) url.substring(0, fragmentIndex) else url
        val queryIndex = withoutFragment.indexOf('?')
        val base = if (queryIndex >= 0) withoutFragment.substring(0, queryIndex) else withoutFragment
        val parameters = if (queryIndex >= 0) {
            withoutFragment.substring(queryIndex + 1).split('&').filter(String::isNotBlank).toMutableList()
        } else {
            mutableListOf()
        }
        val downloadIndex = parameters.indexOfFirst { it.substringBefore('=') == "dl" }
        if (downloadIndex >= 0) parameters[downloadIndex] = "dl=1" else parameters += "dl=1"
        return "$base?${parameters.joinToString("&")}$fragment"
    }

    fun toHtml(format: ScheduleDocumentFormat, bytes: ByteArray): String {
        require(bytes.isNotEmpty()) { "Файл расписания пуст" }
        require(format.isSupported) {
            "Формат ${format.extension} не поддерживается; используйте PDF, XLSX, DOCX, XLS или DOC"
        }
        return when (format) {
            ScheduleDocumentFormat.PDF -> pdfToHtml(bytes)
            ScheduleDocumentFormat.XLSX -> xlsxToHtml(bytes)
            ScheduleDocumentFormat.DOCX -> docxToHtml(bytes)
            ScheduleDocumentFormat.ZIP -> zipToHtml(bytes)
            ScheduleDocumentFormat.XLS -> LegacyXlsHtmlParser().toHtml(bytes)
            ScheduleDocumentFormat.DOC -> LegacyDocHtmlParser().toHtml(bytes)
        }
    }

    private fun pdfToHtml(bytes: ByteArray): String {
        val document = PdfDocument.open(bytes)
        require(document.pages.isNotEmpty()) { "В PDF не найдено страниц" }
        val tables = document.pages.take(MAX_PDF_PAGES).mapIndexed { pageIndex, page ->
            val lines = page.structuredText.blocks.flatMap { it.lines }
                .filter { line -> line.spans.any { it.text.isNotBlank() } }
                .take(MAX_PDF_LINES_PER_PAGE)
            val spans = lines.flatMap { it.spans }.filter { it.text.isNotBlank() }
            val columns = pdfColumnAnchors(spans.map { it.bounds.left })
            buildString {
                append("<h2>PDF · ${pageIndex + 1}</h2><table><tbody>")
                lines.forEach { line ->
                    val cells = MutableList(columns.size) { StringBuilder() }
                    line.spans.filter { it.text.isNotBlank() }.forEach { span ->
                        val columnIndex = columns.indices.minByOrNull { index ->
                            kotlin.math.abs(columns[index] - span.bounds.left)
                        }
                        if (columnIndex != null) {
                            val cell = cells[columnIndex]
                            if (cell.isNotEmpty()) cell.append(' ')
                            cell.append(span.text.trim())
                        }
                    }
                    append("<tr>")
                    cells.forEach { cell -> append("<td>").append(escapeHtml(cell.toString())).append("</td>") }
                    append("</tr>")
                }
                append("</tbody></table>")
            }
        }
        return "<html><body>${tables.joinToString("\n")}</body></html>"
    }

    private fun zipToHtml(bytes: ByteArray): String {
        val archive = ZipInputStream(bytes)
        val documents = mutableListOf<String>()
        val legacyFormats = mutableSetOf<ScheduleDocumentFormat>()
        var entryCount = 0
        var totalUncompressedBytes = 0
        try {
            while (true) {
                val entry = archive.nextEntry ?: break
                entryCount++
                require(entryCount <= MAX_ZIP_ENTRIES) { "В ZIP слишком много файлов" }
                if (entry.isDirectory) {
                    archive.closeEntry()
                    continue
                }
                val remainingBytes = MAX_ZIP_UNCOMPRESSED_BYTES - totalUncompressedBytes
                require(remainingBytes > 0) { "Содержимое ZIP превышает допустимый размер" }
                val bytesForEntry = readZipEntry(
                    archive = archive,
                    maxBytes = minOf(MAX_ZIP_ENTRY_BYTES, remainingBytes),
                )
                totalUncompressedBytes += bytesForEntry.size
                val format = formatForUrl(entry.name) ?: continue
                if (format == ScheduleDocumentFormat.ZIP) continue
                if (!format.isSupported) {
                    legacyFormats += format
                    continue
                }
                if (documents.size >= MAX_ZIP_SCHEDULE_FILES) continue
                val html = try {
                    toHtml(format, bytesForEntry)
                } catch (_: Exception) {
                    continue
                }
                documents += "<section><h2>${escapeHtml(entry.name)}</h2>$html</section>"
            }
        } finally {
            archive.close()
        }
        if (documents.isEmpty() && legacyFormats.isNotEmpty()) {
            val formats = legacyFormats.map(ScheduleDocumentFormat::extension).sorted().joinToString(", ")
            error("В ZIP найдены неподдерживаемые старые форматы: $formats")
        }
        require(documents.isNotEmpty()) { "В ZIP не найдены поддерживаемые файлы расписания" }
        return "<html><body>${documents.joinToString("\n")}</body></html>"
    }

    private fun readZipEntry(archive: ZipInputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(ZIP_BUFFER_SIZE)
        var totalBytes = 0
        while (true) {
            val read = archive.read(buffer, 0, buffer.size)
            if (read < 0) break
            totalBytes += read
            require(totalBytes <= maxBytes) { "Содержимое ZIP превышает допустимый размер" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun xlsxToHtml(bytes: ByteArray): String {
        val workbook = Excel.decodeBytes(bytes)
        val sheets = workbook.getSheets().entries.take(MAX_XLSX_SHEETS)
        require(sheets.isNotEmpty()) { "В XLSX не найдено листов" }
        val tables = sheets.map { (sheetName, sheet) ->
            if (sheet.maxRows <= 0 || sheet.maxColumns <= 0) return@map ""
            val rowCount = minOf(sheet.maxRows, MAX_XLSX_ROWS)
            val columnCount = minOf(sheet.maxColumns, MAX_XLSX_COLUMNS)
            val rows = sheet.selectRangeValues(
                CellIndex.indexByColumnRow(columnIndex = 0, rowIndex = 0),
                CellIndex.indexByColumnRow(columnIndex = columnCount - 1, rowIndex = rowCount - 1),
            )
            buildString {
                append("<h2>").append(escapeHtml(sheetName)).append("</h2><table><tbody>")
                rows.forEach { row ->
                    append("<tr>")
                    row.take(columnCount).forEach { value ->
                        append("<td>").append(escapeHtml(cellText(value))).append("</td>")
                    }
                    repeat((columnCount - row.size).coerceAtLeast(0)) { append("<td></td>") }
                    append("</tr>")
                }
                append("</tbody></table>")
            }
        }.filter(String::isNotBlank)
        require(tables.isNotEmpty()) { "В XLSX не найдено заполненных ячеек" }
        return "<html><body>${tables.joinToString("\n")}</body></html>"
    }

    private fun docxToHtml(bytes: ByteArray): String {
        val documentXml = zipEntry(bytes, WORD_DOCUMENT_ENTRY)?.decodeToString()
            ?: error("В DOCX не найден word/document.xml")
        val tableMatches = DOCX_TABLE.findAll(documentXml).toList()
        val tables = tableMatches.mapNotNull { tableMatch ->
            val rows = DOCX_ROW.findAll(tableMatch.groupValues[1]).map { rowMatch ->
                DOCX_CELL.findAll(rowMatch.groupValues[1]).map { cellMatch ->
                    val cellXml = cellMatch.groupValues[1]
                    DOCX_TEXT.findAll(cellXml).joinToString(" ") { decodeXml(it.groupValues[1]) }
                        .replace(DOCX_TAB_OR_BREAK, " ")
                        .trim()
                }.toList()
            }.toList()
            rows.takeIf { it.isNotEmpty() }?.let { renderRows(it) }
        }
        if (tables.isNotEmpty()) return "<html><body>${tables.joinToString("\n")}</body></html>"

        val paragraphs = DOCX_PARAGRAPH.findAll(documentXml).map { paragraph ->
            DOCX_TEXT.findAll(paragraph.groupValues[1]).joinToString(" ") { decodeXml(it.groupValues[1]) }.trim()
        }.filter(String::isNotBlank).toList()
        require(paragraphs.isNotEmpty()) { "В DOCX не найдено текста расписания" }
        return renderRows(paragraphs.map { listOf(it) })
    }

    private fun renderRows(rows: List<List<String>>): String = buildString {
        append("<table><tbody>")
        rows.take(MAX_DOCX_ROWS).forEach { row ->
            append("<tr>")
            row.take(MAX_DOCX_COLUMNS).forEach { cell ->
                append("<td>").append(escapeHtml(cell)).append("</td>")
            }
            append("</tr>")
        }
        append("</tbody></table>")
    }

    private fun zipEntry(bytes: ByteArray, entryName: String): ByteArray? {
        val archive = ZipInputStream(bytes)
        try {
            while (true) {
                val entry = archive.nextEntry ?: return null
                if (entry.name == entryName) return archive.readBytes()
                archive.closeEntry()
            }
        } finally {
            archive.close()
        }
    }

    private fun pdfColumnAnchors(positions: List<Double>): List<Double> {
        val sorted = positions.filter(Double::isFinite).sorted()
        if (sorted.isEmpty()) return emptyList()
        val clusters = mutableListOf<MutableList<Double>>()
        sorted.forEach { position ->
            val last = clusters.lastOrNull()
            val center = last?.average()
            if (last == null || center == null || position - center > PDF_COLUMN_CLUSTER_DISTANCE) {
                clusters += mutableListOf(position)
            } else {
                last += position
            }
        }
        val frequent = clusters
            .sortedByDescending { it.size }
            .take(MAX_PDF_COLUMNS)
            .map { it.average() }
            .sorted()
        return frequent.ifEmpty { listOf(sorted.first()) }
    }

    private fun cellText(value: Any?): String = when (value) {
        null -> ""
        is CellValue -> when (value) {
            is DateCellValue -> value.asLocalDate().toString()
            is DateTimeCellValue -> value.asDateTimeLocal().toString()
            is TimeCellValue -> value.toString().removeSuffix(":00")
            else -> value.toString()
        }
        else -> value.toString()
    }

    private fun decodeXml(value: String): String {
        val named = value.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
        return XML_NUMERIC_ENTITY.replace(named) { match ->
            val token = match.groupValues[1]
            val codePoint = if (token.startsWith('x', ignoreCase = true)) {
                token.drop(1).toIntOrNull(16)
            } else {
                token.toIntOrNull()
            }
            codePoint?.takeIf { it in 0..0xffff }?.toChar()?.toString() ?: match.value
        }
    }

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private companion object {
        const val WORD_DOCUMENT_ENTRY = "word/document.xml"
        const val MAX_PDF_PAGES = 32
        const val MAX_PDF_LINES_PER_PAGE = 400
        const val MAX_PDF_COLUMNS = 24
        const val MAX_XLSX_SHEETS = 20
        const val MAX_XLSX_ROWS = 4_000
        const val MAX_XLSX_COLUMNS = 96
        const val MAX_DOCX_ROWS = 3_000
        const val MAX_DOCX_COLUMNS = 96
        const val MAX_ZIP_ENTRIES = 128
        const val MAX_ZIP_ENTRY_BYTES = 20 * 1024 * 1024
        const val MAX_ZIP_UNCOMPRESSED_BYTES = 60 * 1024 * 1024
        const val MAX_ZIP_SCHEDULE_FILES = 24
        const val ZIP_BUFFER_SIZE = 8 * 1024
        const val PDF_COLUMN_CLUSTER_DISTANCE = 28.0
        val DOCX_TABLE = Regex("<w:tbl\\b[^>]*>(.*?)</w:tbl>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val DOCX_ROW = Regex("<w:tr\\b[^>]*>(.*?)</w:tr>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val DOCX_CELL = Regex("<w:tc\\b[^>]*>(.*?)</w:tc>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val DOCX_TEXT = Regex("<w:t\\b[^>]*>(.*?)</w:t>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val DOCX_PARAGRAPH = Regex("<w:p\\b[^>]*>(.*?)</w:p>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val DOCX_TAB_OR_BREAK = Regex("<w:(?:tab|br)\\b[^>]*/>", RegexOption.IGNORE_CASE)
        val XML_NUMERIC_ENTITY = Regex("&#(x[0-9a-f]+|[0-9]+);", RegexOption.IGNORE_CASE)
    }
}
