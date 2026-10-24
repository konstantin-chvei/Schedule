package com.example.bruschedule.domain.model

import kotlin.time.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

enum class ScheduleProfileType {
    GROUP,
    TEACHER,
}

data class ScheduleProfile(
    val id: String,
    val type: ScheduleProfileType,
    val name: String,
    val sourceUrl: String? = null,
    val sourceKey: String? = null,
    val universityId: String = UniversityIds.BRU,
)

enum class LessonType {
    LECTURE,
    LABORATORY,
    PRACTICE,
    UNKNOWN,
}

object LessonNotes {
    const val MAX_LENGTH = 500
}

object LessonStickerPalette {
    val choices = listOf(
        "😀", "😎", "🔥", "❤️", "👍",
        "👎", "😴", "🐵", "💀", "😭",
        "🤓", "💪", "🎯", "⭐", "❌",
        "🙂", "😍", "🥰", "😐", "🤗",
        "😔", "🤔", "😌", "😢", "😮",
        "😵‍💫", "🤒", "🤫", "😏", "😶",
    )
}

object LessonStickerCodec {
    private const val SEPARATOR = "\u001F"

    fun encode(stickers: List<String>): String = stickers
        .filter { it in LessonStickerPalette.choices }
        .distinct()
        .joinToString(SEPARATOR)

    fun decode(encoded: String): List<String> = encoded
        .split(SEPARATOR)
        .filter { it in LessonStickerPalette.choices }
        .distinct()
}

data class Lesson(
    val id: String,
    val profileId: String,
    val subjectName: String,
    val teacherName: String,
    val classroom: String,
    val startTime: String,
    val endTime: String,
    val type: LessonType,
    val colorHex: String,
    val isCustom: Boolean,
    val version: Int,
    val lessonDate: String,
    val dayOfWeek: Int,
    val slotNumber: Int = 0,
    val sourceKey: String = id,
    val sourceColorHex: String? = null,
    val notes: String = "",
    val rating: Int? = null,
    val stickers: List<String> = emptyList(),
)

enum class DeadlineLinkType {
    SUBJECT,
    LESSON,
    NONE,
}

enum class TaskReminderMode {
    INHERIT,
    CUSTOM,
    NONE,
}

data class DeadlineReminderPreferences(
    val enabled: Boolean = true,
    val offsetsMinutes: List<Int> = listOf(24 * 60),
)

object DeadlineReminderIntervals {
    const val FIFTEEN_MINUTES = 15
    const val ONE_HOUR = 60
    const val ONE_DAY = 24 * 60
    const val THREE_DAYS = 3 * 24 * 60
    const val ONE_WEEK = 7 * 24 * 60

    val choices = listOf(FIFTEEN_MINUTES, ONE_HOUR, ONE_DAY, THREE_DAYS, ONE_WEEK)

    fun label(minutes: Int): String = when (minutes) {
        FIFTEEN_MINUTES -> "15 мин"
        ONE_HOUR -> "1 час"
        ONE_DAY -> "1 день"
        THREE_DAYS -> "3 дня"
        ONE_WEEK -> "1 неделя"
        else -> "${minutes} мин"
    }
}

data class AcademicTask(
    val id: String,
    val lessonId: String? = null,
    val taskTitle: String,
    val description: String,
    val dueDate: Long,
    val isCompleted: Boolean,
    val profileId: String? = null,
    val subjectNameSnapshot: String = "",
    val linkType: DeadlineLinkType = if (lessonId == null) DeadlineLinkType.NONE else DeadlineLinkType.SUBJECT,
    val reminderMode: TaskReminderMode = TaskReminderMode.INHERIT,
    val reminderOffsetsMinutes: List<Int> = emptyList(),
)

data class ProfileCandidate(
    val type: ScheduleProfileType,
    val name: String,
    val sourceUrl: String,
    val sourceKey: String,
    val subtitle: String = "",
    val universityId: String = UniversityIds.BRU,
    val scheduleAvailable: Boolean = true,
)

data class ProfileSyncState(
    val profileId: String,
    val lastAttemptAtEpochMillis: Long? = null,
    val lastSuccessfulAtEpochMillis: Long? = null,
    val lastError: String? = null,
    val previousSnapshotAtEpochMillis: Long? = null,
    val canRollback: Boolean = false,
)

data class ScheduleWindow(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val semesterCoverageEndDate: LocalDate? = null,
) {
    companion object {
        fun currentAndNextWeek(clock: Clock = Clock.System): ScheduleWindow {
            val today = clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
            val monday = today.minus(DatePeriod(days = today.dayOfWeek.isoDayNumber - 1))
            return ScheduleWindow(
                startDate = monday,
                endDate = monday.plus(DatePeriod(days = 13)),
            )
        }
    }
}

data class RefreshCommit(
    val appliedLessonCount: Int,
    val preservedCustomLessonCount: Int,
    val previousSnapshotAtEpochMillis: Long?,
)

object LessonTypePalette {
    const val LECTURE = "#A7B9FF"
    const val LABORATORY = "#FF8998"
    const val PRACTICE = "#FFC979"
    const val UNKNOWN = "#A6B0C2"

    fun accent(type: LessonType): String = when (type) {
        LessonType.LECTURE -> LECTURE
        LessonType.LABORATORY -> LABORATORY
        LessonType.PRACTICE -> PRACTICE
        LessonType.UNKNOWN -> UNKNOWN
    }
}

object DomainIds {
    fun new(prefix: String): String = "$prefix-${kotlin.random.Random.Default.nextLong().toString(36)}-${kotlin.random.Random.Default.nextLong().toString(36)}"
}
