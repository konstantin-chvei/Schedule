package com.example.bruschedule.data.settings

import com.example.bruschedule.domain.model.AppearancePreferences
import com.example.bruschedule.domain.model.LessonAccentPreferences
import com.example.bruschedule.domain.model.LessonType
import com.example.bruschedule.domain.model.ScheduleAppTheme
import com.example.bruschedule.domain.repository.AppearancePreferencesRepository
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class KmpSettingsAppearanceRepository(
    private val settings: Settings,
) : AppearancePreferencesRepository {
    private val mutablePreferences = MutableStateFlow(readPreferences())
    private val preferences = mutablePreferences.asStateFlow()

    override fun observe() = preferences

    override suspend fun setTheme(theme: ScheduleAppTheme) {
        settings.putString(THEME_KEY, theme.name)
        mutablePreferences.value = mutablePreferences.value.copy(theme = theme)
    }

    override suspend fun setLessonColor(type: LessonType, colorHex: String) {
        require(isHexColor(colorHex)) { "Цвет должен быть в формате #RRGGBB" }
        val normalized = colorHex.uppercase()
        settings.putString(colorKey(type), normalized)
        mutablePreferences.value = mutablePreferences.value.copy(
            lessonAccents = mutablePreferences.value.lessonAccents.withColor(type, normalized),
        )
    }

    override suspend fun resetLessonColors() {
        val defaults = LessonAccentPreferences()
        listOf(
            LessonType.LECTURE,
            LessonType.LABORATORY,
            LessonType.PRACTICE,
            LessonType.UNKNOWN,
        ).forEach { type -> settings.putString(colorKey(type), defaults.colorFor(type)) }
        mutablePreferences.value = mutablePreferences.value.copy(lessonAccents = defaults)
    }

    private fun readPreferences(): AppearancePreferences {
        val theme = runCatching {
            ScheduleAppTheme.valueOf(settings.getString(THEME_KEY, ScheduleAppTheme.BLUE.name))
        }.getOrDefault(ScheduleAppTheme.BLUE)
        val defaults = LessonAccentPreferences()
        fun color(type: LessonType): String {
            val saved = settings.getString(colorKey(type), defaults.colorFor(type))
            return if (isHexColor(saved)) saved.uppercase() else defaults.colorFor(type)
        }
        return AppearancePreferences(
            theme = theme,
            lessonAccents = LessonAccentPreferences(
                lecture = color(LessonType.LECTURE),
                laboratory = color(LessonType.LABORATORY),
                practice = color(LessonType.PRACTICE),
                unknown = color(LessonType.UNKNOWN),
            ),
        )
    }

    private fun colorKey(type: LessonType): String = "appearance.lesson.${type.name.lowercase()}"

    private fun isHexColor(value: String): Boolean =
        value.length == 7 && value[0] == '#' && value.drop(1).all {
            it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F'
        }

    private companion object {
        const val THEME_KEY = "appearance.theme"
    }
}
