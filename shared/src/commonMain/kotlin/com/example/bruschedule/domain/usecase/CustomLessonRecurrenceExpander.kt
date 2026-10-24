package com.example.bruschedule.domain.usecase

import com.example.bruschedule.domain.model.Lesson
import com.example.bruschedule.domain.model.LessonRecurrence
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus

object CustomLessonRecurrenceExpander {
    private const val MAX_OCCURRENCES = 512

    fun expand(
        template: Lesson,
        recurrence: LessonRecurrence,
        coverageEndDate: LocalDate,
    ): List<Lesson> {
        val anchorDate = LocalDate.parse(template.lessonDate)
        val endDate = if (coverageEndDate < anchorDate) anchorDate else coverageEndDate
        val dates = mutableListOf(anchorDate)

        if (recurrence != LessonRecurrence.NONE) {
            var index = 1
            while (true) {
                val nextDate = when (recurrence) {
                    LessonRecurrence.NONE -> anchorDate
                    LessonRecurrence.WEEKLY -> anchorDate.plus(DatePeriod(days = index * 7))
                    LessonRecurrence.BIWEEKLY -> anchorDate.plus(DatePeriod(days = index * 14))
                    LessonRecurrence.MONTHLY -> anchorDate.monthOccurrence(index)
                }
                if (nextDate > endDate) break
                if (dates.size >= MAX_OCCURRENCES) {
                    throw IllegalArgumentException("Слишком много повторений для одного занятия")
                }
                dates += nextDate
                index += 1
            }
        }

        return dates.mapIndexed { index, date ->
            val isAnchor = index == 0
            template.copy(
                id = if (isAnchor) template.id else "${template.id}:$date",
                lessonDate = date.toString(),
                dayOfWeek = date.dayOfWeek.isoDayNumber,
                isCustom = true,
                version = 0,
                sourceKey = if (isAnchor) template.sourceKey else "custom:${template.id}:$date",
            )
        }
    }

    private fun LocalDate.monthOccurrence(monthsAfterAnchor: Int): LocalDate {
        val absoluteMonth = year * 12 + monthNumber - 1 + monthsAfterAnchor
        val targetYear = absoluteMonth / 12
        val targetMonth = absoluteMonth % 12 + 1
        val targetDay = dayOfMonth.coerceAtMost(daysInMonth(targetYear, targetMonth))
        return LocalDate(targetYear, targetMonth, targetDay)
    }

    private fun daysInMonth(year: Int, month: Int): Int = when (month) {
        2 -> if (year % 400 == 0 || (year % 4 == 0 && year % 100 != 0)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }
}
