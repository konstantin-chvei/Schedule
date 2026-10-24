package com.example.bruschedule

import com.example.bruschedule.data.scraper.BruScheduleScraper
import com.example.bruschedule.domain.model.Lesson
import com.example.bruschedule.domain.model.LessonType
import com.example.bruschedule.domain.usecase.ScheduleCycleExpander
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.datetime.DatePeriod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScheduleCycleExpanderTest {
    @Test
    fun onlyExplicitRegularWeekOneAndWeekTwoHeadersEnableCycleExpansion() {
        val scraper = BruScheduleScraper()
        val regularPage = """
            <section>
              <h3>Неделя 1: 19.10 – 25.10.2026</h3>
              <h3>Неделя 2: 26.10 – 01.11.2026</h3>
            </section>
        """.trimIndent()
        val examSessionPage = """
            <div class="week-header">Зимняя экзаменационная сессия</div>
            <div class="week-header">Консультации и экзамены</div>
        """.trimIndent()

        assertTrue(scraper.hasAlternatingTwoWeekCycle(regularPage))
        assertFalse(scraper.hasAlternatingTwoWeekCycle(examSessionPage))
        assertFalse(scraper.hasAlternatingTwoWeekCycle("<div class=week-header>Неделя 1</div>"))
    }

    @Test
    fun repeatsOnlyFromTodayThroughTheConfiguredSemesterEnd() {
        val start = LocalDate(2026, 10, 19)
        val semesterEndPlusWeek = LocalDate(2027, 1, 17)
        val template = listOf(
            lesson("2026-10-19", "group-mpr-261|2026-10-19|1|08:30-10:05|0"),
            lesson("2026-10-26", "group-mpr-261|2026-10-26|1|08:30-10:05|0"),
        )

        val expanded = ScheduleCycleExpander.expand(template, start, semesterEndPlusWeek)
        val repeated = ScheduleCycleExpander.expand(template, start, semesterEndPlusWeek)

        assertEquals(13, expanded.size)
        assertEquals(expanded.map { it.sourceKey }, repeated.map { it.sourceKey })
        assertEquals(expanded.size, expanded.map { it.id }.distinct().size)
        assertEquals(start.toString(), expanded.first().lessonDate)
        assertEquals("2027-01-11", expanded.last().lessonDate)
        assertTrue(expanded.all { LocalDate.parse(it.lessonDate) in start..semesterEndPlusWeek })
        assertTrue(expanded.all { it.sourceKey.contains("|${it.lessonDate}|1|08:30-10:05|0") })
        assertTrue(expanded.all { !it.isCustom })
    }

    @Test
    fun expandsSingleWeekTemplatesWeeklyThroughTheConfiguredSemesterEnd() {
        val from = LocalDate(2026, 10, 19)
        val through = LocalDate(2026, 11, 2)
        val template = listOf(lesson("2026-10-12", "group-mpr-261|2026-10-12|1|08:30-10:05|0"))

        val expanded = ScheduleCycleExpander.expandWeekly(template, from, through)

        assertEquals(listOf("2026-10-19", "2026-10-26", "2026-11-02"), expanded.map { it.lessonDate })
        assertEquals(expanded.size, expanded.map { it.sourceKey }.distinct().size)
        assertTrue(expanded.all { it.sourceKey.contains("|${it.lessonDate}|1|08:30-10:05|0") })
    }

    @Test
    fun keepsOnlyDateLessonsOnceAndSkipsExceptDatesInWeeklyTemplates() {
        val from = LocalDate(2026, 10, 19)
        val through = LocalDate(2026, 11, 2)
        val repeating = lesson(
            "2026-10-19",
            "group-mpr-261|2026-10-19|1|08:30-10:05|0|except:2026-10-26",
        )
        val oneOff = lesson(
            "2026-10-26",
            "group-mpr-261|2026-10-26|1|08:30-10:05|0|once",
        ).copy(subjectName = "Разовая пара")

        val expanded = ScheduleCycleExpander.expandWeekly(listOf(repeating, oneOff), from, through)

        assertEquals(
            listOf("2026-10-19", "2026-11-02"),
            expanded.filter { it.subjectName == repeating.subjectName }.map { it.lessonDate },
        )
        assertEquals(
            listOf("2026-10-26"),
            expanded.filter { it.subjectName == "Разовая пара" }.map { it.lessonDate },
        )
    }

    @Test
    fun datesBeforeTheCurrentDayAreSkippedWhenExpandingTheCurrentCycle() {
        val today = LocalDate(2026, 10, 22)
        val template = listOf(
            lesson("2026-10-19", "group-mpr-261|2026-10-19|1|08:30-10:05|0"),
            lesson("2026-10-26", "group-mpr-261|2026-10-26|1|08:30-10:05|0"),
        )

        val expanded = ScheduleCycleExpander.expand(
            template,
            today,
            today.plus(DatePeriod(days = 14)),
        )

        assertTrue(expanded.isNotEmpty())
        assertTrue(expanded.all { LocalDate.parse(it.lessonDate) >= today })
        assertEquals("2026-10-26", expanded.first().lessonDate)
    }

    @Test
    fun keepsOnlyDateLessonsOnceAndSkipsAnExplicitExceptDateInAnAlternatingCycle() {
        val from = LocalDate(2026, 10, 19)
        val through = LocalDate(2026, 11, 16)
        val repeating = lesson(
            "2026-10-19",
            "group-mpr-261|2026-10-19|1|08:30-10:05|0|except:2026-10-19",
        )
        val oneOff = lesson(
            "2026-10-19",
            "group-mpr-261|2026-10-19|1|08:30-10:05|0|once",
        ).copy(subjectName = "Только одна дата")

        val expanded = ScheduleCycleExpander.expand(listOf(repeating, oneOff), from, through)

        assertEquals(
            listOf("2026-11-02", "2026-11-16"),
            expanded.filter { it.subjectName == repeating.subjectName }.map { it.lessonDate },
        )
        assertEquals(
            listOf("2026-10-19"),
            expanded.filter { it.subjectName == "Только одна дата" }.map { it.lessonDate },
        )
    }

    @Test
    fun customLessonsAreNeverRepeatedWithTheWebsiteCycle() {
        val start = LocalDate(2026, 10, 19)
        val custom = lesson("2026-10-19", "manual:one-off").copy(isCustom = true)

        val expanded = ScheduleCycleExpander.expand(
            listOf(custom),
            start,
            LocalDate(2027, 1, 17),
        )

        assertEquals(listOf(custom), expanded)
    }

    private fun lesson(date: String, sourceKey: String) = Lesson(
        id = "web:$sourceKey",
        profileId = "group-mpr-261",
        subjectName = "Информатика",
        teacherName = "Преподаватель",
        classroom = "ауд. 530",
        startTime = "08:30",
        endTime = "10:05",
        type = LessonType.LECTURE,
        colorHex = "#A7B9FF",
        isCustom = false,
        version = 1,
        lessonDate = date,
        dayOfWeek = 1,
        slotNumber = 1,
        sourceKey = sourceKey,
    )
}
