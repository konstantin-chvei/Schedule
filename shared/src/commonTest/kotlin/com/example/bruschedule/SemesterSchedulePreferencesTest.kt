package com.example.bruschedule

import com.example.bruschedule.data.settings.ProfileSessionEndDateCodec
import com.example.bruschedule.data.settings.UniversitySessionEndDateCodec
import com.example.bruschedule.data.settings.UniversityTrackCodec
import com.example.bruschedule.domain.model.AcademicTrack
import com.example.bruschedule.domain.model.UniversityIds
import kotlin.test.Test
import kotlin.test.assertEquals

class SemesterSchedulePreferencesTest {
    @Test
    fun profileSpecificEndDatesRoundTripThroughTheLocalSettingsCodec() {
        val overrides = mapOf(
            "bru-auto-profile" to "2027-02-05",
            "bsu-economics-profile" to "2027-01-20",
        )

        assertEquals(overrides, ProfileSessionEndDateCodec.decode(ProfileSessionEndDateCodec.encode(overrides)))
    }

    @Test
    fun malformedStoredProfileEndDatesAreIgnored() {
        val encoded = "bru-profile\t2027-02-05\nmissing-separator\ninvalid-date\tnot-a-date"

        assertEquals(
            mapOf("bru-profile" to "2027-02-05"),
            ProfileSessionEndDateCodec.decode(encoded),
        )
    }

    @Test
    fun universityTracksAndManualDatesRoundTripThroughTheirLocalSettingsCodecs() {
        val tracks = mapOf(
            UniversityIds.BSU to AcademicTrack.BSU_CORRESPONDENCE_CUSTOM,
            UniversityIds.BRU to AcademicTrack.RUSSIAN,
        )
        val dates = mapOf(
            "bsu|BSU_CORRESPONDENCE_CUSTOM" to "2027-04-18",
            "bru|RUSSIAN" to "2027-01-26",
        )

        assertEquals(tracks, UniversityTrackCodec.decode(UniversityTrackCodec.encode(tracks)))
        assertEquals(dates, UniversitySessionEndDateCodec.decode(UniversitySessionEndDateCodec.encode(dates)))
    }

    @Test
    fun unsupportedStoredUniversityTracksAreIgnored() {
        val encoded = "bsu\tBSU_CORRESPONDENCE_CUSTOM\nunknown\tIFZO"

        assertEquals(
            mapOf(UniversityIds.BSU to AcademicTrack.BSU_CORRESPONDENCE_CUSTOM),
            UniversityTrackCodec.decode(encoded),
        )
    }
}
