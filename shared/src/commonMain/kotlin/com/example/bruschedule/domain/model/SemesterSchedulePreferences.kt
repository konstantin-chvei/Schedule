package com.example.bruschedule.domain.model

enum class AcademicTrack(
    val label: String,
    val hint: String,
    val defaultSessionEndDateIso: String,
) {
    BELARUSIAN(
        label = "Белорусское отделение",
        hint = "БРУ · очное расписание",
        defaultSessionEndDateIso = "2027-01-10",
    ),
    RUSSIAN(
        label = "Российское отделение",
        hint = "БРУ · отдельная дата сессии",
        defaultSessionEndDateIso = "2027-01-25",
    ),
    IFZO(
        label = "ИФЗО",
        hint = "БРУ · индивидуальные даты",
        defaultSessionEndDateIso = "2027-01-30",
    ),
    BSU_DAYTIME_1_3_WINTER(
        label = "Очная · 1–3 курсы · зимняя",
        hint = "Зачётная неделя — конец декабря; сессия ориентировочно 04–24.01.2027",
        defaultSessionEndDateIso = "2027-01-24",
    ),
    BSU_DAYTIME_1_3_SUMMER(
        label = "Очная · 1–3 курсы · летняя",
        hint = "Ориентир на 2026/27: начало–конец июня",
        defaultSessionEndDateIso = "2027-06-30",
    ),
    BSU_DAYTIME_4_WINTER(
        label = "Очная · выпускной курс · зимняя",
        hint = "Сессия сдвинута на конец декабря — начало января",
        defaultSessionEndDateIso = "2027-01-10",
    ),
    BSU_DAYTIME_4_FINAL(
        label = "Очная · выпускной курс · практика и ГИА",
        hint = "Обычной летней сессии нет; задайте окончание практики/госэкзаменов вручную",
        defaultSessionEndDateIso = "2027-06-30",
    ),
    BSU_CORRESPONDENCE_YEAR_1(
        label = "Заочная · 1 курс",
        hint = "Установочная — сентябрь; зимняя волна обычно в январе/феврале",
        defaultSessionEndDateIso = "2027-02-28",
    ),
    BSU_CORRESPONDENCE_CUSTOM(
        label = "Заочная · 2–5 курсы",
        hint = "Волна зависит от факультета и курса; обязательно проверьте дату вручную",
        defaultSessionEndDateIso = "2027-04-30",
    ),
    CUSTOM(
        label = "Другой вуз · вручную",
        hint = "Укажите дату окончания сессии вашего вуза",
        defaultSessionEndDateIso = "2027-01-10",
    ),
    ;

    companion object {
        fun defaultForUniversity(universityId: String): AcademicTrack = when (universityId) {
            UniversityIds.BRU -> BELARUSIAN
            UniversityIds.BSU -> BSU_DAYTIME_1_3_WINTER
            else -> CUSTOM
        }

        fun availableForUniversity(universityId: String): List<AcademicTrack> = when (universityId) {
            UniversityIds.BRU -> listOf(BELARUSIAN, RUSSIAN, IFZO)
            UniversityIds.BSU -> listOf(
                BSU_DAYTIME_1_3_WINTER,
                BSU_DAYTIME_1_3_SUMMER,
                BSU_DAYTIME_4_WINTER,
                BSU_DAYTIME_4_FINAL,
                BSU_CORRESPONDENCE_YEAR_1,
                BSU_CORRESPONDENCE_CUSTOM,
            )
            else -> listOf(CUSTOM)
        }
    }
}

