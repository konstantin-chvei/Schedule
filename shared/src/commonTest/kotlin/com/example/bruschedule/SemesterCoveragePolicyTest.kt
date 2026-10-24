package com.example.bruschedule

import com.example.bruschedule.domain.model.AcademicTrack
import com.example.bruschedule.domain.model.SemesterSchedulePreferences
import com.example.bruschedule.domain.usecase.SemesterCoveragePolicy
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class SemesterCoveragePolicyTest {
    @Test
    fun usesBranchSpecificSessionEndAndAddsExactlyOneWeek() {
        val belarusian = SemesterSchedulePreferences(
            track = AcademicTrack.BELARUSIAN,
            sessionEndDateIso = SemesterSchedulePreferences.DEFAULT_BELARUSIAN_SESSION_END,
        )
        val russian = SemesterSchedulePreferences(
            track = AcademicTrack.RUSSIAN,
            sessionEndDateIso = SemesterSchedulePreferences.DEFAULT_RUSSIAN_SESSION_END,
        )
        val ifzo = SemesterSchedulePreferences(
            track = AcademicTrack.IFZO,
            sessionEndDateIso = SemesterSchedulePreferences.DEFAULT_IFZO_SESSION_END,
        )

        assertEquals(LocalDate(2027, 1, 17), SemesterCoveragePolicy.coverageEndDate(belarusian))
        assertEquals(LocalDate(2027, 2, 1), SemesterCoveragePolicy.coverageEndDate(russian))
        assertEquals(LocalDate(2027, 2, 6), SemesterCoveragePolicy.coverageEndDate(ifzo))
    }

    @Test
    fun individualIfzoEndDateIsUsedInsteadOfTheCommonSessionDate() {
        val ifzo = SemesterSchedulePreferences(
            track = AcademicTrack.IFZO,
            sessionEndDateIso = "2027-01-27",
        )

        assertEquals(LocalDate(2027, 2, 3), SemesterCoveragePolicy.coverageEndDate(ifzo))
    }

    @Test
    fun profileSpecificSessionEndDatesAllowFacultiesToUseDifferentCoverageHorizons() {
        val preferences = SemesterSchedulePreferences(
            track = AcademicTrack.BELARUSIAN,
            sessionEndDateIso = SemesterSchedulePreferences.DEFAULT_BELARUSIAN_SESSION_END,
            profileSessionEndDatesIso = mapOf(
                "bru-auto-profile" to "2027-02-05",
                "bsu-economics-profile" to "2027-01-20",
                "stale-profile" to "2026-10-01",
            ),
        )

        assertEquals(
            LocalDate(2027, 2, 12),
            SemesterCoveragePolicy.coverageEndDate(LocalDate(2026, 10, 22), preferences, "bru-auto-profile"),
        )
        assertEquals(
            LocalDate(2027, 1, 27),
            SemesterCoveragePolicy.coverageEndDate(LocalDate(2026, 10, 22), preferences, "bsu-economics-profile"),
        )
        assertEquals(
            LocalDate(2027, 1, 17),
            SemesterCoveragePolicy.coverageEndDate(LocalDate(2026, 10, 22), preferences, "unknown-profile"),
        )
        assertEquals(
            LocalDate(2026, 10, 22),
            SemesterCoveragePolicy.coverageEndDate(LocalDate(2026, 10, 22), preferences, "stale-profile"),
        )
    }

    @Test
    fun bsuCoverageUsesTheSelectedUniversityTrackManualDatesAndFacultyOverride() {
        val defaults = SemesterSchedulePreferences()
        assertEquals(
            LocalDate(2027, 1, 31),
            SemesterCoveragePolicy.coverageEndDateForUniversity(
                LocalDate(2026, 10, 23),
                defaults,
                com.example.bruschedule.domain.model.UniversityIds.BSU,
            ),
        )
        assertEquals(
            LocalDate(2027, 1, 17),
            SemesterCoveragePolicy.coverageEndDateForUniversity(
                LocalDate(2026, 10, 23),
                defaults,
                com.example.bruschedule.domain.model.UniversityIds.BRU,
            ),
        )

        val summer = defaults
            .withTrackForUniversity(
                com.example.bruschedule.domain.model.UniversityIds.BSU,
                AcademicTrack.BSU_DAYTIME_1_3_SUMMER,
            )
            .withUniversitySessionEndDate(com.example.bruschedule.domain.model.UniversityIds.BSU, "2027-06-27")
            .copy(profileSessionEndDatesIso = mapOf("bio-profile" to "2027-06-29"))

        assertEquals(
            LocalDate(2027, 7, 4),
            SemesterCoveragePolicy.coverageEndDateForUniversity(
                LocalDate(2026, 10, 23),
                summer,
                com.example.bruschedule.domain.model.UniversityIds.BSU,
            ),
        )
        assertEquals(
            LocalDate(2027, 7, 6),
            SemesterCoveragePolicy.coverageEndDateForUniversity(
                LocalDate(2026, 10, 23),
                summer,
                com.example.bruschedule.domain.model.UniversityIds.BSU,
                "bio-profile",
            ),
        )
    }

    @Test
    fun coverageCannotEndBeforeTodayWhenAStoredSessionDateHasExpired() {
        val preferences = SemesterSchedulePreferences(
            track = AcademicTrack.BELARUSIAN,
            sessionEndDateIso = "2027-01-10",
        )

        assertEquals(
            LocalDate(2027, 2, 10),
            SemesterCoveragePolicy.coverageEndDate(LocalDate(2027, 2, 10), preferences),
        )
    }
}
