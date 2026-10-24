package com.example.bruschedule

import com.example.bruschedule.data.scraper.ScheduleDocumentFormat
import com.example.bruschedule.data.remote.BinaryResourceTransport
import com.example.bruschedule.data.remote.BsuScheduleProfileCatalogRepository
import com.example.bruschedule.data.remote.HtmlTransport
import com.example.bruschedule.data.remote.GoogleSheetsScheduleUrl
import com.example.bruschedule.data.scraper.BsuScheduleHtmlParser
import com.example.bruschedule.data.scraper.ScheduleDocumentHtmlAdapter
import com.example.bruschedule.domain.model.ScheduleProfile
import com.example.bruschedule.domain.model.ScheduleProfileType
import com.example.bruschedule.domain.model.UniversityIds
import com.gyanoba.kexcel.Excel
import com.gyanoba.kexcel.sheet.CellIndex
import com.gyanoba.kexcel.sheet.TextCellValue
import no.synth.kmpzip.io.ByteArrayOutputStream
import no.synth.kmpzip.zip.ZipEntry
import no.synth.kmpzip.zip.ZipOutputStream
import kotlinx.datetime.LocalDate
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScheduleDocumentHtmlAdapterTest {
    private val adapter = ScheduleDocumentHtmlAdapter()

    @Test
    fun detectsOpenXmlFormatsBeforeTheirLegacySuffixes() {
        assertEquals(ScheduleDocumentFormat.XLSX, adapter.formatForUrl("https://example.edu/schedule.xlsx?download=1"))
        assertEquals(ScheduleDocumentFormat.DOCX, adapter.formatForUrl("https://example.edu/schedule.docx#page=1"))
        assertEquals(ScheduleDocumentFormat.ZIP, adapter.formatForUrl("https://example.edu/magistracy.zip"))
        assertEquals(ScheduleDocumentFormat.XLS, adapter.formatForUrl("https://example.edu/schedule.xls"))
        assertEquals(ScheduleDocumentFormat.DOC, adapter.formatForUrl("https://example.edu/schedule.doc"))
        assertEquals(ScheduleDocumentFormat.PDF, adapter.formatForUrl("https://example.edu/schedule.pdf"))
    }

    @Test
    fun dropboxShareLinksAreConvertedToDirectDownloadLinks() {
        assertEquals(
            "https://www.dropbox.com/scl/fi/example/schedule.docx?rlkey=abc&dl=1#view",
            adapter.downloadUrl("https://www.dropbox.com/scl/fi/example/schedule.docx?rlkey=abc&dl=0#view"),
        )
        assertEquals(
            "https://other.example/schedule.pdf?dl=0",
            adapter.downloadUrl("https://other.example/schedule.pdf?dl=0"),
        )
    }

    @Test
    fun pdfPageTextIsPreservedAsColumnAlignedHtml() {
        val content = """
            BT /F1 10 Tf 50 700 Td (Day) Tj 100 0 Td (Time) Tj 100 0 Td (Group 1-11) Tj ET
            BT /F1 10 Tf 50 680 Td (Monday) Tj 100 0 Td (08:30-09:50) Tj 100 0 Td (Mathematics) Tj ET
        """.trimIndent()

        val html = adapter.toHtml(ScheduleDocumentFormat.PDF, minimalPdf(content))

        assertTrue(html.contains("<table>"))
        assertTrue(html.contains("Day"))
        assertTrue(html.contains("Group 1-11"))
        assertTrue(html.contains("Monday"))
        assertTrue(html.contains("08:30-09:50"))
        assertTrue(html.contains("Mathematics"))
    }

    @Test
    fun xlsxWorksheetIsConvertedToAnHtmlTable() {
        val workbook = Excel.createExcel()
        val sheet = workbook["Расписание"]
        sheet.updateCell(CellIndex.indexByString("A1"), TextCellValue("День"))
        sheet.updateCell(CellIndex.indexByString("B1"), TextCellValue("Время"))
        sheet.updateCell(CellIndex.indexByString("C1"), TextCellValue("Группа 1-11"))
        sheet.updateCell(CellIndex.indexByString("A2"), TextCellValue("Понедельник"))
        sheet.updateCell(CellIndex.indexByString("B2"), TextCellValue("08:30–10:00"))
        sheet.updateCell(CellIndex.indexByString("C2"), TextCellValue("Математика"))
        val bytes = checkNotNull(workbook.encode())

        val html = adapter.toHtml(ScheduleDocumentFormat.XLSX, bytes)

        assertTrue(html.contains("<table><tbody>"))
        assertTrue(html.contains("Расписание"))
        assertTrue(html.contains("Понедельник"))
        assertTrue(html.contains("08:30–10:00"))
        assertTrue(html.contains("Математика"))
    }

    @Test
    fun zipArchiveConvertsContainedXlsxTimetables() {
        val workbook = Excel.createExcel()
        val sheet = workbook["Расписание"]
        sheet.updateCell(CellIndex.indexByString("A1"), TextCellValue("День"))
        sheet.updateCell(CellIndex.indexByString("B1"), TextCellValue("Время"))
        sheet.updateCell(CellIndex.indexByString("C1"), TextCellValue("Группа 1-11"))
        sheet.updateCell(CellIndex.indexByString("A2"), TextCellValue("Понедельник"))
        sheet.updateCell(CellIndex.indexByString("B2"), TextCellValue("08:30-09:50"))
        sheet.updateCell(CellIndex.indexByColumnRow(2, 1), TextCellValue("Журналистика"))
        val workbookBytes = checkNotNull(workbook.encode())
        val archiveBytes = ByteArrayOutputStream().let { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("magistracy/raspisanie.xlsx"))
                zip.write(workbookBytes)
                zip.closeEntry()
            }
            output.toByteArray()
        }

        val html = adapter.toHtml(ScheduleDocumentFormat.ZIP, archiveBytes)

        assertTrue(html.contains("raspisanie.xlsx"))
        assertTrue(html.contains("Группа 1-11"))
        assertTrue(html.contains("Понедельник"))
        assertTrue(html.contains("Журналистика"))
    }

    @Test
    fun docxTableIsExtractedFromTheOpenXmlPackage() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body><w:tbl>
                <w:tr><w:tc><w:p><w:r><w:t>День</w:t></w:r></w:p></w:tc>
                  <w:tc><w:p><w:r><w:t>Время</w:t></w:r></w:p></w:tc>
                  <w:tc><w:p><w:r><w:t>Группа 1-11</w:t></w:r></w:p></w:tc></w:tr>
                <w:tr><w:tc><w:p><w:r><w:t>Понедельник</w:t></w:r></w:p></w:tc>
                  <w:tc><w:p><w:r><w:t>08:30–10:00</w:t></w:r></w:p></w:tc>
                  <w:tc><w:p><w:r><w:t>Экология &amp; климат</w:t></w:r></w:p></w:tc></w:tr>
              </w:tbl></w:body>
            </w:document>
        """.trimIndent()
        val bytes = ByteArrayOutputStream().let { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("word/document.xml"))
                zip.write(xml.encodeToByteArray())
                zip.closeEntry()
            }
            output.toByteArray()
        }

        val html = adapter.toHtml(ScheduleDocumentFormat.DOCX, bytes)

        assertTrue(html.contains("Понедельник"))
        assertTrue(html.contains("08:30–10:00"))
        assertTrue(html.contains("Экология &amp; климат"))
    }

    @Test
    fun bsuCatalogueDiscoversGroupsInsideAnXlsxTimetableLink() {
        val facultyUrl = "https://fpmi.bsu.by/timetable"
        val fileUrl = "https://fpmi.bsu.by/files/raspisanie.xlsx"
        val workbook = Excel.createExcel()
        val sheet = workbook["Расписание"]
        sheet.updateCell(CellIndex.indexByString("A1"), TextCellValue("День"))
        sheet.updateCell(CellIndex.indexByString("B1"), TextCellValue("Время"))
        sheet.updateCell(CellIndex.indexByString("C1"), TextCellValue("Группа 1-11"))
        sheet.updateCell(CellIndex.indexByString("A2"), TextCellValue("Понедельник"))
        sheet.updateCell(CellIndex.indexByString("B2"), TextCellValue("08:30-09:50"))
        sheet.updateCell(CellIndex.indexByString("C2"), TextCellValue("Математика"))
        val workbookBytes = checkNotNull(workbook.encode())
        var binaryUrl: String? = null
        val transport = object : HtmlTransport, BinaryResourceTransport {
            override suspend fun get(url: String): String = when (url) {
                facultyUrl -> "<a href=\"/files/raspisanie.xlsx\">Расписание групп</a>"
                else -> error("No HTML fixture for $url")
            }

            override suspend fun getBytes(url: String): ByteArray {
                binaryUrl = url
                return workbookBytes
            }
        }

        val matches = runSuspending {
            BsuScheduleProfileCatalogRepository(transport)
                .search(UniversityIds.BSU, ScheduleProfileType.GROUP, "1-11")
        }

        assertEquals(fileUrl, binaryUrl)
        assertEquals(fileUrl, matches.single().sourceUrl)
        assertTrue(matches.single().name.contains("1-11"))
        assertTrue(matches.single().subtitle.orEmpty().contains("XLSX"))
    }

    @Test
    fun bsuDatasetDiscoveryEnumeratesPublishedGroupsAcrossFacultySources() {
        val facultyUrl = "https://fpmi.bsu.by/timetable"
        val fileUrl = "https://fpmi.bsu.by/files/raspisanie.xlsx"
        val workbook = Excel.createExcel()
        val sheet = workbook["Расписание"]
        sheet.updateCell(CellIndex.indexByString("A1"), TextCellValue("День"))
        sheet.updateCell(CellIndex.indexByString("B1"), TextCellValue("Время"))
        sheet.updateCell(CellIndex.indexByString("C1"), TextCellValue("Группа 1-11"))
        sheet.updateCell(CellIndex.indexByString("A2"), TextCellValue("Понедельник"))
        sheet.updateCell(CellIndex.indexByString("B2"), TextCellValue("08:30-09:50"))
        sheet.updateCell(CellIndex.indexByString("C2"), TextCellValue("Математика"))
        val workbookBytes = checkNotNull(workbook.encode())
        var requestedBinaryUrl: String? = null
        val transport = object : HtmlTransport, BinaryResourceTransport {
            override suspend fun get(url: String): String = if (url == facultyUrl) {
                "<a href=\"/files/raspisanie.xlsx\">Расписание групп</a>"
            } else {
                "<html><body></body></html>"
            }

            override suspend fun getBytes(url: String): ByteArray {
                requestedBinaryUrl = url
                return workbookBytes
            }
        }

        val candidates = runSuspending {
            BsuScheduleProfileCatalogRepository(transport).discoverAll(ScheduleProfileType.GROUP)
        }

        assertEquals(fileUrl, requestedBinaryUrl)
        assertEquals(1, candidates.size)
        assertTrue(candidates.single().name.contains("1-11"))
        assertEquals(fileUrl, candidates.single().sourceUrl)
    }

    @Test
    fun bsuCatalogueFindsJournalismGroupsInTheCurrentFacultyXlsx() {
        val facultyUrl = "https://journ.bsu.by/raspisanie.html"
        val fileUrl = "https://journ.bsu.by/images/Journ26_27/Rasklad_1_kurs_26_27_post.xlsx"
        val workbook = Excel.createExcel()
        val sheet = workbook["Расписание"]
        sheet.updateCell(CellIndex.indexByString("A1"), TextCellValue("День"))
        sheet.updateCell(CellIndex.indexByString("B1"), TextCellValue("Время"))
        sheet.updateCell(CellIndex.indexByString("C1"), TextCellValue("Группа 1-11"))
        sheet.updateCell(CellIndex.indexByString("A2"), TextCellValue("Понедельник"))
        sheet.updateCell(CellIndex.indexByString("B2"), TextCellValue("08:30-09:50"))
        sheet.updateCell(CellIndex.indexByString("C2"), TextCellValue("Математика"))
        val workbookBytes = checkNotNull(workbook.encode())
        var requestedBinaryUrl: String? = null
        val transport = object : HtmlTransport, BinaryResourceTransport {
            override suspend fun get(url: String): String = if (url == facultyUrl) {
                "<a href=\"/images/Journ26_27/Rasklad_1_kurs_26_27_post.xlsx\">Первый курс, расписание занятий</a>"
            } else {
                error("No HTML fixture for $url")
            }

            override suspend fun getBytes(url: String): ByteArray {
                requestedBinaryUrl = url
                return workbookBytes
            }
        }

        val matches = runSuspending {
            BsuScheduleProfileCatalogRepository(transport)
                .search(UniversityIds.BSU, ScheduleProfileType.GROUP, "1-11")
        }

        assertEquals(fileUrl, requestedBinaryUrl)
        assertEquals(fileUrl, matches.single().sourceUrl)
        assertTrue(matches.single().name.contains("1-11"))
        assertTrue(matches.single().subtitle.orEmpty().contains("Факультет журналистики"))
    }

    @Test
    fun bsuCatalogueLoadsTheRadiophysicsGoogleSheetAsHtml() {
        val facultyUrl = "https://rct.bsu.by/education"
        val sheetUrl = "https://docs.google.com/spreadsheets/d/1NcWuxSIA7pwh-VB5X7cILjQ1-0uD1cjj0QLt-I5fJh8/edit"
        val htmlUrl = checkNotNull(GoogleSheetsScheduleUrl.toHtmlTableUrl(sheetUrl))
        val timetableHtml = """
            <table>
              <tr><td>Понедельник</td><td>09:00 - 10:20</td><td>Группа 1-11</td></tr>
              <tr><td>Математика</td><td>Аудитория 115</td><td>Преподаватель</td></tr>
            </table>
        """.trimIndent()
        val transport = object : HtmlTransport {
            override suspend fun get(url: String): String = when (url) {
                facultyUrl -> "<a href=\"$sheetUrl\">Расписание занятий 1 курс</a>"
                htmlUrl -> timetableHtml
                else -> error("No HTML fixture for $url")
            }
        }

        val matches = runSuspending {
            BsuScheduleProfileCatalogRepository(transport)
                .search(UniversityIds.BSU, ScheduleProfileType.GROUP, "1-11")
        }

        assertEquals(sheetUrl, matches.single().sourceUrl)
        assertTrue(matches.single().name.contains("1-11"))
        assertTrue(matches.single().subtitle.orEmpty().contains("Google Sheets"))
    }

    @Test
    fun bsuCatalogueDiscoversTimetablesInsideTheJournalismMasterZip() {
        val facultyUrl = "https://journ.bsu.by/raspisanie.html"
        val zipUrl = "https://journ.bsu.by/files/magistratura.zip"
        val workbook = Excel.createExcel()
        val sheet = workbook["Магистратура"]
        sheet.updateCell(CellIndex.indexByString("A1"), TextCellValue("День"))
        sheet.updateCell(CellIndex.indexByString("B1"), TextCellValue("Время"))
        sheet.updateCell(CellIndex.indexByString("C1"), TextCellValue("Группа 1-11"))
        sheet.updateCell(CellIndex.indexByString("A2"), TextCellValue("Понедельник"))
        sheet.updateCell(CellIndex.indexByString("B2"), TextCellValue("08:30-09:50"))
        sheet.updateCell(CellIndex.indexByString("C2"), TextCellValue("Журналистика"))
        val workbookBytes = checkNotNull(workbook.encode())
        val zipBytes = ByteArrayOutputStream().let { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("raspisanie.xlsx"))
                zip.write(workbookBytes)
                zip.closeEntry()
            }
            output.toByteArray()
        }
        val transport = object : HtmlTransport, BinaryResourceTransport {
            override suspend fun get(url: String): String = if (url == facultyUrl) {
                "<a href=\"/files/magistratura.zip\">Расписание магистратуры (ZIP)</a>"
            } else {
                error("No HTML fixture for $url")
            }

            override suspend fun getBytes(url: String): ByteArray = zipBytes
        }

        val matches = runSuspending {
            BsuScheduleProfileCatalogRepository(transport)
                .search(UniversityIds.BSU, ScheduleProfileType.GROUP, "1-11")
        }

        assertEquals(zipUrl, matches.single().sourceUrl)
        assertTrue(matches.single().name.contains("1-11"))
        assertTrue(matches.single().subtitle.orEmpty().contains("ZIP"))
    }

    @Test
    fun bsuCatalogueFollowsDropboxFolderLinksToDocxSchedules() {
        val facultyUrl = "https://chemistry.bsu.by/index.php/ru/studentam/grafik-uchebnogo-protsessa/raspisanie"
        val folderUrl = "https://www.dropbox.com/scl/fo/course-folder?rlkey=key&dl=0"
        val docxUrl = "https://www.dropbox.com/scl/fi/course-folder/file/1kurs.docx?rlkey=key&dl=0"
        val documentXml = """
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body><w:tbl>
                <w:tr><w:tc><w:p><w:r><w:t>Группа 1-11</w:t></w:r></w:p></w:tc>
                  <w:tc><w:p><w:r><w:t>День</w:t></w:r></w:p></w:tc>
                  <w:tc><w:p><w:r><w:t>Время</w:t></w:r></w:p></w:tc>
                  <w:tc><w:p><w:r><w:t>Предмет</w:t></w:r></w:p></w:tc></w:tr>
                <w:tr><w:tc><w:p><w:r><w:t>Группа 1-11</w:t></w:r></w:p></w:tc>
                  <w:tc><w:p><w:r><w:t>Понедельник</w:t></w:r></w:p></w:tc>
                  <w:tc><w:p><w:r><w:t>08:30-09:50</w:t></w:r></w:p></w:tc>
                  <w:tc><w:p><w:r><w:t>Математика</w:t></w:r></w:p></w:tc></w:tr>
              </w:tbl></w:body>
            </w:document>
        """.trimIndent()
        val docxBytes = ByteArrayOutputStream().let { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("word/document.xml"))
                zip.write(documentXml.encodeToByteArray())
                zip.closeEntry()
            }
            output.toByteArray()
        }
        var requestedBinaryUrl: String? = null
        val transport = object : HtmlTransport, BinaryResourceTransport {
            override suspend fun get(url: String): String = when (url) {
                facultyUrl -> "<a href=\"$folderUrl\">1 курс расписание</a>"
                folderUrl -> "<a href=\"${docxUrl.replace("&", "&amp;")}\">Группа 1-11</a>"
                else -> error("No HTML fixture for $url")
            }

            override suspend fun getBytes(url: String): ByteArray {
                requestedBinaryUrl = url
                return docxBytes
            }
        }

        val matches = runSuspending {
            BsuScheduleProfileCatalogRepository(transport)
                .search(UniversityIds.BSU, ScheduleProfileType.GROUP, "1-11")
        }

        assertEquals(adapter.downloadUrl(docxUrl), requestedBinaryUrl)
        assertEquals(docxUrl, matches.single().sourceUrl)
        assertTrue(matches.single().name.contains("1-11"))
        assertTrue(matches.single().subtitle.orEmpty().contains("Химический факультет"))
    }

    @Test
    fun legacyXlsWorkbookIsParsedThroughTheSelectedLawFacultyGroup() {
        val rows = listOf(
            listOf("Дни", "Время", "Дата", "№ группы", "Название дисциплины", "Преподаватель", "Аудитория"),
            listOf("Понедельник", "8.00-9.20", "", "1", "Теория государства и права", "Иванова И.И.", "115"),
            listOf("", "9.35-10.55", "", "2", "История государства", "Петров П.П.", "216"),
        )
        val workbook = minimalBiffWorkbook(rows, mapOf((1 to 2) to 46_321.0))
        val html = adapter.toHtml(ScheduleDocumentFormat.XLS, minimalOleWorkbook(workbook))
        val profile = ScheduleProfile(
            id = "bsu-law-1",
            type = ScheduleProfileType.GROUP,
            name = "1 группа",
            sourceUrl = "https://law.bsu.by/pub/2/Raspisanie_1_pravo.xls",
            sourceKey = "1 группа",
            universityId = UniversityIds.BSU,
        )

        val lesson = BsuScheduleHtmlParser().parse(profile, html, LocalDate(2026, 10, 26)).single()

        assertTrue(html.contains("<table>"))
        assertEquals("Теория государства и права", lesson.subjectName)
        assertEquals("Иванова И.И.", lesson.teacherName)
        assertEquals("115", lesson.classroom)
        assertEquals("08:00", lesson.startTime)
        assertEquals("09:20", lesson.endTime)
        assertEquals("2026-10-26", lesson.lessonDate)
    }

    @Test
    fun bsuCatalogueDiscoversLawFacultyGroupsFromLegacyXls() {
        val facultyUrl = "https://law.bsu.by/index.php?option=com_content&view=article&id=2350&Itemid=7"
        val fileUrl = "https://law.bsu.by/pub/2/Raspisanie_1_pravo.xls"
        val workbook = minimalBiffWorkbook(
            listOf(
                listOf("Дни", "Время", "Дата", "№ группы", "Название дисциплины", "Преподаватель", "Аудитория"),
                listOf("Понедельник", "8.00-9.20", "", "1", "Теория государства и права", "Иванова И.И.", "115"),
                listOf("", "9.35-10.55", "", "2", "История государства", "Петров П.П.", "216"),
            ),
            mapOf((1 to 2) to 46_321.0),
        )
        val workbookBytes = minimalOleWorkbook(workbook)
        var requestedBinaryUrl: String? = null
        val transport = object : HtmlTransport, BinaryResourceTransport {
            override suspend fun get(url: String): String = if (url == facultyUrl) {
                "<a href=\"/pub/2/Raspisanie_1_pravo.xls\">Расписание 1 курса</a>"
            } else {
                error("No HTML fixture for $url")
            }

            override suspend fun getBytes(url: String): ByteArray {
                requestedBinaryUrl = url
                return workbookBytes
            }
        }

        val matches = runSuspending {
            BsuScheduleProfileCatalogRepository(transport)
                .search(UniversityIds.BSU, ScheduleProfileType.GROUP, "1 группа")
        }

        assertEquals(fileUrl, requestedBinaryUrl)
        assertEquals(fileUrl, matches.single().sourceUrl)
        assertEquals("1 группа", matches.single().sourceKey)
        assertTrue(matches.single().subtitle.orEmpty().contains("Юридический факультет"))
    }

    @Test
    fun legacyDocReaderUsesParagraphPropertiesForWordTableRowEndsAndBothPieceEncodings() {
        val profile = ScheduleProfile(
            id = "bsu-geo-1-11",
            type = ScheduleProfileType.GROUP,
            name = "1-11",
            sourceUrl = "https://geo.bsu.by/files/1kurs.doc",
            sourceKey = "1-11",
            universityId = UniversityIds.BSU,
        )

        listOf(false, true).forEach { compressed ->
            val docStreams = minimalDocStreamsWithOneTableRow(compressed)
            val bytes = minimalOleStreams(mapOf("WordDocument" to docStreams.first, "0Table" to docStreams.second))
            val html = adapter.toHtml(ScheduleDocumentFormat.DOC, bytes)
            val lesson = BsuScheduleHtmlParser().parse(profile, html, LocalDate(2026, 10, 26)).single()

            assertTrue(html.contains("Группа 1-11"))
            assertTrue(html.contains("Понедельник"))
            assertEquals("08:00", lesson.startTime)
            assertEquals("09:20", lesson.endTime)
            assertEquals("Математика", lesson.subjectName)
            assertEquals("2026-10-26", lesson.lessonDate)
        }
    }

    private fun minimalDocStreamsWithOneTableRow(compressed: Boolean): Pair<ByteArray, ByteArray> {
        val text = "Группа 1-11\u0007Понедельник\u0007Дата 26.10.2026\u000708.00-09.20\u0007Математика\u0007"
        val wordDocument = ByteArray(4096)
        val tableStream = ByteArray(4096)
        fun putU16(bytes: ByteArray, offset: Int, value: Int) {
            bytes[offset] = value.toByte()
            bytes[offset + 1] = (value ushr 8).toByte()
        }
        fun putU32(bytes: ByteArray, offset: Int, value: Long) {
            repeat(4) { index -> bytes[offset + index] = (value ushr (index * 8)).toByte() }
        }

        val textOffset = 1024
        val bytesPerCharacter = if (compressed) 1 else 2
        val textBytes = if (compressed) encodeWindows1251(text) else ByteArrayOutputStream().apply {
            text.forEach { character -> writeU16(this, character.code) }
        }.toByteArray()
        textBytes.copyInto(wordDocument, textOffset)
        putU16(wordDocument, 0, 0xa5ec)
        putU16(wordDocument, 6, 0x0419)
        putU32(wordDocument, 0x4c, text.length.toLong())
        putU32(wordDocument, 0x102, 0x300)
        putU32(wordDocument, 0x106, 12)
        putU32(wordDocument, 0x1a2, 0x200)

        val pieceTable = ByteArrayOutputStream().apply {
            writeU32(this, 0)
            writeU32(this, text.length.toLong())
            writeU16(this, 0)
            val storedTextOffset = if (compressed) {
                0x40000000L or (textOffset.toLong() * 2)
            } else {
                textOffset.toLong()
            }
            writeU32(this, storedTextOffset)
            writeU16(this, 0)
        }.toByteArray()
        val clx = byteArrayOf(0x02) + pieceTable
        clx.copyInto(tableStream, 0x200)
        putU32(wordDocument, 0x1a6, clx.size.toLong())

        val textCellEnds = text.indices.filter { text[it] == '\u0007' }
        val fcBoundaries = listOf(textOffset.toLong()) + textCellEnds.map { index ->
            (textOffset + (index + 1) * bytesPerCharacter).toLong()
        }
        val textFcEnd = fcBoundaries.last()
        val papxRunCount = fcBoundaries.size - 1
        putU32(tableStream, 0x300, textOffset.toLong())
        putU32(tableStream, 0x304, textFcEnd)
        putU32(tableStream, 0x308, 4)

        val pageOffset = 4 * 512
        fcBoundaries.forEachIndexed { index, fc -> putU32(wordDocument, pageOffset + index * 4, fc) }
        val rgbxOffset = pageOffset + fcBoundaries.size * 4
        repeat(papxRunCount) { index ->
            wordDocument[rgbxOffset + index] = (if (index == papxRunCount - 1) 248 else 245).toByte()
        }
        wordDocument[pageOffset + 511] = papxRunCount.toByte()
        val normalPapxOffset = pageOffset + 490
        wordDocument[normalPapxOffset] = 0
        wordDocument[normalPapxOffset + 1] = 1
        putU16(wordDocument, normalPapxOffset + 2, 0)
        val rowEndPapxOffset = pageOffset + 496
        wordDocument[rowEndPapxOffset] = 0
        wordDocument[rowEndPapxOffset + 1] = 3
        putU16(wordDocument, rowEndPapxOffset + 2, 0)
        putU16(wordDocument, rowEndPapxOffset + 4, 0x2417)
        wordDocument[rowEndPapxOffset + 6] = 1
        return wordDocument to tableStream
    }

    private fun minimalBiffWorkbook(
        rows: List<List<String>>,
        numericCells: Map<Pair<Int, Int>, Double> = emptyMap(),
    ): ByteArray {
        val strings = rows.flatten().filter(String::isNotBlank).distinct()
        val globalBof = biffRecord(0x0809, ByteArray(16))
        val dateXf = biffRecord(0x00e0, byteArrayOf(0, 0, 14, 0))
        val sstData = ByteArrayOutputStream().apply {
            writeU32(this, strings.size.toLong())
            writeU32(this, strings.size.toLong())
            strings.forEach { value ->
                writeU16(this, value.length)
                writeU8(this, 0x01)
                value.forEach { character -> writeU16(this, character.code) }
            }
        }.toByteArray()
        val sstRecord = biffRecord(0x00fc, sstData)
        val globalEof = biffRecord(0x000a, byteArrayOf())
        val boundSheetPlaceholder = biffRecord(0x0085, boundSheetData(0, "Расписание"))
        val sheetOffset = globalBof.size + boundSheetPlaceholder.size + sstRecord.size + dateXf.size + globalEof.size
        val boundSheet = biffRecord(0x0085, boundSheetData(sheetOffset, "Расписание"))
        val sheetRecords = ByteArrayOutputStream().apply {
            writeBytes(this, biffRecord(0x0809, ByteArray(16)))
            rows.forEachIndexed { rowIndex, row ->
                row.forEachIndexed { columnIndex, value ->
                    if (value.isNotBlank()) {
                        val cell = ByteArrayOutputStream().apply {
                            writeU16(this, rowIndex)
                            writeU16(this, columnIndex)
                            writeU16(this, 0)
                            writeU32(this, strings.indexOf(value).toLong())
                        }.toByteArray()
                        writeBytes(this, biffRecord(0x00fd, cell))
                    }
                }
            }
            numericCells.forEach { (coordinate, value) ->
                val cell = ByteArrayOutputStream().apply {
                    writeU16(this, coordinate.first)
                    writeU16(this, coordinate.second)
                    writeU16(this, 0)
                    writeU64(this, value.toBits())
                }.toByteArray()
                writeBytes(this, biffRecord(0x0203, cell))
            }
            writeBytes(this, biffRecord(0x000a, byteArrayOf()))
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            writeBytes(this, globalBof)
            writeBytes(this, boundSheet)
            writeBytes(this, sstRecord)
            writeBytes(this, dateXf)
            writeBytes(this, globalEof)
            writeBytes(this, sheetRecords)
        }.toByteArray()
    }

    private fun boundSheetData(sheetOffset: Int, sheetName: String): ByteArray =
        ByteArrayOutputStream().apply {
            writeU32(this, sheetOffset.toLong())
            writeU8(this, 0)
            writeU8(this, 0)
            writeU8(this, sheetName.length)
            writeU8(this, 0x01)
            sheetName.forEach { character -> writeU16(this, character.code) }
        }.toByteArray()

    private fun biffRecord(recordId: Int, data: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            writeU16(this, recordId)
            writeU16(this, data.size)
            writeBytes(this, data)
        }.toByteArray()

    private fun minimalOleWorkbook(workbook: ByteArray): ByteArray =
        minimalOleStreams(mapOf("Workbook" to workbook))

    private fun minimalOleStreams(streams: Map<String, ByteArray>): ByteArray {
        require(streams.size in 1..3)
        require(streams.keys.all { it.length <= 31 })
        val sectorSize = 512
        val paddedStreams = streams.mapValues { (_, content) ->
            val size = maxOf(4096, ((content.size + sectorSize - 1) / sectorSize) * sectorSize)
            ByteArray(size).also { content.copyInto(it) }
        }
        val totalStreamSectors = paddedStreams.values.sumOf { it.size / sectorSize }
        val totalSectors = 2 + totalStreamSectors
        require(totalSectors <= 128)
        val file = ByteArray(sectorSize * (totalSectors + 1))
        byteArrayOf(0xd0.toByte(), 0xcf.toByte(), 0x11, 0xe0.toByte(), 0xa1.toByte(), 0xb1.toByte(), 0x1a, 0xe1.toByte())
            .copyInto(file, 0)
        fun putU16(offset: Int, value: Int) {
            file[offset] = value.toByte()
            file[offset + 1] = (value ushr 8).toByte()
        }
        fun putU32(offset: Int, value: Long) {
            repeat(4) { index -> file[offset + index] = (value ushr (index * 8)).toByte() }
        }
        fun putU64(offset: Int, value: Long) {
            repeat(8) { index -> file[offset + index] = (value ushr (index * 8)).toByte() }
        }
        putU16(24, 0x003e)
        putU16(26, 3)
        putU16(28, 0xfffe)
        putU16(30, 9)
        putU16(32, 6)
        putU32(40, 0)
        putU32(44, 1)
        putU32(48, 1)
        putU32(56, 4096)
        putU32(60, 0xfffffffeL)
        putU32(64, 0)
        putU32(68, 0xfffffffeL)
        putU32(72, 0)
        putU32(76, 0)
        repeat(108) { index -> putU32(80 + index * 4, 0xffffffffL) }

        val fatEntries = MutableList(128) { 0xffffffffL }
        fatEntries[0] = 0xfffffffdL
        fatEntries[1] = 0xfffffffeL
        var nextSector = 2
        paddedStreams.values.forEach { content ->
            val streamSectorCount = content.size / sectorSize
            repeat(streamSectorCount) { index ->
                fatEntries[nextSector + index] =
                    if (index == streamSectorCount - 1) 0xfffffffeL else (nextSector + index + 1).toLong()
            }
            nextSector += streamSectorCount
        }
        fatEntries.forEachIndexed { index, value -> putU32(sectorSize + index * 4, value) }

        fun writeDirectoryEntry(index: Int, name: String, type: Int, startSector: Long, size: Long) {
            val offset = sectorSize * 2 + index * 128
            name.forEachIndexed { characterIndex, character ->
                putU16(offset + characterIndex * 2, character.code)
            }
            putU16(offset + name.length * 2, 0)
            putU16(offset + 64, (name.length + 1) * 2)
            file[offset + 66] = type.toByte()
            file[offset + 67] = 1
            putU32(offset + 116, startSector)
            putU64(offset + 120, size)
        }
        writeDirectoryEntry(0, "Root Entry", 5, 0xfffffffeL, 0)
        var streamStartSector = 2
        var directoryIndex = 1
        paddedStreams.forEach { (name, content) ->
            writeDirectoryEntry(directoryIndex++, name, 2, streamStartSector.toLong(), content.size.toLong())
            content.copyInto(file, (streamStartSector + 1) * sectorSize)
            streamStartSector += content.size / sectorSize
        }
        return file
    }

    private fun encodeWindows1251(text: String): ByteArray = ByteArray(text.length) { index ->
        val character = text[index]
        when {
            character.code <= 0x7f -> character.code.toByte()
            character in '\u0410'..'\u042f' -> (0xc0 + character.code - 0x0410).toByte()
            character in '\u0430'..'\u044f' -> (0xe0 + character.code - 0x0430).toByte()
            character == '\u0401' -> 0xa8.toByte()
            character == '\u0451' -> 0xb8.toByte()
            else -> error("Character is not represented in this test CP1251 fixture: $character")
        }
    }

    private fun writeBytes(output: ByteArrayOutputStream, bytes: ByteArray) {
        output.write(bytes, 0, bytes.size)
    }

    private fun writeU8(output: ByteArrayOutputStream, value: Int) =
        writeBytes(output, byteArrayOf(value.toByte()))

    private fun writeU16(output: ByteArrayOutputStream, value: Int) = writeBytes(
        output,
        byteArrayOf(value.toByte(), (value ushr 8).toByte()),
    )

    private fun writeU32(output: ByteArrayOutputStream, value: Long) = writeBytes(
        output,
        byteArrayOf(
            value.toByte(),
            (value ushr 8).toByte(),
            (value ushr 16).toByte(),
            (value ushr 24).toByte(),
        ),
    )

    private fun writeU64(output: ByteArrayOutputStream, value: Long) = writeBytes(
        output,
        ByteArray(8) { index -> (value ushr (index * 8)).toByte() },
    )

    private fun minimalPdf(content: String): ByteArray {
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            "<< /Length ${content.encodeToByteArray().size} >>\nstream\n$content\nendstream",
        )
        val pdf = StringBuilder("%PDF-1.4\n")
        val offsets = mutableListOf(0)
        objects.forEachIndexed { index, value ->
            offsets += pdf.toString().encodeToByteArray().size
            pdf.append("${index + 1} 0 obj\n$value\nendobj\n")
        }
        val xrefOffset = pdf.toString().encodeToByteArray().size
        pdf.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.drop(1).forEach { offset ->
            pdf.append(offset.toString().padStart(10, '0')).append(" 00000 n \n")
        }
        pdf.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\n")
            .append("startxref\n$xrefOffset\n%%EOF")
        return pdf.toString().encodeToByteArray()
    }

    private fun <T> runSuspending(block: suspend () -> T): T {
        var value: Result<T>? = null
        block.startCoroutine(object : kotlin.coroutines.Continuation<T> {
            override val context = kotlin.coroutines.EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) {
                value = result
            }
        })
        return checkNotNull(value).getOrThrow()
    }
}
