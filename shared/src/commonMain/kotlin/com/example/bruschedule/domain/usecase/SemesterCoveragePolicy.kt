package com.example.bruschedule.domain.usecase

import com.example.bruschedule.domain.model.SemesterSchedulePreferences
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

object SemesterCoveragePolicy {
    const val DAYS_AFTER_SESSION = 7

    fun coverageStartDate(today: LocalDate): LocalDate = today

    fun sessionEndDate(preferences: SemesterSchedulePreferences): LocalDate =
        LocalDate.parse(preferences.sessionEndDateIso)

    fun sessionEndDate(preferences: SemesterSchedulePreferences, profileId: String?): LocalDate {
        val profileEndDate = profileId
            ?.let(preferences.profileSessionEndDatesIso::get)
            ?.let { value -> runCatching { LocalDate.parse(value) }.getOrNull() }
        return profileEndDate ?: sessionEndDate(preferences)
    }

    fun sessionEndDateForUniversity(
        preferences: SemesterSchedulePreferences,
        universityId: String,
        profileId: String? = null,
    ): LocalDate {
        val profileEndDate = profileId
            ?.let(preferences.profileSessionEndDatesIso::get)
            ?.let { value -> runCatching { LocalDate.parse(value) }.getOrNull() }
        return profileEndDate ?: LocalDate.parse(preferences.sessionEndDateIsoForUniversity(universityId))
    }

    fun coverageEndDate(preferences: SemesterSchedulePreferences): LocalDate =
        sessionEndDate(preferences).plus(DatePeriod(days = DAYS_AFTER_SESSION))

    fun coverageEndDate(preferences: SemesterSchedulePreferences, profileId: String?): LocalDate =
        sessionEndDate(preferences, profileId).plus(DatePeriod(days = DAYS_AFTER_SESSION))

    fun coverageEndDateForUniversity(
        preferences: SemesterSchedulePreferences,
        universityId: String,
        profileId: String? = null,
    ): LocalDate = sessionEndDateForUniversity(preferences, universityId, profileId)
        .plus(DatePeriod(days = DAYS_AFTER_SESSION))

    fun coverageEndDate(today: LocalDate, preferences: SemesterSchedulePreferences): LocalDate =
        coverageEndDate(preferences).let { configuredEnd -> if (configuredEnd < today) today else configuredEnd }

    fun coverageEndDate(
        today: LocalDate,
        preferences: SemesterSchedulePreferences,
        profileId: String?,
    ): LocalDate = coverageEndDate(preferences, profileId)
        .let { configuredEnd -> if (configuredEnd < today) today else configuredEnd }

    fun coverageEndDateForUniversity(
        today: LocalDate,
        preferences: SemesterSchedulePreferences,
        universityId: String,
        profileId: String? = null,
    ): LocalDate = coverageEndDateForUniversity(preferences, universityId, profileId)
        .let { configuredEnd -> if (configuredEnd < today) today else configuredEnd }
}
