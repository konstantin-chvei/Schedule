package com.example.bruschedule

import com.example.bruschedule.data.scraper.GenericHtmlScheduleParser
import com.example.bruschedule.domain.model.ScheduleProfile
import com.example.bruschedule.domain.model.ScheduleProfileType
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GenericHtmlScheduleParserTest {
    private val parser = GenericHtmlScheduleParser()
    private val profile = ScheduleProfile(
        id = "bsu-is-241",
        type = ScheduleProfileType.GROUP,
        name = "ИС-241",
        universityId = "bsu",
    )

    @Test
    fun parsesTimetableWithDaysAsColumnsAndExtractsTimeTeacherAndRoom() {
        val html = """
            <table>
              <thead><tr><th>Время</th><th>Понедельник 19.10.2026</th><th>Вторник 20.10.2026</th></tr></thead>
              <tbody>
                <tr>
                  <td>1 пара 08:30–10:05</td>
                  <td>Математический анализ<br>Преподаватель: Иванова Н.В.<br>Ауд. 530<br>Лекция</td>
                  <td></td>
                </tr>
              </tbody>
            </table>
        """.trimIndent()

        val lesson = parser.parse(profile, html, LocalDate(2026, 10, 19)).single()

        assertEquals("2026-10-19", lesson.lessonDate)
        assertEquals(1, lesson.dayOfWeek)
        assertEquals("Математический анализ", lesson.subjectName)
        assertEquals("Иванова Н.В.", lesson.teacherName)
        assertEquals("530", lesson.classroom)
        assertEquals("08:30", lesson.startTime)
        assertEquals("10:05", lesson.endTime)
        assertEquals(1, lesson.slotNumber)
    }

    @Test
    fun parsesVerticalTableWithDayTimeAndSubjectRows() {
        val html = """
            <table>
              <thead><tr><th>День</th><th>Время</th><th>Дисциплина</th></tr></thead>
              <tbody>
                <tr>
                  <td>Понедельник, 19.10.2026</td>
                  <td>09:40-11:15</td>
                  <td>Физика<br>Преподаватель: Петров П.П.<br>Кабинет 12<br>Практика</td>
                </tr>
              </tbody>
            </table>
        """.trimIndent()

        val lesson = parser.parse(profile, html, LocalDate(2026, 10, 19)).single()

        assertEquals("Физика", lesson.subjectName)
        assertEquals("2026-10-19", lesson.lessonDate)
        assertEquals("Петров П.П.", lesson.teacherName)
        assertEquals("12", lesson.classroom)
        assertEquals("09:40", lesson.startTime)
        assertEquals("11:15", lesson.endTime)
    }

    @Test
    fun parsesMmfStyleTableWithSeparateGroupTeacherTypeRoomAndInheritedDateCells() {
        val html = """
            <table>
              <thead>
                <tr>
                  <th>День</th><th>Дата</th><th>Время</th><th>Группа</th>
                  <th>Предмет, преподаватель</th><th>Тип</th><th>Аудитория</th>
                </tr>
              </thead>
              <tbody>
                <tr>
                  <td>Понедельник</td><td>19.10.2026</td><td>09.45–11.05</td><td>1 группа</td>
                  <td>Теория вероятностей<br>доц. Иванова Н.В.</td><td>Лекция</td><td>Ауд. 530</td>
                </tr>
                <tr>
                  <td></td><td></td><td>11.25–12.45</td><td>1 группа</td>
                  <td>Линейная алгебра</td><td>Практика</td><td>531</td>
                </tr>
              </tbody>
            </table>
        """.trimIndent()

        val lessons = parser.parse(profile, html, LocalDate(2026, 10, 19))

        assertEquals(2, lessons.size)
        assertEquals("Теория вероятностей", lessons[0].subjectName)
        assertEquals("Иванова Н.В.", lessons[0].teacherName)
        assertEquals("530", lessons[0].classroom)
        assertEquals("09:45", lessons[0].startTime)
        assertEquals("11:05", lessons[0].endTime)
        assertEquals("2026-10-19", lessons[1].lessonDate)
        assertEquals("531", lessons[1].classroom)
    }

    @Test
    fun discoversTeacherNamesFromFacultyTeacherColumnsAndExplicitTeacherMarkup() {
        val html = """
            <table>
              <tr><th>День</th><th>Время</th><th>Предмет</th><th>Преподаватель</th></tr>
              <tr><td>Понедельник</td><td>08:30-10:05</td><td>Математика</td><td>Иванова Н.В.</td></tr>
            </table>
            <div class="lesson-teacher"><a class="teacher-link">Микитченко Надежда Владимировна</a></div>
            <div>Преподаватель: Петров П.П.</div>
        """.trimIndent()

        assertEquals(
            setOf("Иванова Н.В.", "Микитченко Надежда Владимировна", "Петров П.П."),
            parser.discoverTeacherNames(html).toSet(),
        )
    }

    @Test
    fun discoversMultipleTeachersFromOneInlineMatrixCell() {
        val html = """
            <table>
              <tr><th>День</th><th>Время</th><th>1-11</th></tr>
              <tr><td>Понедельник</td><td>08:15-09:40</td>
                <td>Английский язык<br>Ковальчук А.М. Кутыркина Т.П.</td></tr>
            </table>
        """.trimIndent()

        assertEquals(
            setOf("Ковальчук А.М.", "Кутыркина Т.П."),
            parser.discoverTeacherNames(html).toSet(),
        )
    }

    @Test
    fun recognizesWeeklyTemplatesOnlyForNonBruSources() {
        val html = """
            <table>
              <thead><tr><th>Время</th><th>Понедельник</th><th>Вторник</th></tr></thead>
              <tbody><tr><td>08:30-10:05</td><td>Математика</td><td>Физика</td></tr></tbody>
            </table>
        """.trimIndent()

        assertEquals(true, parser.hasWeeklyScheduleTemplate(profile, html))
        assertEquals(false, parser.hasWeeklyScheduleTemplate(profile.copy(universityId = "bru"), html))
        val oddWeekHtml = """
            <h2>Нечетная неделя</h2>
            <table><tr><th>День</th><th>Время</th><th>Предмет</th></tr>
            <tr><td>Понедельник, 26.10.2026</td><td>08:15-09:40</td><td>Математика</td></tr></table>
        """.trimIndent()
        assertEquals(false, parser.hasWeeklyScheduleTemplate(profile, oddWeekHtml))
    }

    @Test
    fun rejectsHtmlWithoutRecognizableTimetableInsteadOfReturningAnEmptySchedule() {
        assertFailsWith<IllegalArgumentException> {
            parser.parse(profile, "<html><body>Занятий нет</body></html>", LocalDate(2026, 10, 19))
        }
    }
}
