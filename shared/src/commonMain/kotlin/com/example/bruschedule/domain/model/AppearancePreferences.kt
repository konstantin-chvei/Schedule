package com.example.bruschedule.domain.model

enum class ScheduleAppTheme(val label: String) {
    BLUE("Синяя"),
    BLACK("Чёрная"),
    WHITE("Белая"),
}

data class LessonAccentPreferences(
    val lecture: String = LessonTypePalette.LECTURE,
    val laboratory: String = LessonTypePalette.LABORATORY,
    val practice: String = LessonTypePalette.PRACTICE,
    val unknown: String = LessonTypePalette.UNKNOWN,
) {
    fun colorFor(type: LessonType): String = when (type) {
        LessonType.LECTURE -> lecture
        LessonType.LABORATORY -> laboratory
        LessonType.PRACTICE -> practice
        LessonType.UNKNOWN -> unknown
    }

    fun withColor(type: LessonType, colorHex: String): LessonAccentPreferences = when (type) {
        LessonType.LECTURE -> copy(lecture = colorHex)
        LessonType.LABORATORY -> copy(laboratory = colorHex)
        LessonType.PRACTICE -> copy(practice = colorHex)
        LessonType.UNKNOWN -> copy(unknown = colorHex)
    }
}

data class AppearancePreferences(
    val theme: ScheduleAppTheme = ScheduleAppTheme.BLUE,
    val lessonAccents: LessonAccentPreferences = LessonAccentPreferences(),
)
