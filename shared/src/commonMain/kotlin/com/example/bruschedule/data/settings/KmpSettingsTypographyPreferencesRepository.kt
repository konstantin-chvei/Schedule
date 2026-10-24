package com.example.bruschedule.data.settings

import com.example.bruschedule.domain.model.TypographyPreferences
import com.example.bruschedule.domain.repository.TypographyPreferencesRepository
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class KmpSettingsTypographyPreferencesRepository(
    private val settings: Settings,
) : TypographyPreferencesRepository {
    private val mutablePreferences = MutableStateFlow(readPreferences())
    private val preferences = mutablePreferences.asStateFlow()

    override fun observe() = preferences

    override suspend fun update(preferences: TypographyPreferences) {
        val normalized = preferences.normalized()
        val current = mutablePreferences.value
        if (current.displayScale != normalized.displayScale) {
            settings.putString(DISPLAY_SCALE_KEY, normalized.displayScale.toString())
        }
        if (current.headingScale != normalized.headingScale) {
            settings.putString(HEADING_SCALE_KEY, normalized.headingScale.toString())
        }
        if (current.bodyScale != normalized.bodyScale) {
            settings.putString(BODY_SCALE_KEY, normalized.bodyScale.toString())
        }
        if (current.captionScale != normalized.captionScale) {
            settings.putString(CAPTION_SCALE_KEY, normalized.captionScale.toString())
        }
        mutablePreferences.value = normalized
    }

    private fun readPreferences(): TypographyPreferences {
        val legacyScale = TypographyPreferences.normalizeScale(
            settings.getString(LEGACY_SCALE_KEY, TypographyPreferences.DEFAULT_FONT_SCALE.toString())
                .toFloatOrNull()
                ?: TypographyPreferences.DEFAULT_FONT_SCALE,
        )
        fun readScale(key: String): Float = TypographyPreferences.normalizeScale(
            settings.getString(key, legacyScale.toString()).toFloatOrNull() ?: legacyScale,
        )

        return TypographyPreferences(
            displayScale = readScale(DISPLAY_SCALE_KEY),
            headingScale = readScale(HEADING_SCALE_KEY),
            bodyScale = readScale(BODY_SCALE_KEY),
            captionScale = readScale(CAPTION_SCALE_KEY),
        )
    }

    private companion object {
        const val LEGACY_SCALE_KEY = "typography.font_scale"
        const val DISPLAY_SCALE_KEY = "typography.display_scale"
        const val HEADING_SCALE_KEY = "typography.heading_scale"
        const val BODY_SCALE_KEY = "typography.body_scale"
        const val CAPTION_SCALE_KEY = "typography.caption_scale"
    }
}
