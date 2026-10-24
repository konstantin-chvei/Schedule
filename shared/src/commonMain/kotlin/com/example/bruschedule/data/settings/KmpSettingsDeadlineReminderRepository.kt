package com.example.bruschedule.data.settings

import com.example.bruschedule.domain.model.DeadlineReminderIntervals
import com.example.bruschedule.domain.model.DeadlineReminderPreferences
import com.example.bruschedule.domain.repository.DeadlineReminderPreferencesRepository
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class KmpSettingsDeadlineReminderRepository(
    private val settings: Settings,
) : DeadlineReminderPreferencesRepository {
    private val mutablePreferences = MutableStateFlow(readPreferences())
    private val preferences = mutablePreferences.asStateFlow()

    override fun observe() = preferences

    override suspend fun update(preferences: DeadlineReminderPreferences) {
        val normalized = preferences.copy(
            offsetsMinutes = preferences.offsetsMinutes
                .filter { it in DeadlineReminderIntervals.choices }
                .distinct()
                .sortedDescending()
                .ifEmpty { listOf(DeadlineReminderIntervals.ONE_DAY) },
        )
        settings.putBoolean(ENABLED_KEY, normalized.enabled)
        settings.putString(OFFSETS_KEY, normalized.offsetsMinutes.joinToString(","))
        mutablePreferences.value = normalized
    }

    private fun readPreferences(): DeadlineReminderPreferences {
        val offsets = settings.getString(OFFSETS_KEY, DeadlineReminderIntervals.ONE_DAY.toString())
            .split(",")
            .mapNotNull { it.toIntOrNull() }
            .filter { it in DeadlineReminderIntervals.choices }
            .distinct()
            .sortedDescending()
            .ifEmpty { listOf(DeadlineReminderIntervals.ONE_DAY) }
        return DeadlineReminderPreferences(
            enabled = settings.getBoolean(ENABLED_KEY, true),
            offsetsMinutes = offsets,
        )
    }

    private companion object {
        const val ENABLED_KEY = "deadline_reminders.enabled"
        const val OFFSETS_KEY = "deadline_reminders.offsets_minutes"
    }
}
