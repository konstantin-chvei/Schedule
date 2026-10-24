package com.example.bruschedule

import com.example.bruschedule.data.remote.GitHubDailyMessagesRemoteDataSource
import com.example.bruschedule.data.remote.GitHubScheduleDataPaths
import com.example.bruschedule.data.remote.GitHubScheduleProfileCatalogRepository
import com.example.bruschedule.data.remote.GitHubScheduleRemoteDataSource
import com.example.bruschedule.data.remote.HtmlTransport
import com.example.bruschedule.data.scraper.GitHubCsvScheduleParser
import com.example.bruschedule.data.scraper.UniversityScheduleHtmlParser
import com.example.bruschedule.domain.model.ScheduleProfile
import com.example.bruschedule.domain.model.ScheduleProfileType
import com.example.bruschedule.domain.model.ScheduleWindow
import com.example.bruschedule.domain.model.UniversityIds
import kotlinx.datetime.LocalDate
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitHubScheduleDataTest {
    @Test
    fun searchReadsThePerUniversityIndexAndCachesItAcrossQueries() = runSuspend {
        val requestedUrls = mutableListOf<String>()
        val transport = object : HtmlTransport {
            override suspend fun get(url: String): String {
                requestedUrls += url
                return """id,name,subtitle,source_key,schedule_path,status,updated_at
biology-group-41,41,Биофак · курс 1,bio-group-number:41,schedules/groups/biology-group-41.html,ready,2026-10-23T12:00:00Z
biology-group-42,42,Биофак · курс 1,,schedules/groups/biology-group-42.html,missing,
"""
            }
        }
        val repository = GitHubScheduleProfileCatalogRepository(transport)

        val first = repository.search(UniversityIds.BSU, ScheduleProfileType.GROUP, "41")
        val second = repository.search(UniversityIds.BSU, ScheduleProfileType.GROUP, "42")

        assertEquals(1, requestedUrls.size)
        assertEquals(
            GitHubScheduleDataPaths.indexUrl(UniversityIds.BSU, ScheduleProfileType.GROUP),
            requestedUrls.single(),
        )
        assertEquals("41", first.single().name)
        assertEquals("bio-group-number:41", first.single().sourceKey)
        assertTrue(first.single().scheduleAvailable)
        assertTrue(first.single().subtitle.contains("Расписание доступно"))
        assertEquals(
            "${GitHubScheduleDataPaths.UNIVERSITIES_ROOT}/bsu/schedules/groups/biology-group-41.html",
            first.single().sourceUrl,
        )
        assertEquals("42", second.single().name)
        assertEquals("biology-group-42", second.single().sourceKey)
        assertFalse(second.single().scheduleAvailable)
        assertTrue(second.single().subtitle.contains("Расписание пока недоступно"))
        assertTrue(requestedUrls.none { "bsu.bio" in it || "rasp.bru.by" in it })
    }

    @Test
    fun usesTheLegacyIndexUntilTheRenamedRepositoryIsAvailable() = runSuspend {
        val requestedUrls = mutableListOf<String>()
        val currentIndex = GitHubScheduleDataPaths.indexUrl(UniversityIds.BSU, ScheduleProfileType.GROUP)
        val legacyIndex = GitHubScheduleDataPaths.legacyIndexUrl(UniversityIds.BSU, ScheduleProfileType.GROUP)
        val transport = object : HtmlTransport {
            override suspend fun get(url: String): String {
                requestedUrls += url
                if (url == currentIndex) error("404: repository has not been renamed yet")
                return """id,name,subtitle,source_key,schedule_path,status
biology-group-41,41,Биофак · курс 1,bio-group-number:41,schedules/groups/biology-group-41.html,ready
"""
            }
        }
        val repository = GitHubScheduleProfileCatalogRepository(transport)

        val candidate = repository.search(UniversityIds.BSU, ScheduleProfileType.GROUP, "41").single()

        assertEquals(listOf(currentIndex, legacyIndex), requestedUrls)
        assertEquals(
            "${GitHubScheduleDataPaths.LEGACY_UNIVERSITIES_ROOT}/bsu/schedules/groups/biology-group-41.html",
            candidate.sourceUrl,
        )
    }

    @Test
    fun bsuTeacherIndexPassesFacultyParserKeyWithoutPublishingItsSourceUrl() = runSuspend {
        val repository = GitHubScheduleProfileCatalogRepository(object : HtmlTransport {
            override suspend fun get(url: String): String = """id,name,subtitle,source_key,schedule_path,status
ivanova-ii,Иванова И.И.,ФПМИ,ФПМИ,schedules/teachers/ivanova-ii.html,ready
"""
        })

        val candidate = repository.search(UniversityIds.BSU, ScheduleProfileType.TEACHER, "Иванова").single()

        assertEquals("ФПМИ", candidate.sourceKey)
        assertEquals(
            "${GitHubScheduleDataPaths.UNIVERSITIES_ROOT}/bsu/schedules/teachers/ivanova-ii.html",
            candidate.sourceUrl,
        )
        assertFalse(candidate.sourceUrl.contains("fpmi.bsu.by"))
    }

    @Test
    fun teacherSearchUsesItsOwnIndexAndAcceptsOneCharacterCodes() = runSuspend {
        val requestedUrls = mutableListOf<String>()
        val repository = GitHubScheduleProfileCatalogRepository(object : HtmlTransport {
            override suspend fun get(url: String): String {
                requestedUrls += url
                return "id,name,subtitle,schedule_path,status\nageeva-tn,Агеева Т.Н.,БРУ,schedules/teachers/ageeva-tn.csv,ready\n"
            }
        })

        val matches = repository.search(UniversityIds.BRU, ScheduleProfileType.TEACHER, "т.н")

        assertEquals("Агеева Т.Н.", matches.single().name)
        assertEquals(
            GitHubScheduleDataPaths.indexUrl(UniversityIds.BRU, ScheduleProfileType.TEACHER),
            requestedUrls.single(),
        )
    }

    @Test
    fun onlyPublicGitHubPagesIndexesAndScheduleFilesAreAccepted() {
        assertEquals("Schedule", GitHubScheduleDataPaths.REPOSITORY)
        assertEquals("https://konstantin-chvei.github.io/Schedule/", GitHubScheduleDataPaths.PAGES_URL)
        assertEquals(
            "${GitHubScheduleDataPaths.PAGES_URL}content/daily-messages.txt",
            GitHubDailyMessagesRemoteDataSource.DAILY_MESSAGES_URL,
        )
        assertTrue(GitHubScheduleDataPaths.isPublishedDataUrl(GitHubScheduleDataPaths.indexUrl(UniversityIds.BSU, ScheduleProfileType.GROUP)))
        assertEquals("BRU-schedule", GitHubScheduleDataPaths.LEGACY_REPOSITORY)
        assertEquals(
            "${GitHubScheduleDataPaths.LEGACY_PAGES_URL}content/daily-messages.txt",
            GitHubDailyMessagesRemoteDataSource.LEGACY_DAILY_MESSAGES_URL,
        )
        assertTrue(
            GitHubScheduleDataPaths.isPublishedDataUrl(
                GitHubScheduleDataPaths.legacyIndexUrl(UniversityIds.BSU, ScheduleProfileType.GROUP),
            ),
        )
        assertEquals(
            listOf(
                GitHubScheduleDataPaths.indexUrl(UniversityIds.BSU, ScheduleProfileType.GROUP),
                GitHubScheduleDataPaths.legacyIndexUrl(UniversityIds.BSU, ScheduleProfileType.GROUP),
            ),
            GitHubScheduleDataPaths.indexUrls(UniversityIds.BSU, ScheduleProfileType.GROUP),
        )
        val currentScheduleUrl =
            "${GitHubScheduleDataPaths.UNIVERSITIES_ROOT}/bsu/schedules/groups/biology-group-41.html"
        val savedLegacyScheduleUrl =
            "${GitHubScheduleDataPaths.LEGACY_UNIVERSITIES_ROOT}/bsu/schedules/groups/biology-group-41.html"
        assertTrue(GitHubScheduleDataPaths.isScheduleFileUrl(currentScheduleUrl))
        assertTrue(GitHubScheduleDataPaths.isScheduleFileUrl(savedLegacyScheduleUrl))
        assertEquals(currentScheduleUrl, GitHubScheduleDataPaths.canonicalScheduleUrl(savedLegacyScheduleUrl))
        assertEquals(
            listOf(currentScheduleUrl, savedLegacyScheduleUrl),
            GitHubScheduleDataPaths.scheduleUrls(savedLegacyScheduleUrl),
        )
        assertFalse(GitHubScheduleDataPaths.isPublishedDataUrl("https://bsu.bio/schedule.php?group=41"))
        assertFalse(
            GitHubScheduleDataPaths.isScheduleFileUrl(
                "${GitHubScheduleDataPaths.UNIVERSITIES_ROOT}/bsu/schedules/groups/../../secret.html",
            ),
        )
    }

    @Test
    fun dailyMessagesFallBackToThePreviousPagesRepositoryDuringMigration() = runSuspend {
        val requestedUrls = mutableListOf<String>()
        val source = GitHubDailyMessagesRemoteDataSource(object : HtmlTransport {
            override suspend fun get(url: String): String {
                requestedUrls += url
                if (url == GitHubDailyMessagesRemoteDataSource.DAILY_MESSAGES_URL) {
                    error("404: repository has not been renamed yet")
                }
                return "# comments are ignored\nСообщение из старого Pages\n\n"
            }
        })

        val messages = source.fetchMessages()

        assertEquals(GitHubDailyMessagesRemoteDataSource.DAILY_MESSAGES_URLS, requestedUrls)
        assertEquals(listOf("Сообщение из старого Pages"), messages)
        assertTrue(GitHubDailyMessagesRemoteDataSource.isDailyMessagesUrl(requestedUrls.last()))
    }

    @Test
    fun rejectsUnsafePathsFromAnIndexFile() {
        assertFailsWith<IllegalArgumentException> {
            GitHubScheduleDataPaths.scheduleUrl(
                UniversityIds.BSU,
                ScheduleProfileType.GROUP,
                "schedules/groups/../../secret.html",
            )
        }
    }

    @Test
    fun csvScheduleParserReadsQuotedCellsAndBuildsStableLessonIdentities() {
        val profile = ScheduleProfile(
            id = "profile-mpr-261",
            type = ScheduleProfileType.GROUP,
            name = "МПР-261",
            sourceUrl = "${GitHubScheduleDataPaths.UNIVERSITIES_ROOT}/bru/schedules/groups/mpr-261.csv",
            sourceKey = "mpr-261",
            universityId = UniversityIds.BRU,
        )
        val csv = """date,start_time,end_time,subject,teacher,classroom,type,source_key
2026-10-26,08:15,09:40,"Математика, линейная алгебра",Петров П.П.,203,Лекция,lesson-001
"""

        val first = GitHubCsvScheduleParser().parse(profile, csv, LocalDate(2026, 10, 26))
        val second = GitHubCsvScheduleParser().parse(profile, csv, LocalDate(2026, 11, 2))

        assertEquals(1, first.size)
        assertEquals("Математика, линейная алгебра", first.single().subjectName)
        assertEquals("Петров П.П.", first.single().teacherName)
        assertEquals("203", first.single().classroom)
        assertEquals("08:15", first.single().startTime)
        assertEquals("2026-10-26", first.single().lessonDate)
        assertEquals(first.single().sourceKey, second.single().sourceKey)
    }

    @Test
    fun dispatcherUsesTheGitHubCsvParserForPublishedCsvProfiles() {
        val profile = ScheduleProfile(
            id = "profile-41",
            type = ScheduleProfileType.GROUP,
            name = "41",
            sourceUrl = "${GitHubScheduleDataPaths.UNIVERSITIES_ROOT}/bsu/schedules/groups/41.csv",
            universityId = UniversityIds.BSU,
        )
        val parsed = UniversityScheduleHtmlParser().parse(
            profile,
            "date,time,subject\n26.10.2026,08.15–09.40,Философия\n",
            LocalDate(2026, 10, 26),
        )

        assertEquals("Философия", parsed.single().subjectName)
        assertEquals("08:15", parsed.single().startTime)
        assertFalse(UniversityScheduleHtmlParser().hasWeeklyScheduleTemplate(profile, ""))
    }

    @Test
    fun runtimeScheduleSourceRejectsUniversityWebsitesAndFetchesOnlyGitHubFiles() = runSuspend {
        var requestedUrl: String? = null
        val transport = object : HtmlTransport {
            override suspend fun get(url: String): String {
                requestedUrl = url
                return "date,time,subject\n2026-10-26,08:15-09:40,Математика\n"
            }
        }
        val source = GitHubScheduleRemoteDataSource(transport)
        val window = ScheduleWindow(LocalDate(2026, 10, 26), LocalDate(2026, 11, 1))
        val githubProfile = ScheduleProfile(
            id = "group-41",
            type = ScheduleProfileType.GROUP,
            name = "41",
            sourceUrl = "${GitHubScheduleDataPaths.UNIVERSITIES_ROOT}/bsu/schedules/groups/41.csv",
            universityId = UniversityIds.BSU,
        )

        val content = source.fetchHtml(githubProfile, window)
        assertTrue(content.startsWith("date,time,subject"))
        assertEquals(githubProfile.sourceUrl, requestedUrl)
        val savedProfile = githubProfile.copy(
            sourceUrl = "${GitHubScheduleDataPaths.LEGACY_UNIVERSITIES_ROOT}/bsu/schedules/groups/41.csv",
        )
        source.fetchHtml(savedProfile, window)
        assertEquals(githubProfile.sourceUrl, requestedUrl)
        assertFailsWith<IllegalArgumentException> {
            runSuspend {
                source.fetchHtml(githubProfile.copy(sourceUrl = "https://bsu.bio/schedule.php?group=41"), window)
            }
        }
        assertEquals(githubProfile.sourceUrl, requestedUrl)
    }

    @Test
    fun scheduleRefreshFallsBackToThePreviousPagesRepositoryUntilMigrationCompletes() = runSuspend {
        val requestedUrls = mutableListOf<String>()
        val currentUrl = "${GitHubScheduleDataPaths.UNIVERSITIES_ROOT}/bsu/schedules/groups/41.csv"
        val legacyUrl = "${GitHubScheduleDataPaths.LEGACY_UNIVERSITIES_ROOT}/bsu/schedules/groups/41.csv"
        val transport = object : HtmlTransport {
            override suspend fun get(url: String): String {
                requestedUrls += url
                if (url == currentUrl) error("404: repository has not been renamed yet")
                return "date,time,subject\n2026-10-26,08:15-09:40,Математика\n"
            }
        }
        val source = GitHubScheduleRemoteDataSource(transport)
        val legacyProfile = ScheduleProfile(
            id = "group-41",
            type = ScheduleProfileType.GROUP,
            name = "41",
            sourceUrl = legacyUrl,
            universityId = UniversityIds.BSU,
        )

        val content = source.fetchHtml(
            legacyProfile,
            ScheduleWindow(LocalDate(2026, 10, 26), LocalDate(2026, 11, 1)),
        )

        assertTrue(content.startsWith("date,time,subject"))
        assertEquals(listOf(currentUrl, legacyUrl), requestedUrls)
    }

    private fun <T> runSuspend(block: suspend () -> T): T {
        var result: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(value: Result<T>) {
                result = value
            }
        })
        return requireNotNull(result).getOrThrow()
    }
}
