package com.example.bruschedule.domain.usecase

import com.example.bruschedule.domain.model.AcademicTask
import com.example.bruschedule.domain.model.Lesson
import kotlinx.datetime.LocalTime

data class ScheduleLessonConflict(
    val personalLesson: Lesson,
    val websiteLessons: List<Lesson>,
    val linkedDeadlines: List<AcademicTask>,
)

object ScheduleConflictDetector {
    fun find(lessons: List<Lesson>, tasks: List<AcademicTask>): List<ScheduleLessonConflict> {
        val websiteLessons = lessons.filterNot(Lesson::isCustom)
        return lessons.asSequence()
            .filter(Lesson::isCustom)
            .mapNotNull { personal ->
                val overlappingLessons = websiteLessons.filter { website -> personal.conflictsWith(website) }
                if (overlappingLessons.isEmpty()) return@mapNotNull null
                ScheduleLessonConflict(
                    personalLesson = personal,
                    websiteLessons = overlappingLessons.sortedWith(
                        compareBy<Lesson>({ it.lessonDate }, { it.startTime }, { it.subjectName }),
                    ),
                    linkedDeadlines = tasks.filter {
                        it.lessonId == personal.id && it.profileId == personal.profileId
                    }
                        .distinctBy(AcademicTask::id),
                )
            }
            .sortedWith(
                compareBy<ScheduleLessonConflict>(
                    { it.personalLesson.lessonDate },
                    { it.personalLesson.startTime },
                    { it.personalLesson.subjectName },
                ),
            )
            .toList()
    }

    private fun Lesson.conflictsWith(other: Lesson): Boolean {
        if (profileId != other.profileId) return false
        if (sourceKey.isNotBlank() && sourceKey == other.sourceKey) return true
        if (lessonDate != other.lessonDate) return false

        val thisStart = startTime.minutesFromMidnight()
        val thisEnd = endTime.minutesFromMidnight()
        val otherStart = other.startTime.minutesFromMidnight()
        val otherEnd = other.endTime.minutesFromMidnight()
        if (thisStart != null && thisEnd != null && otherStart != null && otherEnd != null) {
            return thisStart < otherEnd && otherStart < thisEnd
        }
        return startTime == other.startTime || (slotNumber > 0 && slotNumber == other.slotNumber)
    }

    private fun String.minutesFromMidnight(): Int? = runCatching {
        val time = LocalTime.parse(this)
        time.hour * 60 + time.minute
    }.getOrNull()
}
