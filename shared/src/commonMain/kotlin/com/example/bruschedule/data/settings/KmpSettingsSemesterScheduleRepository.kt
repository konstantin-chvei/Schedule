package com.example.bruschedule.data.settings

import com.example.bruschedule.domain.model.AcademicTrack
import com.example.bruschedule.domain.model.SemesterSchedulePreferences
import com.example.bruschedule.domain.repository.SemesterSchedulePreferencesRepository
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.LocalDate

class KmpSettingsSemesterScheduleRepository(
    private val settings: Settings,
) : SemesterSchedulePreferencesRepository {
    private val mutablePreferences = MutableStateFlow(readPreferences())
    private val preferences = mutablePreferences.asStateFlow()

    override fun observe() = preferences

    override suspend fun update(preferences: SemesterSchedulePreferences) {
        LocalDate.parse(preferences.sessionEndDateIso)
        LocalDate.parse(preferences.ifzoSessionEndDateIso)
        preferences.profileSessionEndDatesIso.forEach { (profileId, endDate) ->
            require(profileId.isNotBlank() && '\t' !in profileId && '\n' !in profileId) {
                "Идентификатор расписания не может быть пустым или содержать перенос строки"
            }
            LocalDate.parse(endDate)
        }
        preferences.tracksByUniversityId.forEach { (universityId, track) ->
            require(universityId.isNotBlank() && '\t' !in universityId && '\n' !in universityId) {
                "Идентификатор вуза не может быть пустым или содержать перенос строки"
            }
            require(track in AcademicTrack.availableForUniversity(universityId)) {
                "Выбранный тип сессии не подходит этому вузу"
            }
        }
        preferences.universitySessionEndDateOverridesIso.forEach { (key, endDate) ->
            require(key.isNotBlank() && '\t' !in key && '\n' !in key) {
                "Идентификатор даты сессии не может быть пустым или содержать перенос строки"
            }
            LocalDate.parse(endDate)
        }

        settings.putString(TRACK_KEY, preferences.track.name)
        settings.putString(SESSION_END_KEY, preferences.sessionEndDateIso)
        settings.putString(IFZO_SESSION_END_KEY, preferences.ifzoSessionEndDateIso)
        settings.putString(PROFILE_END_DATES_KEY, ProfileSessionEndDateCodec.encode(preferences.profileSessionEndDatesIso))
        settings.putString(UNIVERSITY_TRACKS_KEY, UniversityTrackCodec.encode(preferences.tracksByUniversityId))
        settings.putString(
            UNIVERSITY_END_DATES_KEY,
            UniversitySessionEndDateCodec.encode(preferences.universitySessionEndDateOverridesIso),
        )
        mutablePreferences.value = preferences
    }

    private fun readPreferences(): SemesterSchedulePreferences {
        val track = runCatching {
            AcademicTrack.valueOf(settings.getString(TRACK_KEY, AcademicTrack.BELARUSIAN.name))
        }.getOrDefault(AcademicTrack.BELARUSIAN)
        val storedEndDate = settings.getString(
            SESSION_END_KEY,
            SemesterSchedulePreferences.defaultSessionEndDate(track),
        )
        val validEndDate = runCatching {
            LocalDate.parse(storedEndDate)
            storedEndDate
        }.getOrDefault(SemesterSchedulePreferences.defaultSessionEndDate(track))
        val storedIfzoEndDate = settings.getString(
            IFZO_SESSION_END_KEY,
            SemesterSchedulePreferences.DEFAULT_IFZO_SESSION_END,
        )
        val validIfzoEndDate = runCatching {
            LocalDate.parse(storedIfzoEndDate)
            storedIfzoEndDate
        }.getOrDefault(SemesterSchedulePreferences.DEFAULT_IFZO_SESSION_END)
        return SemesterSchedulePreferences(
            track = track,
            sessionEndDateIso = validEndDate,
            ifzoSessionEndDateIso = validIfzoEndDate,
            profileSessionEndDatesIso = ProfileSessionEndDateCodec.decode(
                settings.getString(PROFILE_END_DATES_KEY, ""),
            ),
            tracksByUniversityId = UniversityTrackCodec.decode(settings.getString(UNIVERSITY_TRACKS_KEY, "")),
            universitySessionEndDateOverridesIso = UniversitySessionEndDateCodec.decode(
                settings.getString(UNIVERSITY_END_DATES_KEY, ""),
            ),
        )
    }

    private companion object {
        const val TRACK_KEY = "semester.track"
        const val SESSION_END_KEY = "semester.session_end_date"
        const val IFZO_SESSION_END_KEY = "semester.ifzo_session_end_date"
        const val PROFILE_END_DATES_KEY = "semester.profile_session_end_dates"
        const val UNIVERSITY_TRACKS_KEY = "semester.university_tracks"
        const val UNIVERSITY_END_DATES_KEY = "semester.university_session_end_dates"
    }
}

internal object ProfileSessionEndDateCodec {
    fun encode(values: Map<String, String>): String = values.entries
        .sortedBy { it.key }
        .joinToString("\n") { (profileId, endDate) -> "$profileId\t$endDate" }

    fun decode(encoded: String): Map<String, String> = decodeDateMap(encoded)
}

internal object UniversitySessionEndDateCodec {
    fun encode(values: Map<String, String>): String = values.entries
        .sortedBy { it.key }
        .joinToString("\n") { (key, endDate) -> "$key\t$endDate" }

    fun decode(encoded: String): Map<String, String> = decodeDateMap(encoded)
}

internal object UniversityTrackCodec {
    fun encode(values: Map<String, AcademicTrack>): String = values.entries
        .sortedBy { it.key }
        .joinToString("\n") { (universityId, track) -> "$universityId\t${track.name}" }

    fun decode(encoded: String): Map<String, AcademicTrack> = buildMap {
        encoded.lineSequence().forEach { line ->
            val separator = line.lastIndexOf('\t')
            if (separator <= 0) return@forEach
            val universityId = line.substring(0, separator)
            val track = runCatching { AcademicTrack.valueOf(line.substring(separator + 1)) }.getOrNull()
                ?: return@forEach
            if (universityId.isBlank() || track !in AcademicTrack.availableForUniversity(universityId)) return@forEach
            put(universityId, track)
        }
    }
}

private fun decodeDateMap(encoded: String): Map<String, String> = buildMap {
    encoded.lineSequence().forEach { line ->
        val separator = line.lastIndexOf('\t')
        if (separator <= 0) return@forEach
        val key = line.substring(0, separator)
        val endDate = line.substring(separator + 1)
        if (key.isBlank()) return@forEach
        if (runCatching { LocalDate.parse(endDate) }.isSuccess) put(key, endDate)
    }
}
