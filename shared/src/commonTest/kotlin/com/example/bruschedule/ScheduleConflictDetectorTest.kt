package com.example.bruschedule

import com.example.bruschedule.domain.model.AcademicTask
import com.example.bruschedule.domain.model.DeadlineLinkType
import com.example.bruschedule.domain.model.Lesson
import com.example.bruschedule.domain.model.LessonType
import com.example.bruschedule.domain.usecase.ScheduleConflictDetector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScheduleConflictDetectorTest {
    @Test
    fun reportsOverlappingPersonalAndWebsiteLessonsAndTheirDeadlines() {
        val personal = lesson(id = "personal", custom = true, start = "08:30", end = "10:05")
        val website = lesson(id = "website", custom = false, start = "09:00", end = "10:30")
        val task = AcademicTask(
            id = "task-1",
            lessonId = personal.id,
            taskTitle = "Подготовить отчёт",
            description = "",
            dueDate = 1L,
            isCompleted = false,
            profileId = personal.profileId,
            subjectNameSnapshot = personal.subjectName,
            linkType = DeadlineLinkType.LESSON,
        )

        val conflicts = ScheduleConflictDetector.find(listOf(personal, website), listOf(task))

        assertEquals(1, conflicts.size)
        assertEquals(personal.id, conflicts.single().personalLesson.id)
        assertEquals(listOf(website.id), conflicts.single().websiteLessons.map(Lesson::id))
        assertEquals(listOf(task.id), conflicts.single().linkedDeadlines.map(AcademicTask::id))
    }

    @Test
    fun touchingIntervalsAndDifferentDaysDoNotConflict() {
        val personal = lesson(id = "personal", custom = true, start = "08:30", end = "10:05")
        val adjacent = lesson(id = "adjacent", custom = false, start = "10:05", end = "11:40")
        val nextDay = lesson(id = "next-day", custom = false, date = "2026-10-20", start = "08:30", end = "10:05")

        assertTrue(ScheduleConflictDetector.find(listOf(personal, adjacent, nextDay), emptyList()).isEmpty())
    }

    private fun lesson(
        id: String,
        custom: Boolean,
        date: String = "2026-10-19",
        start: String,
        end: String,
    ) = Lesson(
        id = id,
        profileId = "profile-1",
        subjectName = if (custom) "Моя пара" else "Пара с сайта",
        teacherName = "Преподаватель",
        classroom = "ауд. 1",
        startTime = start,
        endTime = end,
        type = LessonType.PRACTICE,
        colorHex = "#FFFFFF",
        isCustom = custom,
        version = 1,
        lessonDate = date,
        dayOfWeek = 1,
        slotNumber = 1,
        sourceKey = if (custom) "custom:$id" else "web:$id",
    )
}
