package com.example.bruschedule

import com.example.bruschedule.data.remote.GitHubScheduleRemoteDataSource
import com.example.bruschedule.data.remote.HtmlTransport
import com.example.bruschedule.domain.model.ScheduleProfile
import com.example.bruschedule.domain.model.ScheduleProfileType
import com.example.bruschedule.domain.model.ScheduleWindow
import com.example.bruschedule.domain.model.UniversityIds
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BsuBiologyRuntimeSourcePolicyTest {
    @Test
    fun runtimeRejectsUniversityScheduleUrlBeforeMakingAnyRequest() = runTest {
        var requestCount = 0
        val transport = object : HtmlTransport {
            override suspend fun get(url: String): String {
                requestCount += 1
                error("Unexpected network request: $url")
            }
        }
        val source = GitHubScheduleRemoteDataSource(transport)
        val profile = ScheduleProfile(
            id = "biology-group-41",
            type = ScheduleProfileType.GROUP,
            name = "41",
            sourceUrl = "https://bsu.bio/schedule.php?study=d&course=1&group=41",
            universityId = UniversityIds.BSU,
        )

        assertFailsWith<IllegalArgumentException> {
            source.fetchHtml(
                profile,
                ScheduleWindow(LocalDate(2026, 10, 26), LocalDate(2026, 11, 1)),
            )
        }
        assertEquals(0, requestCount)
    }
}
