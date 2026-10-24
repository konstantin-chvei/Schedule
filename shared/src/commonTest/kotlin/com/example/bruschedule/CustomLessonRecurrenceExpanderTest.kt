package com.example.bruschedule

import com.example.bruschedule.domain.model.Lesson
import com.example.bruschedule.domain.model.LessonRecurrence
import com.example.bruschedule.domain.model.LessonType
import com.example.bruschedule.domain.usecase.CustomLessonRecurrenceExpander
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CustomLessonRecurrenceExpanderTest {
    @Test
    fun weeklyAndBiweeklyRepeatsUseTheAnchorWeekday() {
        val template = lesson("2026-10-19")
        val endDate = LocalDate.parse("2026-11-16")

        val weekly = CustomLessonRecurrenceExpander.expand(template, LessonRecurrence.WEEKLY, endDate)
        val biweekly = CustomLessonRecurrenceExpander.expand(template, LessonRecurrence.BIWEEKLY, endDate)

        assertEquals(
            listOf("2026-10-19", "2026-10-26", "2026-11-02", "2026-11-09", "2026-11-16"),
            weekly.map { it.lessonDate },
        )
        assertEquals(listOf("2026-10-19", "2026-11-02", "2026-11-16"), biweekly.map { it.lessonDate })
        assertTrue(weekly.all { it.dayOfWeek == template.dayOfWeek })
    }

    @Test
    fun noRepeatKeepsOnlyTheSelectedDate() {
        val template = lesson("2026-10-19")

        val occurrences = CustomLessonRecurrenceExpander.expand(
            template,
            LessonRecurrence.NONE,
            LocalDate.parse("2027-02-01"),
        )

        assertEquals(listOf("2026-10-19"), occurrences.map { it.lessonDate })
    }

    @Test
    fun monthlyRepeatKeepsAnchorDayAndClampsAtLeapFebruary() {
        val template = lesson("2024-01-31")

        val monthly = CustomLessonRecurrenceExpander.expand(
            template,
            LessonRecurrence.MONTHLY,
            LocalDate.parse("2024-03-31"),
        )

        assertEquals(listOf("2024-01-31", "2024-02-29", "2024-03-31"), monthly.map { it.lessonDate })
    }

    @Test
    fun occurrencesHaveDistinctStableIdsAndCopyTheOptionalNote() {
        val template = lesson("2026-10-19").copy(notes = "Взять линейку")

        val occurrences = CustomLessonRecurrenceExpander.expand(
            template,
            LessonRecurrence.WEEKLY,
            LocalDate.parse("2026-11-02"),
        )

        assertEquals(3, occurrences.map { it.id }.distinct().size)
        assertEquals(template.id, occurrences.first().id)
        assertTrue(occurrences.all { it.notes == "Взять линейку" })
        assertTrue(occurrences.all { it.isCustom })
    }

    private fun lesson(date: String) = Lesson(
        id = "custom-lesson-series-1",
        profileId = "profile-1",
        subjectName = "Моя пара",
        teacherName = "",
        classroom = "",
        startTime = "08:30",
        endTime = "10:05",
        type = LessonType.PRACTICE,
        colorHex = "#FFFFFF",
        isCustom = true,
        version = 0,
        lessonDate = date,
        dayOfWeek = LocalDate.parse(date).dayOfWeek.isoDayNumber,
        slotNumber = 1,
        sourceKey = "custom:custom-lesson-series-1",
    )
}
