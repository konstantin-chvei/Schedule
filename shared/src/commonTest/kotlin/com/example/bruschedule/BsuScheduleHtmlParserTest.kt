package com.example.bruschedule

import com.example.bruschedule.data.scraper.BsuScheduleHtmlParser
import com.example.bruschedule.data.scraper.BsuTeacherNameMatcher
import com.example.bruschedule.data.scraper.BsuTeacherProfileSourceKey
import com.example.bruschedule.domain.model.ScheduleProfile
import com.example.bruschedule.domain.model.ScheduleProfileType
import com.example.bruschedule.domain.usecase.ScheduleCycleExpander
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BsuScheduleHtmlParserTest {
    private val parser = BsuScheduleHtmlParser()

    @Test
    fun teacherProfileSourceKeyKeepsFacultyAndReadsLegacyKeys() {
        val urls = listOf("https://fpmi.bsu.by/schedule-one", "https://fpmi.bsu.by/schedule-two")
        val encoded = BsuTeacherProfileSourceKey.encode("Иванова И.И.", urls, facultyName = "ФПМИ")

        assertEquals("Иванова И.И.", BsuTeacherProfileSourceKey.decode(encoded)?.teacherName)
        assertEquals(urls, BsuTeacherProfileSourceKey.decode(encoded)?.urls)
        assertEquals("ФПМИ", BsuTeacherProfileSourceKey.decode(encoded)?.facultyName)
        assertEquals(
            null,
            BsuTeacherProfileSourceKey.decode(BsuTeacherProfileSourceKey.encode("Иванова И.И.", urls))?.facultyName,
        )
    }

    @Test
    fun matchesFacultyTeacherNameVariantsWithAcademicTitles() {
        assertTrue(BsuTeacherNameMatcher.matches("ст. преп. Иванова Н.В.", "Иванова Наталья Владимировна"))
        assertTrue(BsuTeacherNameMatcher.matches("Петров П.П.", "Петров Павел Петрович"))
        assertFalse(BsuTeacherNameMatcher.matches("Иванова Н.В.", "Иванова Наталья Сергеевна"))
    }

    @Test
    fun discoversGroupLabelsFromTableHeadersAndSplitSubgroupColumns() {
        val html = """
            <table>
              <tr><th>День</th><th>Дата</th><th>Время</th><th>1-11</th></tr>
            </table>
            <table>
              <tr><th>Время</th><th>1 группа</th><th>А</th><th>Б</th></tr>
            </table>
            <a href="/group/2-12">Группа 2-12</a>
        """.trimIndent()

        assertEquals(
            listOf("1-11", "1 группа", "1 группа А", "1 группа Б"),
            parser.discoverTableGroupLabels(html),
        )
        assertEquals(
            listOf("1-11", "1 группа", "1 группа А", "1 группа Б", "2-12"),
            parser.discoverGroupLabels(html),
        )
    }

    @Test
    fun discoversNumericGroupIdsFromMultiStreamTimetableHeaders() {
        val html = """
            <table>
              <tr><th>Дни</th><th>Время</th><th>Дата</th><th>№ группы</th><th>Название дисциплины</th></tr>
              <tr><td>Понедельник</td><td>8.00-9.20</td><td>2026-10-26</td><td>1</td><td>Математика</td></tr>
              <tr><td></td><td>9.35-10.55</td><td></td><td>2</td><td>Физика</td></tr>
            </table>
        """.trimIndent()

        assertEquals(listOf("1 группа", "2 группа"), parser.discoverTableGroupLabels(html))
    }

    @Test
    fun parsesLegacyXlsStyleNumberedGroupColumnsAndDottedTimes() {
        val profile = ScheduleProfile(
            id = "bsu-law-1",
            type = ScheduleProfileType.GROUP,
            name = "1 группа",
            sourceUrl = "https://law.bsu.by/pub/2/Raspisanie_1_pravo.xls",
            sourceKey = "1 группа",
            universityId = "bsu",
        )
        val html = """
            <html><body><table>
              <tr><th>Дни</th><th>Время</th><th>Дата</th><th>№ группы</th><th>Название дисциплины</th><th>Преподаватель</th><th>Аудитория</th></tr>
              <tr><td>Понедельник</td><td>8.00-9.20</td><td>2026-10-26</td><td>1</td><td>Теория государства и права</td><td>Иванова И.И.</td><td>115</td></tr>
              <tr><td></td><td>9.35-10.55</td><td></td><td>2</td><td>История государства</td><td>Петров П.П.</td><td>216</td></tr>
            </table></body></html>
        """.trimIndent()

        val lessons = parser.parse(profile, html, LocalDate(2026, 10, 26))

        assertEquals(1, lessons.size)
        assertEquals("Теория государства и права", lessons.single().subjectName)
        assertEquals("Иванова И.И.", lessons.single().teacherName)
        assertEquals("115", lessons.single().classroom)
        assertEquals("08:00", lessons.single().startTime)
        assertEquals("09:20", lessons.single().endTime)
        assertEquals("2026-10-26", lessons.single().lessonDate)
    }

    @Test
    fun filtersGoogleSheetMatrixToTheSelectedGroupAndCarriesMergedDayDateAndMetadataRows() {
        val profile = ScheduleProfile(
            id = "bsu-econ-1-11",
            type = ScheduleProfileType.GROUP,
            name = "1-11",
            sourceUrl = "https://docs.google.com/spreadsheets/d/example-sheet/edit?gid=0",
            sourceKey = "1-11",
            universityId = "bsu",
        )
        val html = """
            <html><body>
              <table class="google-visualization-table-table">
                <thead><tr><th>День</th><th>Дата</th><th>Время</th><th>1-11</th><th></th><th>1-12</th><th></th></tr></thead>
                <tbody>
                  <tr><td>Понедельник</td><td>19.10.2026</td><td>08.15–09.40</td><td>Математический анализ</td><td>515</td><td>Физика</td><td>521</td></tr>
                  <tr><td></td><td></td><td></td><td>Преподаватель: Иванова Н.В.</td><td></td><td>Петров П.П.</td><td></td></tr>
                  <tr><td></td><td></td><td></td><td></td><td>515</td><td></td><td>521</td></tr>
                  <tr><td></td><td></td><td></td><td>Лекция</td><td></td><td>Лабораторная</td><td></td></tr>
                  <tr><td>Вторник</td><td>20.10.2026</td><td>09.50-<br>11.15</td><td>История Беларуси</td><td>527</td><td>Химия</td><td>532</td></tr>
                </tbody>
              </table>
            </body></html>
        """.trimIndent()

        val lessons = parser.parse(profile, html, LocalDate(2026, 10, 19))

        assertEquals(2, lessons.size)
        assertEquals(listOf("Математический анализ", "История Беларуси"), lessons.map { it.subjectName })
        assertEquals(listOf("2026-10-19", "2026-10-20"), lessons.map { it.lessonDate })
        assertEquals("Иванова Н.В.", lessons.first().teacherName)
        assertEquals("515", lessons.first().classroom)
        assertEquals("08:15", lessons.first().startTime)
        assertEquals("09:40", lessons.first().endTime)
        assertEquals("09:50", lessons.last().startTime)
        assertEquals("527", lessons.last().classroom)
        assertTrue(parser.hasWeeklyScheduleTemplate(profile, html))
    }

    @Test
    fun parsesEconomyGoogleSheetStyleHeadersRoomsAbbreviationsAndInlineTeachers() {
        val profile = ScheduleProfile(
            id = "bsu-econ-1-11-real-layout",
            type = ScheduleProfileType.GROUP,
            name = "1-11",
            sourceUrl = "https://docs.google.com/spreadsheets/d/example/edit",
            sourceKey = "1-11",
            universityId = "bsu",
        )
        val html = """
            <html><head><title>ЧЕТНАЯ</title></head><body>
              <h2>ЧЕТНАЯ</h2>
              <table>
                <tr>
                  <th>1 курс (бел) День</th><th>Дата</th><th>Время</th>
                  <th>Специальность 6-05-0412-01 Менеджмент, группа 1-11</th><th></th>
                  <th>Специальность 6-05-0412-01 Менеджмент, группа 1-12</th><th></th>
                </tr>
                <tr>
                  <td>ПОНЕДЕЛЬНИК</td><td>С 19.10.2026</td><td>09.45-<br>11.05</td>
                  <td>ЛК Экономическая теория<br>Зарожная Е.С.</td><td>404</td>
                  <td>ПЗ Физика<br>Петров П.П.</td><td>520</td>
                </tr>
                <tr>
                  <td></td><td></td><td>11.25-12.45</td>
                  <td>ПЗ Тайм-менеджмент Рассеко Ю.Ю.</td><td>316</td>
                  <td>ЛК Химия</td><td>521</td>
                </tr>
              </table>
            </body></html>
        """.trimIndent()

        val lessons = parser.parse(profile, html, LocalDate(2026, 10, 19))

        assertEquals(2, lessons.size)
        assertEquals("Экономическая теория", lessons[0].subjectName)
        assertEquals("Зарожная Е.С.", lessons[0].teacherName)
        assertEquals("404", lessons[0].classroom)
        assertEquals("LECTURE", lessons[0].type.name)
        assertEquals("Тайм-менеджмент", lessons[1].subjectName)
        assertEquals("Рассеко Ю.Ю.", lessons[1].teacherName)
        assertEquals("316", lessons[1].classroom)
        assertEquals("PRACTICE", lessons[1].type.name)
        assertTrue(parser.hasAlternatingTwoWeekCycle(html))
        assertFalse(parser.hasWeeklyScheduleTemplate(profile, html))
    }

    @Test
    fun separatesOnlyDateAndExceptDateRowsBeforeAlternatingExpansion() {
        val profile = ScheduleProfile(
            id = "bsu-econ-1-11-exceptions",
            type = ScheduleProfileType.GROUP,
            name = "1-11",
            sourceUrl = "https://docs.google.com/spreadsheets/d/example/edit",
            sourceKey = "1-11",
            universityId = "bsu",
        )
        val html = """
            <html><body><h2>ЧЕТНАЯ</h2>
              <table>
                <tr><th>1 курс День</th><th>Дата</th><th>Время</th><th>Группа 1-11</th><th></th><th>Группа 1-12</th><th></th></tr>
                <tr>
                  <td>Понедельник</td><td>19.10.2026</td><td>11.25-12.45</td>
                  <td>КРОМЕ 19.10. ЛК Компьютерная графика<br>Макаревич В.А. /<br>ПЗ Психология управления Василевич Т.В. ТОЛЬКО 19.10.</td>
                  <td>79</td><td>Математика</td><td>80</td>
                </tr>
              </table>
            </body></html>
        """.trimIndent()

        val parsed = parser.parse(profile, html, LocalDate(2026, 10, 19))
        val expanded = ScheduleCycleExpander.expand(parsed, LocalDate(2026, 10, 19), LocalDate(2026, 11, 16))

        assertEquals(2, parsed.size)
        assertEquals("Компьютерная графика", parsed.first { it.type.name == "LECTURE" }.subjectName)
        assertEquals("Макаревич В.А.", parsed.first { it.type.name == "LECTURE" }.teacherName)
        assertEquals("Психология управления", parsed.first { it.type.name == "PRACTICE" }.subjectName)
        assertEquals("Василевич Т.В.", parsed.first { it.type.name == "PRACTICE" }.teacherName)
        assertEquals(
            listOf("2026-11-02", "2026-11-16"),
            expanded.filter { it.type.name == "LECTURE" }.map { it.lessonDate },
        )
        assertEquals(listOf("2026-10-19"), expanded.filter { it.type.name == "PRACTICE" }.map { it.lessonDate })
    }

    @Test
    fun routesMmfHtmlTableThroughTheSharedVerticalTableAdapter() {
        val profile = ScheduleProfile(
            id = "bsu-mmf-1",
            type = ScheduleProfileType.GROUP,
            name = "1 группа",
            sourceUrl = "https://mmf.bsu.by/ru/raspisanie-zanyatij/dnevnoe-otdelenie/1-kurs/1-gruppa/",
            sourceKey = "1 группа",
            universityId = "bsu",
        )
        val html = """
            <table>
              <tr><th>День</th><th>Время</th><th>Группа</th><th>Предмет, преподаватель</th><th>Тип</th><th>Аудитория</th></tr>
              <tr><td>Понедельник</td><td>09.45–11.05</td><td>1 группа</td><td>Теория вероятностей<br>доц. Иванова Н.В.</td><td>ЛК</td><td>530</td></tr>
            </table>
        """.trimIndent()

        val lesson = parser.parse(profile, html, LocalDate(2026, 10, 19)).single()

        assertEquals("Теория вероятностей", lesson.subjectName)
        assertEquals("Иванова Н.В.", lesson.teacherName)
        assertEquals("530", lesson.classroom)
        assertEquals("LECTURE", lesson.type.name)
        assertEquals("09:45", lesson.startTime)
    }

    @Test
    fun selectsSubgroupWhenItsLabelIsSplitAcrossAdjacentHeaderCells() {
        val profile = ScheduleProfile(
            id = "bsu-fpmi-1b",
            type = ScheduleProfileType.GROUP,
            name = "1 группа Б",
            sourceUrl = "https://docs.google.com/spreadsheets/d/example-sheet/edit?gid=123",
            sourceKey = "1 группа Б",
            universityId = "bsu",
        )
        val html = """
            <table>
              <tr><th>День</th><th>Время</th><th>1 группа</th><th>А</th><th>Б</th></tr>
              <tr><td>Понедельник</td><td>09.00–10.20</td><td></td><td>Математика А</td><td>Физика Б</td></tr>
            </table>
        """.trimIndent()

        val lesson = parser.parse(profile, html, LocalDate(2026, 10, 19)).single()

        assertEquals("Физика Б", lesson.subjectName)
        assertEquals("09:00", lesson.startTime)
        assertEquals("2026-10-19", lesson.lessonDate)
    }

    @Test
    fun parsesOnlyTheSelectedTeacherFromAFacultyTimetable() {
        val profile = ScheduleProfile(
            id = "bsu-teacher-ivanova",
            type = ScheduleProfileType.TEACHER,
            name = "Иванова И.И.",
            sourceUrl = "https://mmf.bsu.by/ru/raspisanie-zanyatij/",
            sourceKey = BsuTeacherProfileSourceKey.encode(
                "Иванова И.И.",
                listOf("https://mmf.bsu.by/ru/raspisanie-zanyatij/"),
                facultyName = "Механико-математический факультет",
            ),
            universityId = "bsu",
        )
        val html = """
            <table>
              <tr><th>День</th><th>Дата</th><th>Время</th><th>Группа</th><th>Предмет</th><th>Преподаватель</th><th>Аудитория</th></tr>
              <tr><td>Понедельник</td><td>19.10.2026</td><td>09:00-10:25</td><td>1 группа</td><td>Математика</td><td>Иванова И.И.</td><td>101</td></tr>
              <tr><td>Понедельник</td><td>19.10.2026</td><td>10:35-12:00</td><td>2 группа</td><td>Физика</td><td>Петров П.П.</td><td>202</td></tr>
            </table>
        """.trimIndent()

        val lessons = parser.parse(profile, html, LocalDate(2026, 10, 19))

        assertEquals(1, lessons.size)
        assertEquals("Математика", lessons.single().subjectName)
        assertEquals("Иванова И.И.", lessons.single().teacherName)
        assertEquals("101", lessons.single().classroom)
    }

    @Test
    fun discoversAndParsesTeacherFromTheSecondFpmisSubgroupColumn() {
        val profile = ScheduleProfile(
            id = "bsu-teacher-fpmi-subgroup",
            type = ScheduleProfileType.TEACHER,
            name = "Иванова И.И.",
            sourceUrl = "https://konstantin-chvei.github.io/Schedule/schedule-data/universities/bsu/schedules/teachers/ivanova-ii.html",
            sourceKey = "ФПМИ",
            universityId = "bsu",
        )
        val html = """
            <table>
              <tr>
                <th>1 курс день недели понедельник</th><th>время занятий</th>
                <th>Выписка 1 группа А</th><th>Б</th><th>2 группа А</th><th>Б</th>
              </tr>
              <tr>
                <td></td><td>08.15–09.40</td>
                <td>Математический анализ<br>Петров П.П.</td>
                <td>Физика<br>Иванова И.И.</td>
                <td>История<br>Сидоров С.С.</td><td></td>
              </tr>
            </table>
        """.trimIndent()

        assertTrue(parser.discoverTableGroupLabels(html).containsAll(listOf("1 группа А", "1 группа Б")))
        val lessons = parser.parse(profile, html, LocalDate(2026, 10, 19))

        assertEquals(1, lessons.size)
        assertEquals("Физика", lessons.single().subjectName)
        assertEquals("Иванова И.И.", lessons.single().teacherName)
        assertEquals("2026-10-19", lessons.single().lessonDate)
    }

    @Test
    fun parsesTeacherLessonsFromEveryGroupColumnInAFacultyGoogleSheet() {
        val sheetUrl = "https://docs.google.com/spreadsheets/d/teacher-sheet/edit?gid=1"
        val profile = ScheduleProfile(
            id = "bsu-teacher-petrov",
            type = ScheduleProfileType.TEACHER,
            name = "Петров П.П.",
            sourceUrl = sheetUrl,
            sourceKey = BsuTeacherProfileSourceKey.encode(
                "Петров П.П.",
                listOf(sheetUrl),
                facultyName = "Экономический факультет",
            ),
            universityId = "bsu",
        )
        val html = """
            <table>
              <tr><th>День</th><th>Дата</th><th>Время</th><th>1-11</th><th>1-12</th></tr>
              <tr><td>Понедельник</td><td>19.10.2026</td><td>08:15-09:40</td>
                <td>Математика<br>Иванова И.И.</td><td>Физика<br>Петров П.П.</td></tr>
            </table>
        """.trimIndent()

        val lessons = parser.parse(profile, html, LocalDate(2026, 10, 19))

        assertEquals(1, lessons.size)
        assertEquals("Физика", lessons.single().subjectName)
        assertEquals("Петров П.П.", lessons.single().teacherName)
    }

    @Test
    fun parsesTeacherLessonsFromRepeatedGroupTablesInEveryFacultySheet() {
        val profile = ScheduleProfile(
            id = "bsu-teacher-multi-sheet",
            type = ScheduleProfileType.TEACHER,
            name = "Петров П.П.",
            sourceUrl = "https://docs.google.com/spreadsheets/d/example/edit",
            sourceKey = BsuTeacherProfileSourceKey.encode(
                "Петров П.П.",
                listOf(
                    "https://docs.google.com/spreadsheets/d/example-odd/edit",
                    "https://docs.google.com/spreadsheets/d/example-even/edit",
                ),
                facultyName = "Экономический факультет",
            ),
            universityId = "bsu",
        )
        val html = """
            <section data-bsu-teacher-source="true" data-bsu-teacher-title="НЕЧЕТНАЯ">
              <h2 class="bsu-teacher-source-title">НЕЧЕТНАЯ</h2>
              <table>
                <tr><th>День</th><th>Дата</th><th>Время</th><th>1-11</th></tr>
                <tr><td>ПОНЕДЕЛЬНИК</td><td>С 26.10.2026</td><td>08:15-09:40</td><td>История<br>Петров П.П.</td></tr>
              </table>
            </section>
            <section data-bsu-teacher-source="true" data-bsu-teacher-title="ЧЕТНАЯ">
              <h2 class="bsu-teacher-source-title">ЧЕТНАЯ</h2>
              <table>
                <tr><th>День</th><th>Дата</th><th>Время</th><th>1-11</th></tr>
                <tr><td>ПОНЕДЕЛЬНИК</td><td>С 19.10.2026</td><td>09:50-11:15</td><td>Математика<br>Петров П.П.</td></tr>
              </table>
            </section>
        """.trimIndent()

        val lessons = parser.parse(profile, html, LocalDate(2026, 10, 19))

        assertEquals(2, lessons.size)
        assertEquals(listOf("Математика", "История"), lessons.map { it.subjectName })
        assertEquals(listOf("2026-10-19", "2026-10-26"), lessons.map { it.lessonDate })
        assertEquals(2, lessons.map { it.sourceKey }.distinct().size)
        assertTrue(parser.hasAlternatingTwoWeekCycle(html))
        val occurrences = ScheduleCycleExpander.expand(
            lessons,
            fromDate = LocalDate(2026, 10, 23),
            throughDate = LocalDate(2026, 11, 8),
        )
        assertEquals(listOf("2026-10-26", "2026-11-02"), occurrences.map { it.lessonDate })
    }

    @Test
    fun parsesFscPdfWeekdayRowsWithSeparateSubgroupAndRoomColumns() {
        val sheetUrl = "https://fsc.bsu.by/media/schedules/class/2026/10/17/inform_1_1.pdf"
        val profile = ScheduleProfile(
            id = "bsu-teacher-fsc-kvetko",
            type = ScheduleProfileType.TEACHER,
            name = "Кветко О.М.",
            sourceUrl = sheetUrl,
            sourceKey = BsuTeacherProfileSourceKey.encode(
                "Кветко О.М.",
                listOf(sheetUrl),
                facultyName = "ФСК",
            ),
            universityId = "bsu",
        )
        val html = """
            <h2>Курс 1 19.10.2026-24.10.2026</h2>
            <table>
              <tr><td></td><td>Время</td><td>1 подгруппа (2620)</td><td>ауд</td><td>2 подгруппа (2620)</td><td>ауд</td></tr>
              <tr><td>Понедельник</td><td>08.30-09.55</td><td>Алгебра и теория чисел (лекция) Дубровина О.В.</td><td>801</td><td></td><td></td></tr>
              <tr><td>Вторник</td><td>11.50-13.15 13.25-14.50</td><td>Математический анализ (лекция) Кветко О.М. Кураторский/информационный час Кветко О.М.</td><td>803</td><td>Физическая культура (пр)</td><td>803</td></tr>
            </table>
        """.trimIndent()

        val lessons = parser.parse(profile, html, LocalDate(2026, 10, 19))

        assertEquals(2, lessons.size)
        assertEquals(listOf("Математический анализ (лекция)", "Кураторский/информационный час"), lessons.map { it.subjectName })
        assertEquals(listOf("11:50", "13:25"), lessons.map { it.startTime })
        assertEquals(listOf("803", "803"), lessons.map { it.classroom })
        assertTrue(lessons.all { it.teacherName == "Кветко О.М." })
    }

    @Test
    fun treatsAnExplicitOddOrEvenWorksheetAsAnAlternatingTwoWeekTemplate() {
        val html = """
            <html><head><title>НЕЧЕТНАЯ</title></head><body>
              <table>
                <tr><th>День</th><th>Дата</th><th>Время</th><th>Группа 1-11</th></tr>
                <tr><td>Понедельник</td><td>С 19.10.2026</td><td>08.15–09.40</td><td>Математика</td></tr>
              </table>
            </body></html>
        """.trimIndent()
        val profile = ScheduleProfile(
            id = "bsu-group-parity",
            type = ScheduleProfileType.GROUP,
            name = "1-11",
            sourceUrl = "https://docs.google.com/spreadsheets/d/economy-odd/edit",
            sourceKey = "1-11",
            universityId = "bsu",
        )

        assertTrue(parser.hasAlternatingTwoWeekCycle(html))
        assertFalse(parser.hasWeeklyScheduleTemplate(profile, html))
        val lessons = parser.parse(profile, html, LocalDate(2026, 10, 19))
        assertEquals(1, lessons.size)
        assertEquals("2026-10-19", lessons.single().lessonDate)
    }
}