data class SemesterSchedulePreferences(

    val track: AcademicTrack = AcademicTrack.BELARUSIAN,
    val sessionEndDateIso: String = DEFAULT_BELARUSIAN_SESSION_END,
    val ifzoSessionEndDateIso: String = DEFAULT_IFZO_SESSION_END,
    val profileSessionEndDatesIso: Map<String, String> = emptyMap(),
    val tracksByUniversityId: Map<String, AcademicTrack> = emptyMap(),
    val universitySessionEndDateOverridesIso: Map<String, String> = emptyMap(),
) {
    fun trackForUniversity(universityId: String): AcademicTrack =
        tracksByUniversityId[universityId]?.takeIf { it in AcademicTrack.availableForUniversity(universityId) }
            ?: if (universityId == UniversityIds.BRU) track else AcademicTrack.defaultForUniversity(universityId)

    fun sessionEndDateIsoForUniversity(universityId: String): String {
        val selectedTrack = trackForUniversity(universityId)
        val preferenceKey = preferenceKey(universityId, selectedTrack)
        universitySessionEndDateOverridesIso[preferenceKey]?.let { return it }
        if (universityId == UniversityIds.BRU) {
            return if (selectedTrack == AcademicTrack.IFZO) ifzoSessionEndDateIso else sessionEndDateIso
        }
        return selectedTrack.defaultSessionEndDateIso
    }

    fun hasUniversitySessionEndDateOverride(universityId: String): Boolean =
        preferenceKey(universityId, trackForUniversity(universityId)) in universitySessionEndDateOverridesIso

    fun withTrackForUniversity(universityId: String, selectedTrack: AcademicTrack): SemesterSchedulePreferences {
        require(selectedTrack in AcademicTrack.availableForUniversity(universityId)) {
            "Этот вариант сессии не подходит выбранному вузу"
        }
        val tracks = tracksByUniversityId + (universityId to selectedTrack)
        if (universityId != UniversityIds.BRU) return copy(tracksByUniversityId = tracks)

        val oldTrack = track
        val oldLegacyDate = if (oldTrack == AcademicTrack.IFZO) ifzoSessionEndDateIso else sessionEndDateIso
        val oldDefault = if (oldTrack == AcademicTrack.IFZO) DEFAULT_IFZO_SESSION_END else defaultSessionEndDate(oldTrack)
        val overrides = universitySessionEndDateOverridesIso.toMutableMap()
        val oldKey = preferenceKey(UniversityIds.BRU, oldTrack)
        if (oldLegacyDate != oldDefault && oldKey !in overrides) overrides[oldKey] = oldLegacyDate

        val nextKey = preferenceKey(UniversityIds.BRU, selectedTrack)
        val nextDate = overrides[nextKey] ?: when (selectedTrack) {
            AcademicTrack.IFZO -> ifzoSessionEndDateIso
            else -> selectedTrack.defaultSessionEndDateIso
        }
        return copy(
            track = selectedTrack,
            sessionEndDateIso = nextDate,
            tracksByUniversityId = tracks,
            universitySessionEndDateOverridesIso = overrides,
        )
    }

    fun withUniversitySessionEndDate(universityId: String, sessionEndDateIso: String?): SemesterSchedulePreferences {
        val selectedTrack = trackForUniversity(universityId)
        val key = preferenceKey(universityId, selectedTrack)
        val overrides = universitySessionEndDateOverridesIso.toMutableMap()
        if (sessionEndDateIso == null || sessionEndDateIso == selectedTrack.defaultSessionEndDateIso) {
            overrides.remove(key)
        } else {
            overrides[key] = sessionEndDateIso
        }
        if (universityId != UniversityIds.BRU) {
            return copy(universitySessionEndDateOverridesIso = overrides)
        }
        val effectiveDate = overrides[key] ?: selectedTrack.defaultSessionEndDateIso
        return copy(
            sessionEndDateIso = effectiveDate,
            ifzoSessionEndDateIso = if (selectedTrack == AcademicTrack.IFZO) effectiveDate else ifzoSessionEndDateIso,
            universitySessionEndDateOverridesIso = overrides,
        )
    }

    companion object {
        const val DEFAULT_BELARUSIAN_SESSION_END = "2027-01-10"
        const val DEFAULT_RUSSIAN_SESSION_END = "2027-01-25"
        const val DEFAULT_IFZO_SESSION_END = "2027-01-30"

        fun defaultSessionEndDate(track: AcademicTrack): String = when (track) {
            AcademicTrack.BELARUSIAN -> DEFAULT_BELARUSIAN_SESSION_END
            AcademicTrack.RUSSIAN -> DEFAULT_RUSSIAN_SESSION_END
            AcademicTrack.IFZO -> DEFAULT_IFZO_SESSION_END
            else -> track.defaultSessionEndDateIso
        }

        fun preferenceKey(universityId: String, track: AcademicTrack): String = "$universityId|${track.name}"
    }
}
