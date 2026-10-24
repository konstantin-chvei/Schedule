package com.example.bruschedule.domain.usecase

import com.example.bruschedule.domain.model.Lesson
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus

object ScheduleCycleExpander {
    private val ONE_WEEK = DatePeriod(days = 7)
    private val TWO_WEEKS = DatePeriod(days = 14)
    private const val ONE_OFF_KEY_MARKER = "|once"
    private val EXCLUDED_DATE_MARKER = Regex("\\|except:(\\d{4}-\\d{2}-\\d{2})")

    fun expandWeekly(
        templateLessons: List<Lesson>,
        fromDate: LocalDate,
        throughDate: LocalDate,
    ): List<Lesson> {
        if (templateLessons.isEmpty() || throughDate < fromDate) return emptyList()

        return buildList {
            templateLessons.forEach { lesson ->
                if (lesson.isCustom) {
                    add(lesson)
                    return@forEach
                }
                if (isOneOff(lesson)) {
                    val oneOffDate = runCatching { LocalDate.parse(lesson.lessonDate) }.getOrNull()
                    if (oneOffDate != null && oneOffDate in fromDate..throughDate) add(lesson)
                    return@forEach
                }
                val templateDate = runCatching { LocalDate.parse(lesson.lessonDate) }.getOrNull() ?: run {
                    add(lesson)
                    return@forEach
                }
                var occurrenceDate = templateDate
                while (occurrenceDate.minus(ONE_WEEK) >= fromDate) occurrenceDate = occurrenceDate.minus(ONE_WEEK)
                while (occurrenceDate < fromDate) occurrenceDate = occurrenceDate.plus(ONE_WEEK)
                val excludedDate = excludedDateFor(lesson)
                while (occurrenceDate <= throughDate) {
                    val occurrenceKey = if (occurrenceDate == templateDate) {
                        lesson.sourceKey
                    } else {
                        stableOccurrenceKey(lesson, templateDate, occurrenceDate)
                    }
                    if (occurrenceDate != excludedDate) {
                        add(
                            lesson.copy(
                                id = if (occurrenceDate == templateDate) lesson.id else "web:$occurrenceKey",
                                lessonDate = occurrenceDate.toString(),
                                dayOfWeek = occurrenceDate.dayOfWeek.isoDayNumber,
                                sourceKey = occurrenceKey,
                            ),
                        )
                    }
                    occurrenceDate = occurrenceDate.plus(ONE_WEEK)
                }
            }
        }.distinctBy { it.sourceKey }
            .sortedWith(compareBy<Lesson>({ it.lessonDate }, { it.startTime }, { it.slotNumber }, { it.id }))
    }

    fun expand(
        templateLessons: List<Lesson>,
        fromDate: LocalDate,
        throughDate: LocalDate,
    ): List<Lesson> {
        if (templateLessons.isEmpty() || throughDate < fromDate) return emptyList()

        return buildList {
            templateLessons.forEach { lesson ->
                if (lesson.isCustom) {
                    add(lesson)
                    return@forEach
                }
                if (isOneOff(lesson)) {
                    val oneOffDate = runCatching { LocalDate.parse(lesson.lessonDate) }.getOrNull()
                    if (oneOffDate != null && oneOffDate in fromDate..throughDate) add(lesson)
                    return@forEach
                }
                val templateDate = runCatching { LocalDate.parse(lesson.lessonDate) }.getOrNull() ?: run {
                    add(lesson)
                    return@forEach
                }

                var occurrenceDate = templateDate
                while (occurrenceDate < fromDate) occurrenceDate = occurrenceDate.plus(TWO_WEEKS)
                val excludedDate = excludedDateFor(lesson)
                while (occurrenceDate <= throughDate) {
                    val occurrenceKey = if (occurrenceDate == templateDate) {
                        lesson.sourceKey
                    } else {
                        stableOccurrenceKey(lesson, templateDate, occurrenceDate)
                    }
                    if (occurrenceDate != excludedDate) {
                        add(
                            lesson.copy(
                                id = if (occurrenceDate == templateDate) lesson.id else "web:$occurrenceKey",
                                lessonDate = occurrenceDate.toString(),
                                dayOfWeek = occurrenceDate.dayOfWeek.isoDayNumber,
                                sourceKey = occurrenceKey,
                            ),
                        )
                    }
                    occurrenceDate = occurrenceDate.plus(TWO_WEEKS)
                }
            }
        }.distinctBy { it.sourceKey }
            .sortedWith(compareBy<Lesson>({ it.lessonDate }, { it.startTime }, { it.slotNumber }, { it.id }))
    }

    private fun isOneOff(lesson: Lesson): Boolean = lesson.sourceKey.endsWith(ONE_OFF_KEY_MARKER)

    private fun excludedDateFor(lesson: Lesson): LocalDate? = EXCLUDED_DATE_MARKER.find(lesson.sourceKey)
        ?.groupValues
        ?.getOrNull(1)
        ?.let { value -> runCatching { LocalDate.parse(value) }.getOrNull() }

    private fun stableOccurrenceKey(lesson: Lesson, templateDate: LocalDate, occurrenceDate: LocalDate): String {
        val templatePrefix = "${lesson.profileId}|$templateDate|"
        val slotIdentity = lesson.sourceKey.removePrefix(templatePrefix)
        return if (slotIdentity != lesson.sourceKey) {
            "${lesson.profileId}|$occurrenceDate|$slotIdentity"
        } else {
            "${lesson.sourceKey}|cycle:$occurrenceDate"
        }
    }
}
