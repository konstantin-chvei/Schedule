package com.example.bruschedule

import com.example.bruschedule.data.scraper.BruScheduleDirectoryParser
import com.example.bruschedule.data.remote.BruScheduleProfileCatalogRepository
import com.example.bruschedule.data.remote.BruScheduleRemoteDataSource
import com.example.bruschedule.data.remote.BsuScheduleProfileCatalogRepository
import com.example.bruschedule.data.remote.HtmlTransport
import com.example.bruschedule.data.remote.UniversityScheduleProfileCatalogRepository
import com.example.bruschedule.data.scraper.BruScheduleScraper
import com.example.bruschedule.data.scraper.BsuBiologyFacultyTeacherScheduleParser
import com.example.bruschedule.data.scraper.BsuBusinessInstituteTeacherScheduleParser
import com.example.bruschedule.data.scraper.BsuChemistryFacultyTeacherScheduleParser
import com.example.bruschedule.data.scraper.BsuEconomyFacultyTeacherScheduleParser
import com.example.bruschedule.data.scraper.BsuFacultyTeacherScheduleParserRegistry
import com.example.bruschedule.data.scraper.BsuFirFacultyTeacherScheduleParser
import com.example.bruschedule.data.scraper.BsuFfsnFacultyTeacherScheduleParser
import com.example.bruschedule.data.scraper.BsuFpmiFacultyTeacherScheduleParser
import com.example.bruschedule.data.scraper.BsuFscFacultyTeacherScheduleParser
import com.example.bruschedule.data.scraper.BsuGeographyFacultyTeacherScheduleParser
import com.example.bruschedule.data.scraper.BsuHistoryFacultyTeacherScheduleParser
import com.example.bruschedule.data.scraper.BsuJournalismFacultyTeacherScheduleParser
import com.example.bruschedule.data.scraper.BsuLawFacultyTeacherScheduleParser
import com.example.bruschedule.data.scraper.BsuMmfFacultyTeacherScheduleParser
import com.example.bruschedule.data.scraper.BsuPhilologyFacultyTeacherScheduleParser
import com.example.bruschedule.data.scraper.BsuPhysicsFacultyTeacherScheduleParser
import com.example.bruschedule.data.scraper.BsuRctFacultyTeacherScheduleParser
import com.example.bruschedule.data.scraper.BsuTeacherProfileSourceKey
import com.example.bruschedule.domain.model.Lesson
import com.example.bruschedule.domain.model.ProfileCandidate
import com.example.bruschedule.domain.model.LessonType
import com.example.bruschedule.domain.model.LessonTypePalette
import com.example.bruschedule.domain.model.LessonStickerCodec
import com.example.bruschedule.domain.model.ScheduleProfile
import com.example.bruschedule.domain.model.ScheduleProfileType
import com.example.bruschedule.domain.model.ScheduleWindow
import com.example.bruschedule.domain.model.UniversityIds
import com.example.bruschedule.domain.repository.ScheduleProfileCatalogRepository
import com.example.bruschedule.domain.usecase.ScheduleMergeStrategy
import kotlinx.datetime.LocalDate
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScheduleArchitectureTest {
    private val profile = ScheduleProfile(
        id = "group-mpr-261",
        type = ScheduleProfileType.GROUP,
        name = "МПР-261",
    )

    @Test
    fun bsuFacultyTeacherAdapterRegistryCoversEveryConfiguredFacultySource() {
        val catalogue = BsuScheduleProfileCatalogRepository(object : HtmlTransport {
            override suspend fun get(url: String): String = error("This contract test must not access the network")
        })
        assertEquals(
            catalogue.configuredFacultyNames,
            BsuFacultyTeacherScheduleParserRegistry.registeredFacultyNames,
        )
        assertEquals(
            setOf(
                "Экономический факультет",
                "ФПМИ",
                "Механико-математический факультет",
                "Географический факультет",
                "ФМО",
                "ФСК",
                "Биологический факультет",
                "Институт бизнеса",
                "Исторический факультет",
                "Юридический факультет",
                "Химический факультет",
                "Физический факультет",
                "Факультет журналистики",
                "Филологический факультет",
                "Факультет философии и социальных наук",
                "Факультет радиофизики и компьютерных технологий",
            ),
            BsuFacultyTeacherScheduleParserRegistry.registeredFacultyNames,
        )
        val officialFacultySourceAliases = setOf(
            "Биологический факультет",
            "Военный факультет",
            "Географический факультет",
            "Факультет журналистики",
            "Исторический факультет",
            "Механико-математический факультет",
            "ФПМИ",
            "Факультет радиофизики и компьютерных технологий",
            "ФСК",
            "Факультет философии и социальных наук",
            "Физический факультет",
            "Филологический факультет",
            "Химический факультет",
            "Экономический факультет",
            "Юридический факультет",
            "ФМО",
        )
        assertEquals(
            setOf("Военный факультет"),
            officialFacultySourceAliases - BsuFacultyTeacherScheduleParserRegistry.registeredFacultyNames,
            "Military Faculty has no public lesson timetable URL in the official BSU pages",
        )
        assertTrue("Институт бизнеса" in BsuFacultyTeacherScheduleParserRegistry.registeredFacultyNames)
        val adapters = BsuFacultyTeacherScheduleParserRegistry.registeredFacultyNames.map { facultyName ->
            BsuFacultyTeacherScheduleParserRegistry.parserFor(facultyName)
        }
        assertEquals(
            BsuFacultyTeacherScheduleParserRegistry.registeredFacultyNames.size,
            adapters.distinct().size,
            "Each configured source has a dedicated faculty adapter instance",
        )
        assertTrue(BsuFacultyTeacherScheduleParserRegistry.parserFor("Экономический факультет") is BsuEconomyFacultyTeacherScheduleParser)
        assertTrue(BsuFacultyTeacherScheduleParserRegistry.parserFor("ФПМИ") is BsuFpmiFacultyTeacherScheduleParser)
        assertTrue(
            BsuFacultyTeacherScheduleParserRegistry.parserFor("Механико-математический факультет") is
                BsuMmfFacultyTeacherScheduleParser,
        )
        assertTrue(BsuFacultyTeacherScheduleParserRegistry.parserFor("Географический факультет") is BsuGeographyFacultyTeacherScheduleParser)
        assertTrue(BsuFacultyTeacherScheduleParserRegistry.parserFor("ФМО") is BsuFirFacultyTeacherScheduleParser)
        assertTrue(BsuFacultyTeacherScheduleParserRegistry.parserFor("ФСК") is BsuFscFacultyTeacherScheduleParser)
        assertTrue(BsuFacultyTeacherScheduleParserRegistry.parserFor("Биологический факультет") is BsuBiologyFacultyTeacherScheduleParser)
        assertTrue(BsuFacultyTeacherScheduleParserRegistry.parserFor("Институт бизнеса") is BsuBusinessInstituteTeacherScheduleParser)
        assertTrue(BsuFacultyTeacherScheduleParserRegistry.parserFor("Исторический факультет") is BsuHistoryFacultyTeacherScheduleParser)
        assertTrue(BsuFacultyTeacherScheduleParserRegistry.parserFor("Юридический факультет") is BsuLawFacultyTeacherScheduleParser)
        assertTrue(BsuFacultyTeacherScheduleParserRegistry.parserFor("Химический факультет") is BsuChemistryFacultyTeacherScheduleParser)
        assertTrue(BsuFacultyTeacherScheduleParserRegistry.parserFor("Физический факультет") is BsuPhysicsFacultyTeacherScheduleParser)
        assertTrue(BsuFacultyTeacherScheduleParserRegistry.parserFor("Факультет журналистики") is BsuJournalismFacultyTeacherScheduleParser)
        assertTrue(BsuFacultyTeacherScheduleParserRegistry.parserFor("Филологический факультет") is BsuPhilologyFacultyTeacherScheduleParser)
        assertTrue(
            BsuFacultyTeacherScheduleParserRegistry.parserFor("Факультет философии и социальных наук") is
                BsuFfsnFacultyTeacherScheduleParser,
        )
        assertTrue(
            BsuFacultyTeacherScheduleParserRegistry.parserFor("Факультет радиофизики и компьютерных технологий") is
                BsuRctFacultyTeacherScheduleParser,
        )
    }

    @Test
    fun universityCatalogueForwardsPartialTeacherSearchResults() {
        val candidate = ProfileCandidate(
            type = ScheduleProfileType.TEACHER,
            name = "Иванова И.И.",
            sourceUrl = "https://fpmi.bsu.by/timetable",
            sourceKey = "source-key",
            universityId = UniversityIds.BSU,
        )
        val catalogue = object : ScheduleProfileCatalogRepository {
            override suspend fun search(type: ScheduleProfileType, query: String) = listOf(candidate)

            override suspend fun search(
                universityId: String,
                type: ScheduleProfileType,
                query: String,
                onPartialResults: (List<ProfileCandidate>) -> Unit,
            ): List<ProfileCandidate> {
                onPartialResults(listOf(candidate))
                return listOf(candidate)
            }
        }
        val partials = mutableListOf<List<ProfileCandidate>>()

        val results = runSuspending {
            UniversityScheduleProfileCatalogRepository(mapOf(UniversityIds.BSU to catalogue))
                .search(UniversityIds.BSU, ScheduleProfileType.TEACHER, "Иванова") { partials += it }
        }

        assertEquals(listOf(candidate), partials.single())
        assertEquals(listOf(candidate), results)
    }

    @Test
    fun parsesRealBruTableRowsAndMapsSiteTypesToPastelAccents() {
        val lessons = BruScheduleScraper().parse(profile, sampleWeekHtml(), LocalDate(2026, 10, 19))

        assertEquals(2, lessons.size)
        val lecture = lessons.first { it.type == LessonType.LECTURE }
        assertEquals("2026-10-19", lecture.lessonDate)
        assertEquals("Информатика", lecture.subjectName)
        assertEquals("Заровчатская Е.В", lecture.teacherName)
        assertEquals("1 корпус, ауд. 530", lecture.classroom)
        assertEquals("08:30", lecture.startTime)
        assertEquals(LessonTypePalette.LECTURE, lecture.colorHex)
        assertEquals("#3788D8", lecture.sourceColorHex)

        val laboratory = lessons.first { it.type == LessonType.LABORATORY }
        assertEquals(LessonTypePalette.LABORATORY, laboratory.colorHex)

        assertEquals("#E0A800", laboratory.sourceColorHex)
    }

    @Test
    fun parsesTeacherOccupancyCardsAndTheirExplicitDates() {
        val teacher = ScheduleProfile("teacher-1", ScheduleProfileType.TEACHER, "Иванова Н.В.")
        val html = """
            <html><body>
              <div class="hero"><h2>👩‍🏫 Иванова Н.В.</h2></div>
              <div class="week-header">Неделя 1: 19.10 – 25.10.2026</div>
              <table class="schedule-table">
                <thead><tr><th class="time-col">Время</th><th>Пн<br><small>19.10</small></th></tr></thead>
                <tbody><tr><td class="time-col">1 пара 08:30–10:05</td>
                  <td><div class="occ-card occ-lab">
                    <div class="occ-line1">Лабораторная</div>
                    <div class="occ-line2">Экология</div>
                    <div class="occ-line3">БИОР-251</div>
                    <div class="occ-line4">🏛️ 7 корпус, ауд. 501</div>
                    <div class="occ-line5">📅 2026-10-19 08:30-10:05</div>
                  </div></td>
                </tr></tbody>
              </table>
            </body></html>
        """.trimIndent()

        val parsed = BruScheduleScraper().parse(teacher, html, LocalDate(2026, 10, 19)).single()

        assertEquals("Экология", parsed.subjectName)
        assertEquals("Иванова Н.В.", parsed.teacherName)
        assertEquals("7 корпус, ауд. 501", parsed.classroom)
        assertEquals("2026-10-19", parsed.lessonDate)
        assertEquals(LessonType.LABORATORY, parsed.type)
    }

    @Test
    fun parserHandlesDecemberToJanuaryHeaderAcrossTwoWeekCycle() {
        val html = """
            <html><body>
            <div class="text-muted">28.12.2026 — 10.01.2027 (2 недели)</div>
            <div class="week-header">Неделя 1: 28.12 – 03.01.2027</div>
            <table class="schedule-table">
              <thead><tr><th class="time-col">Время</th>
                <th>Пн<br><small>28.12</small></th><th>Пн<br><small>04.01</small></th>
              </tr></thead>
              <tbody><tr><td class="time-col">1 пара 08:30–10:05</td>
                <td><div class="lesson-card lesson-practice"><div class="title">Алгебра</div><div class="info">👤 Иванов И.И<br>📍 ауд. 10<br>Практика</div></div></td>
                <td><div class="lesson-card lesson-practice"><div class="title">Алгебра</div><div class="info">👤 Иванов И.И<br>📍 ауд. 10<br>Практика</div></div></td>
              </tr></tbody>
            </table></body></html>
        """.trimIndent()

        val lessons = BruScheduleScraper().parse(profile, html, LocalDate(2026, 12, 28))

        assertEquals(listOf("2026-12-28", "2027-01-04"), lessons.map { it.lessonDate })
        assertTrue(lessons.all { it.dayOfWeek == 1 })
    }

    @Test
    fun groupDirectoryRetainsFacultyForExactGroupLookup() {
        val parser = BruScheduleDirectoryParser()
        val rootHtml = """
            <a href="/full_time/%D0%90%D0%B2%D1%82%D0%BE%D0%BC%D0%B5%D1%85%D0%B0%D0%BD%D0%B8%D1%87%D0%B5%D1%81%D0%BA%D0%B8%D0%B9%20%D1%84%D0%B0%D0%BA%D1%83%D0%BB%D1%8C%D1%82%D0%B5%D1%82/index.html">
                🏛️ Автомеханический факультет <small>Групп: 24</small>
            </a>
            <a href="/full_time/%D0%A4%D0%B0%D0%BA%D1%83%D0%BB%D1%8C%D1%82%D0%B5%D1%82%20%D1%83%D0%BF%D1%80%D0%B0%D0%B2%D0%BB%D0%B5%D0%BD%D0%B8%D1%8F%20%D0%B8%20%D0%B8%D0%BD%D0%BD%D0%BE%D0%B2%D0%B0%D1%86%D0%B8%D0%B9/index.html">
                🏛️ Факультет управления и инноваций <small>Групп: 25</small>
            </a>
        """.trimIndent()
        val rootUrl = BruScheduleDirectoryParser.FULL_TIME_INDEX
        val faculties = parser.facultyDirectories(rootHtml, rootUrl, ScheduleProfileType.GROUP)
        assertEquals(listOf("Автомеханический факультет", "Факультет управления и инноваций"), faculties.map { it.name })

        val groupHtml = """
            <h1>🏛️ Автомеханический факультет</h1>
            <a class="group-link" href="/full_time/auto/%D0%9C%D0%9F%D0%A0-261_weeks_20261019_20261101.html">
                <strong>МПР-261</strong><small>(МПР-261)</small>
                <span class="period-label">19.10 – 01.11.2026</span>
            </a>
        """.trimIndent()
        val candidate = parser.groupCandidates(groupHtml, faculties.first().url).single()

        assertEquals("МПР-261", candidate.name)
        assertEquals(faculties.first().url, candidate.sourceUrl)
        assertEquals("Автомеханический факультет · 19.10 – 01.11.2026", candidate.subtitle)
        assertTrue(parser.isExactMatch(candidate, "МПР-261"))
        assertTrue(parser.matchesQuery(candidate, "мпр-261"))
        assertFalse(parser.matchesQuery(candidate, "МПР-261-1"))
    }

    @Test
    fun siteDirectoryUrlsUseHttpsAndEncodeRawCyrillicPathSegments() {
        val url = BruScheduleDirectoryParser().absolutize(
            BruScheduleDirectoryParser.ROOT,
            "http://rasp.bru.by/full_time/Факультет управления и инноваций/index.html",
        )

        assertTrue(url.startsWith("https://rasp.bru.by/"))
        assertTrue(url.contains("%20"))
        assertFalse(url.contains("Факультет управления"))
        assertFalse(url.contains(" "))
    }

    @Test
    fun exactGroupSearchSkipsFailedFacultyAndReturnsTheCorrectFaculty() {
        val autoUrl = "https://rasp.bru.by/full_time/auto/index.html"
        val managementUrl = "https://rasp.bru.by/full_time/management/index.html"
        val rootHtml = """
            <a href="$managementUrl">🏛️ Факультет управления и инноваций <small>Групп: 25</small></a>
            <a href="$autoUrl">🏛️ Автомеханический факультет <small>Групп: 24</small></a>
        """.trimIndent()
        val autoHtml = """
            <h1>🏛️ Автомеханический факультет</h1>
            <a class="group-link" href="https://rasp.bru.by/full_time/auto/МПР-261_weeks_20261019_20261101.html">
                <strong>МПР-261</strong><span class="period-label">19.10 – 01.11.2026</span>
            </a>
        """.trimIndent()
        val requestedUrls = mutableListOf<String>()
        val transport = object : HtmlTransport {
            override suspend fun get(url: String): String {
                requestedUrls += url
                return when (url) {
                    BruScheduleDirectoryParser.FULL_TIME_INDEX -> rootHtml
                    autoUrl -> autoHtml
                    managementUrl -> error("HTTP 400 on an unrelated faculty must not abort this lookup")
                    else -> error("Unexpected test URL: $url")
                }
            }
        }

        val matches = runSuspending {
            BruScheduleProfileCatalogRepository(transport).search(ScheduleProfileType.GROUP, "МПР-261")
        }

        assertEquals(1, matches.size)
        assertEquals("МПР-261", matches.single().name)
        assertEquals("Автомеханический факультет", matches.single().subtitle.substringBefore(" ·"))
        assertEquals(autoUrl, matches.single().sourceUrl)
        assertTrue(managementUrl in requestedUrls)
        assertTrue(requestedUrls.indexOf(managementUrl) < requestedUrls.indexOf(autoUrl))
        assertFalse(
            BruScheduleDirectoryParser.PART_TIME_INDEX in requestedUrls,
            "Unexpected directory requests: $requestedUrls; matches: ${matches.map { it.name to it.sourceUrl }}",
        )
    }

    @Test
    fun bruDatasetDiscoveryEnumeratesPublishedTeachersFromTheOfficialIndex() {
        val teacherUrl = "https://rasp.bru.by/teachers/ageeva-tn.html"
        val teachersHtml = "<a href=\"/teachers/ageeva-tn.html\">Агеева Т.Н. 12</a>"
        val transport = object : HtmlTransport {
            override suspend fun get(url: String): String = when (url) {
                BruScheduleDirectoryParser.TEACHERS_INDEX -> teachersHtml
                else -> error("Unexpected fixture request: $url")
            }
        }

        val candidates = runSuspending {
            BruScheduleProfileCatalogRepository(transport).discoverAll(ScheduleProfileType.TEACHER)
        }

        assertEquals(1, candidates.size)
        assertEquals("Агеева Т.Н.", candidates.single().name)
        assertEquals(teacherUrl, candidates.single().sourceUrl)
    }

    @Test
    fun bruDatasetDiscoveryEnumeratesAllGroupsFromEveryPublishedFacultyDirectory() {
        val autoUrl = "https://rasp.bru.by/full_time/auto/index.html"
        val managementUrl = "https://rasp.bru.by/full_time/management/index.html"
        val autoHtml = """
            <h1>Автомеханический факультет</h1>
            <a class="group-link" href="/full_time/auto/МПР-261_weeks_20261019_20261101.html">
                <strong>МПР-261</strong><span class="period-label">19.10 – 01.11.2026</span>
            </a>
        """.trimIndent()
        val managementHtml = """
            <h1>Факультет управления и инноваций</h1>
            <a class="group-link" href="/full_time/management/УИ-241_weeks_20261019_20261101.html">
                <strong>УИ-241</strong><span class="period-label">19.10 – 01.11.2026</span>
            </a>
        """.trimIndent()
        val fullTimeRoot = """
            <a href="$managementUrl">Факультет управления и инноваций <small>Групп: 25</small></a>
            <a href="$autoUrl">Автомеханический факультет <small>Групп: 24</small></a>
        """.trimIndent()
        val transport = object : HtmlTransport {
            override suspend fun get(url: String): String = when (url) {
                BruScheduleDirectoryParser.FULL_TIME_INDEX -> fullTimeRoot
                BruScheduleDirectoryParser.PART_TIME_INDEX -> "<html><body></body></html>"
                autoUrl -> autoHtml
                managementUrl -> managementHtml
                else -> error("Unexpected fixture request: $url")
            }
        }

        val candidates = runSuspending {
            BruScheduleProfileCatalogRepository(transport).discoverAll(ScheduleProfileType.GROUP)
        }

        assertEquals(setOf("МПР-261", "УИ-241"), candidates.map { it.name }.toSet())
        assertEquals(2, candidates.map { it.sourceUrl }.distinct().size)
    }

    @Test
    fun customUniversityProfileLoadsItsDirectTimetableUrl() {
        var requestedUrl: String? = null
        val transport = object : HtmlTransport {
            override suspend fun get(url: String): String {
                requestedUrl = url
                return "<html><body>schedule</body></html>"
            }
        }
        val customProfile = ScheduleProfile(
            id = "custom-group",
            type = ScheduleProfileType.GROUP,
            name = "ИС-241",
            sourceUrl = "https://schedule.example.edu/groups/is-241.html",
            universityId = "custom-university",
        )
        val window = ScheduleWindow(LocalDate(2026, 10, 19), LocalDate(2026, 11, 1))

        val html = runSuspending { BruScheduleRemoteDataSource(transport).fetchHtml(customProfile, window) }

        assertEquals(customProfile.sourceUrl, requestedUrl)
        assertEquals("<html><body>schedule</body></html>", html)
    }

    @Test
    fun bsuGoogleSheetsProfileFetchesGvizHtmlForTheSelectedWorksheet() {
        var requestedUrl: String? = null
        val transport = object : HtmlTransport {
            override suspend fun get(url: String): String {
                requestedUrl = url
                return "<table><tr><td>schedule</td></tr></table>"
            }
        }
        val bsuProfile = ScheduleProfile(
            id = "bsu-group-1-11",
            type = ScheduleProfileType.GROUP,
            name = "1-11",
            sourceUrl = "https://docs.google.com/spreadsheets/d/example/edit?usp=sharing#gid=795046734",
            sourceKey = "1-11",
            universityId = UniversityIds.BSU,
        )
        val window = ScheduleWindow(LocalDate(2026, 10, 19), LocalDate(2026, 11, 1))

        runSuspending { BruScheduleRemoteDataSource(transport).fetchHtml(bsuProfile, window) }

        assertEquals(
            "https://docs.google.com/spreadsheets/d/example/gviz/tq?tqx=out:html&gid=795046734",
            requestedUrl,
        )
    }

    @Test
    fun bsuTeacherRefreshLoadsAllFacultyScheduleSourcesFromTheSavedProfile() {
        val sources = listOf(
            "https://docs.google.com/spreadsheets/d/econ-one/edit?gid=123",
            "https://docs.google.com/spreadsheets/d/econ-two/edit?gid=456",
        )
        val expectedRequests = listOf(
            "https://docs.google.com/spreadsheets/d/econ-one/gviz/tq?tqx=out:html&gid=123",
            "https://docs.google.com/spreadsheets/d/econ-two/gviz/tq?tqx=out:html&gid=456",
        )
        val requested = mutableListOf<String>()
        val transport = object : HtmlTransport {
            override suspend fun get(url: String): String {
                requested += url
                return when (url) {
                    expectedRequests[0] -> "<html><head><title>НЕЧЕТНАЯ</title></head><body><table><tr><td>First teacher schedule</td></tr></table></body></html>"
                    expectedRequests[1] -> "<html><head><title>ЧЕТНАЯ</title></head><body><table><tr><td>Second teacher schedule</td></tr></table></body></html>"
                    else -> error("Unexpected teacher timetable URL: $url")
                }
            }
        }
        val profile = ScheduleProfile(
            id = "bsu-teacher-ivanova",
            type = ScheduleProfileType.TEACHER,
            name = "Иванова И.И.",
            sourceUrl = sources.first(),
            sourceKey = BsuTeacherProfileSourceKey.encode("Иванова И.И.", sources),
            universityId = UniversityIds.BSU,
        )

        val html = runSuspending {
            BruScheduleRemoteDataSource(transport).fetchHtml(
                profile,
                ScheduleWindow(LocalDate(2026, 10, 19), LocalDate(2026, 10, 25)),
            )
        }

        assertEquals(expectedRequests, requested)
        assertTrue(html.contains("First teacher schedule"))
        assertTrue(html.contains("Second teacher schedule"))
        assertTrue(html.contains("data-bsu-teacher-title=\"НЕЧЕТНАЯ\""))
        assertTrue(html.contains("data-bsu-teacher-title=\"ЧЕТНАЯ\""))
        assertTrue(html.contains("data-bsu-teacher-url=\"${sources[0]}\""))
    }

    @Test
    fun bsuCatalogueSearchDiscoversSelectableGroupsFromPublicGoogleSheets() {
        val sheetUrl = "https://docs.google.com/spreadsheets/d/econ-sheet/edit?gid=123"
        val gvizUrl = "https://docs.google.com/spreadsheets/d/econ-sheet/gviz/tq?tqx=out:html&gid=123"
        val transport = object : HtmlTransport {
            override suspend fun get(url: String): String = when (url) {
                "https://economy.bsu.by/studentam/dnevnaya-forma-obucheniya/raspisanie/" ->
                    "<a href=\"$sheetUrl\">Расписание дневного отделения</a>"
                gvizUrl -> """
                    <table><tr><th>День</th><th>Дата</th><th>Время</th><th>1-11</th></tr>
                    <tr><td>Понедельник</td><td>19.10.2026</td><td>08:15–09:40</td><td>Математика</td></tr></table>
                """.trimIndent()
                else -> error("No public fixture for $url")
            }
        }

        val matches = runSuspending {
            BsuScheduleProfileCatalogRepository(transport)
                .search(UniversityIds.BSU, ScheduleProfileType.GROUP, "1-11")
        }

        assertEquals(1, matches.size)
        assertEquals("1-11", matches.single().name)
        assertEquals(sheetUrl, matches.single().sourceUrl)
        assertEquals("1-11", matches.single().sourceKey)
        assertTrue(matches.single().subtitle.contains("Экономический факультет"))
        assertEquals(UniversityIds.BSU, matches.single().universityId)
    }

    @Test
    fun bsuTeacherCatalogueSearchesFacultySheetsAndCreatesATeacherProfileWithItsSource() {
        val facultyUrl = "https://economy.bsu.by/studentam/dnevnaya-forma-obucheniya/raspisanie/"
        val sheetUrl = "https://docs.google.com/spreadsheets/d/econ-teachers/edit?gid=123"
        val gvizUrl = "https://docs.google.com/spreadsheets/d/econ-teachers/gviz/tq?tqx=out:html&gid=123"
        val transport = object : HtmlTransport {
            override suspend fun get(url: String): String = when (url) {
                facultyUrl -> "<a href=\"$sheetUrl\">Расписание занятий 1 курса</a>"
                gvizUrl -> """
                    <table>
                      <tr><th>День</th><th>Дата</th><th>Время</th><th>Группа 1-11</th><th>Преподаватель</th></tr>
                      <tr><td>Понедельник</td><td>19.10.2026</td><td>08:15-09:40</td><td>Математика</td><td>Иванова И.И.</td></tr>
                    </table>
                """.trimIndent()
                else -> error("No faculty fixture for $url")
            }
        }

        val matches = runSuspending {
            BsuScheduleProfileCatalogRepository(transport)
                .search(UniversityIds.BSU, ScheduleProfileType.TEACHER, "Иванова")
        }

        assertEquals(1, matches.size)
        assertEquals(ScheduleProfileType.TEACHER, matches.single().type)
        assertEquals("Иванова И.И.", matches.single().name)
        assertEquals(sheetUrl, matches.single().sourceUrl)
        assertEquals(listOf(sheetUrl), BsuTeacherProfileSourceKey.decode(matches.single().sourceKey)?.urls)
        assertEquals(
            "Экономический факультет",
            BsuTeacherProfileSourceKey.decode(matches.single().sourceKey)?.facultyName,
        )
        assertTrue(matches.single().subtitle.contains("Экономический факультет"))
    }

    @Test
    fun bsuDatasetDiscoveryEnumeratesTeachersFromEverySupportedFaculty() {
        val facultyUrl = "https://economy.bsu.by/studentam/dnevnaya-forma-obucheniya/raspisanie/"
        val sheetUrl = "https://docs.google.com/spreadsheets/d/econ-teachers-all/edit?gid=123"
        val gvizUrl = "https://docs.google.com/spreadsheets/d/econ-teachers-all/gviz/tq?tqx=out:html&gid=123"
        val transport = object : HtmlTransport {
            override suspend fun get(url: String): String = when (url) {
                facultyUrl -> "<a href=\"$sheetUrl\">Расписание занятий 1 курса</a>"
                gvizUrl -> """
                    <table>
                      <tr><th>День</th><th>Дата</th><th>Время</th><th>Группа 1-11</th><th>Преподаватель</th></tr>
                      <tr><td>Понедельник</td><td>19.10.2026</td><td>08:15-09:40</td><td>Математика</td><td>Иванова И.И.</td></tr>
                    </table>
                """.trimIndent()
                else -> "<html><body></body></html>"
            }
        }

        val candidates = runSuspending {
            BsuScheduleProfileCatalogRepository(transport).discoverAll(ScheduleProfileType.TEACHER)
        }

        assertEquals(1, candidates.size)
        assertEquals("Иванова И.И.", candidates.single().name)
        assertEquals(listOf(sheetUrl), BsuTeacherProfileSourceKey.decode(candidates.single().sourceKey)?.urls)
    }

    @Test
    fun bsuTeacherCatalogueMergesNameVariantsAcrossSheetsFromTheSameFaculty() {
        val facultyUrl = "https://economy.bsu.by/studentam/dnevnaya-forma-obucheniya/raspisanie/"
        val firstSheet = "https://docs.google.com/spreadsheets/d/econ-teachers-one/edit"
        val secondSheet = "https://docs.google.com/spreadsheets/d/econ-teachers-two/edit"
        val firstGviz = "https://docs.google.com/spreadsheets/d/econ-teachers-one/gviz/tq?tqx=out:html"
        val secondGviz = "https://docs.google.com/spreadsheets/d/econ-teachers-two/gviz/tq?tqx=out:html"
        val transport = object : HtmlTransport {
            override suspend fun get(url: String): String = when (url) {
                facultyUrl -> """
                    <a href="$firstSheet">Расписание занятий 1 курса</a>
                    <a href="$secondSheet">Расписание занятий 2 курса</a>
                """.trimIndent()
                firstGviz -> """
                    <table><tr><th>День</th><th>Время</th><th>Предмет</th><th>Преподаватель</th></tr>
                    <tr><td>Понедельник</td><td>08:15-09:40</td><td>Математика</td><td>Иванова Н.В.</td></tr></table>
                """.trimIndent()
                secondGviz -> """
                    <table><tr><th>День</th><th>Время</th><th>Предмет</th><th>Преподаватель</th></tr>
                    <tr><td>Вторник</td><td>09:50-11:15</td><td>Статистика</td><td>Иванова Наталья Владимировна</td></tr></table>
                """.trimIndent()
                else -> error("No faculty fixture for $url")
            }
        }

        val matches = runSuspending {
            BsuScheduleProfileCatalogRepository(transport)
                .search(UniversityIds.BSU, ScheduleProfileType.TEACHER, "Иванова")
        }

        assertEquals(1, matches.size)
        assertEquals(
            listOf(firstSheet, secondSheet),
            BsuTeacherProfileSourceKey.decode(matches.single().sourceKey)?.urls,
        )
        assertTrue(matches.single().subtitle.contains("расписаний: 2"))
    }

    @Test
    fun bsuFpmiGoogleSheetTeacherSearchUsesItsFacultySourceAndKeepsFacultyIdentity() {
        val facultyUrl = "https://fpmi.bsu.by/timetable"
        val sheetUrl = "https://docs.google.com/spreadsheets/d/fpmi-course-one/edit?gid=795046734"
        val gvizUrl = "https://docs.google.com/spreadsheets/d/fpmi-course-one/gviz/tq?tqx=out:html&gid=795046734"
        val transport = object : HtmlTransport {
            override suspend fun get(url: String): String = when (url) {
                facultyUrl -> "<a href=\"$sheetUrl\">Расписание занятий на 1 курс</a>"
                gvizUrl -> """
                    <table>
                      <tr><th>1 курс день недели понедельник</th><th>время занятий</th>
                        <th>1 группа А</th><th>Б</th><th>2 группа А</th><th>Б</th></tr>
                      <tr><td></td><td>08:15-09:40</td>
                        <td>Математический анализ<br>Петров П.П.</td>
                        <td>Физика<br>Иванова И.И.</td>
                        <td>История<br>Сидоров С.С.</td><td></td></tr>
                    </table>
                """.trimIndent()
                else -> error("No FPMI fixture for $url")
            }
        }

        val matches = runSuspending {
            BsuScheduleProfileCatalogRepository(transport)
                .search(UniversityIds.BSU, ScheduleProfileType.TEACHER, "Иванова")
        }

        assertEquals(1, matches.size)
        assertEquals(ScheduleProfileType.TEACHER, matches.single().type)
        assertEquals("Иванова И.И.", matches.single().name)
        assertEquals(sheetUrl, matches.single().sourceUrl)
        assertEquals("ФПМИ", BsuTeacherProfileSourceKey.decode(matches.single().sourceKey)?.facultyName)
    }

    @Test
    fun bsuCatalogueSearchFollowsFacultyGroupLinksToHtmlTimetables() {
        val facultyUrl = "https://mmf.bsu.by/ru/raspisanie-zanyatij/"
        val groupUrl = "https://mmf.bsu.by/ru/raspisanie-zanyatij/dnevnoe-otdelenie/1-kurs/1-gruppa/"
        val transport = object : HtmlTransport {
            override suspend fun get(url: String): String = when (url) {
                facultyUrl -> "<a href=\"/ru/raspisanie-zanyatij/dnevnoe-otdelenie/1-kurs/1-gruppa/\">1 группа А</a>"
                groupUrl -> """
                    <table><tr><th>День</th><th>Время</th><th>Предмет</th></tr>
                    <tr><td>Понедельник</td><td>08:30–09:50</td><td>Математика</td></tr></table>
                """.trimIndent()
                else -> error("No public fixture for $url")
            }
        }

        val matches = runSuspending {
            BsuScheduleProfileCatalogRepository(transport)
                .search(UniversityIds.BSU, ScheduleProfileType.GROUP, "1 группа А")
        }

        assertEquals(1, matches.size)
        assertEquals("1 группа А", matches.single().name)
        assertEquals(groupUrl, matches.single().sourceUrl)
        assertEquals("Механико-математический факультет · HTML", matches.single().subtitle)
    }

    @Test
    fun groupRefreshRecoversFromStaleFacultyUrlAndResolvesTheMatchingFaculty() {
        val parser = BruScheduleDirectoryParser()
        val staleFacultyInput = "https://rasp.bru.by/full_time/Факультет управления и инноваций/index.html"
        val managementUrl = parser.absolutize(BruScheduleDirectoryParser.ROOT, staleFacultyInput)
        val autoUrl = "https://rasp.bru.by/full_time/auto/index.html"
        val scheduleUrl = "https://rasp.bru.by/full_time/auto/%D0%9C%D0%9F%D0%A0-261_weeks_20261019_20261101.html"
        val rootHtml = """
            <a href="$managementUrl">Факультет управления и инноваций</a>
            <a href="$autoUrl">Автомеханический факультет</a>
        """.trimIndent()
        val autoHtml = """
            <h1>🏛️ Автомеханический факультет</h1>
            <a class="group-link" href="$scheduleUrl"><strong>МПР-261</strong></a>
        """.trimIndent()
        val requestedUrls = mutableListOf<String>()
        val transport = object : HtmlTransport {
            override suspend fun get(url: String): String {
                requestedUrls += url
                return when (url) {
                    managementUrl -> error("HTTP 400: stale faculty page")
                    BruScheduleDirectoryParser.FULL_TIME_INDEX -> rootHtml
                    autoUrl -> autoHtml
                    scheduleUrl -> "<table class=\"schedule-table\"></table>"
                    else -> error("Unexpected test URL: $url")
                }
            }
        }
        val staleProfile = profile.copy(sourceUrl = staleFacultyInput)
        val window = ScheduleWindow(LocalDate(2026, 10, 19), LocalDate(2026, 11, 1))

        val html = runSuspending {
            BruScheduleRemoteDataSource(transport).fetchHtml(staleProfile, window)
        }

        assertTrue(html.contains("schedule-table"))
        assertEquals(scheduleUrl, requestedUrls.last())
        assertEquals(1, requestedUrls.count { it == managementUrl })
        assertFalse(BruScheduleDirectoryParser.PART_TIME_INDEX in requestedUrls)
    }

    @Test
    fun directoryResolverPrefersTheRollingWindowThatCoversTodayAndNextWeek() {
        val html = """
            <a href="https://rasp.bru.by/full_time/faculty/МПР-261_weeks_20261005_20261018.html">МПР-261</a>
            <a href="https://rasp.bru.by/full_time/faculty/МПР-261_weeks_20261019_20261101.html">МПР-261</a>
        """.trimIndent()
        val window = ScheduleWindow(LocalDate(2026, 10, 19), LocalDate(2026, 11, 1))

        val url = BruScheduleDirectoryParser().scheduleUrlFromIndex(
            html = html,
            pageUrl = "https://rasp.bru.by/full_time/faculty/index.html",
            profile = profile,
            window = window,
        )

        assertTrue(url.orEmpty().contains("20261019_20261101"))
    }

    @Test
    fun directoryResolverRejectsAGroupCodeThatOnlyMatchesAsAPrefix() {
        val html = """
            <a class="group-link" href="https://rasp.bru.by/full_time/auto/МПР-261-1_weeks_20261019_20261101.html">
                <strong>МПР-261-1</strong>
            </a>
        """.trimIndent()

        val url = BruScheduleDirectoryParser().scheduleUrlFromIndex(
            html = html,
            pageUrl = "https://rasp.bru.by/full_time/auto/index.html",
            profile = profile,
            window = ScheduleWindow(LocalDate(2026, 10, 19), LocalDate(2026, 11, 1)),
        )

        assertNull(url)
    }

    @Test
    fun invalidMarkupFailsClosedInsteadOfProducingAnEmptySchedule() {
        assertFailsWith<IllegalArgumentException> {
            BruScheduleScraper().parse(profile, "<html><body>maintenance</body></html>", LocalDate(2026, 10, 19))
        }
    }

    @Test
    fun manualOverrideWinsOverWebsiteAndIsNotLostWhenSubjectChanges() {
        val manual = lesson(
            id = "local-lesson",
            sourceKey = "group-mpr-261|2026-10-19|1|08:30-10:05|0",
            subject = "Моя правка по информатике",
            isCustom = true,
            version = 2,
        )
        val web = lesson(
            id = "web-new-id",
            sourceKey = manual.sourceKey,
            subject = "Название на сайте",
            isCustom = false,
            version = 1,
        )

        val merged = ScheduleMergeStrategy.merge(existing = listOf(manual), incoming = listOf(web))

        assertEquals(1, merged.size)
        assertEquals("Моя правка по информатике", merged.single().subjectName)
        assertEquals("local-lesson", merged.single().id)
        assertTrue(merged.single().isCustom)
        assertEquals(2, merged.single().version)
    }

    @Test
    fun lessonFeedbackFollowsTheSameWebsiteClassWhenItsSourceIdChanges() {
        val previous = lesson(
            id = "web-old",
            sourceKey = "slot-old",
            subject = "Физика",
            isCustom = false,
        ).copy(notes = "Взять линейку", rating = 4, stickers = listOf("🔥", "😀"))
        val refreshed = previous.copy(id = "web-new", sourceKey = "slot-new", version = 1, notes = "", rating = null, stickers = emptyList())

        val merged = ScheduleMergeStrategy.merge(listOf(previous), listOf(refreshed))

        assertEquals("Взять линейку", merged.single().notes)
        assertEquals(4, merged.single().rating)
        assertEquals(listOf("🔥", "😀"), merged.single().stickers)
    }

    @Test
    fun lessonFeedbackFollowsWebsiteSourceKeyWhenTheSubjectChanges() {
        val previous = lesson(id = "web-old", sourceKey = "slot-stable", subject = "Физика", isCustom = false)
            .copy(rating = 3, stickers = listOf("💪"))
        val refreshed = previous.copy(
            id = "web-new",
            subjectName = "Физика · новая тема",
            version = 1,
            rating = null,
            stickers = emptyList(),
        )

        val merged = ScheduleMergeStrategy.merge(listOf(previous), listOf(refreshed))

        assertEquals(3, merged.single().rating)
        assertEquals(listOf("💪"), merged.single().stickers)
    }

    @Test
    fun rollbackRestoresSnapshotVersionsButKeepsEditsMadeAfterTheSnapshot() {
        val oldOverriddenWeb = lesson(id = "web-1", sourceKey = "slot-1", subject = "Старая версия", isCustom = false, version = 7)
        val oldUntouchedWeb = lesson(id = "web-2", sourceKey = "slot-2", subject = "Физика", isCustom = false, version = 6)
        val currentOverride = oldOverriddenWeb.copy(subjectName = "Моя правка", isCustom = true, version = 8)
        val currentUntouchedWeb = oldUntouchedWeb.copy(
            id = "web-2-new",
            subjectName = "Физика · новая тема",
            notes = "Подготовить отчёт",
            rating = 5,
            stickers = listOf("🎯"),
        )
        val customAddedAfterSnapshot = lesson(
            id = "after-snapshot",
            sourceKey = "manual:after-snapshot",
            subject = "Новая ручная пара",
            isCustom = true,
        )

        val restored = ScheduleMergeStrategy.restoreSnapshot(
            snapshot = listOf(oldOverriddenWeb, oldUntouchedWeb),
            current = listOf(currentOverride, currentUntouchedWeb, customAddedAfterSnapshot),
        )

        assertEquals("Моя правка", restored.first { it.id == "web-1" }.subjectName)
        assertTrue(restored.first { it.id == "web-1" }.isCustom)
        assertEquals(6, restored.first { it.id == "web-2" }.version)
        assertEquals("Подготовить отчёт", restored.first { it.id == "web-2" }.notes)
        assertEquals(5, restored.first { it.id == "web-2" }.rating)
        assertEquals(listOf("🎯"), restored.first { it.id == "web-2" }.stickers)
        assertEquals("Новая ручная пара", restored.first { it.id == "after-snapshot" }.subjectName)
    }

    @Test
    fun lessonStickerCodecKeepsOnlyUniqueSupportedStickers() {
        val stickers = listOf("🔥", "😀", "🔥", "unsupported")

        val encoded = LessonStickerCodec.encode(stickers)

        assertEquals(listOf("🔥", "😀"), LessonStickerCodec.decode(encoded))
        assertEquals(emptyList<String>(), LessonStickerCodec.decode("unsupported"))
    }

    @Test
    fun websiteVersionIncrementsAndManualOnlyLessonsRemain() {
        val oldWeb = lesson(id = "web-1", sourceKey = "slot-A", subject = "Физика", isCustom = false, version = 4)
        val newWeb = oldWeb.copy(subjectName = "Физика · новая тема", version = 1)
        val customOnly = lesson(id = "my-extra", sourceKey = "manual:my-extra", subject = "Консультация", isCustom = true)

        val merged = ScheduleMergeStrategy.merge(listOf(oldWeb, customOnly), listOf(newWeb))

        assertEquals(2, merged.size)
        assertEquals(5, merged.first { it.id == "web-1" }.version)
        assertEquals("Консультация", merged.first { it.id == "my-extra" }.subjectName)
    }

    private fun <T> runSuspending(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) {
                outcome = result
            }
        })
        return outcome?.getOrThrow() ?: error("Suspend test block did not complete synchronously")
    }

    private fun lesson(
        id: String,
        sourceKey: String,
        subject: String,
        isCustom: Boolean,
        version: Int = 1,
    ) = Lesson(
        id = id,
        profileId = profile.id,
        subjectName = subject,
        teacherName = "Преподаватель",
        classroom = "ауд. 1",
        startTime = "08:30",
        endTime = "10:05",
        type = LessonType.LECTURE,
        colorHex = LessonTypePalette.LECTURE,
        isCustom = isCustom,
        version = version,
        lessonDate = "2026-10-19",
        dayOfWeek = 1,
        slotNumber = 1,
        sourceKey = sourceKey,
    )

    private fun sampleWeekHtml() = """
        <!doctype html>
        <html lang="ru"><head>
          <style>
            .lesson-lecture { background: #3788d8; }
            .lesson-lab { background-color: #e0a800; }
          </style>
        </head><body>
          <h2>МПР-261</h2>
          <div class="text-muted">19.10.2026 — 01.11.2026 (2 недели)</div>
          <div class="week-header">Неделя 1: 19.10 – 25.10.2026</div>
          <table class="schedule-table">
            <thead><tr>
              <th class="time-col">Время</th>
              <th>Пн<br><small>19.10</small></th>
              <th>Вт<br><small>20.10</small></th>
            </tr></thead>
            <tbody><tr>
              <td class="time-col"><div>1 пара</div><div>08:30–10:05</div></td>
              <td><div class="lesson-card lesson-lecture"><div class="title">Информатика</div><div class="info">👤 Заровчатская Е.В<br>📍 1 корпус, ауд. 530<br>Лекция</div></div></td>
              <td><div class="lesson-card lesson-lab"><div class="title">Программирование</div><div class="info">👤 Сидоров А.А<br>📍 2 корпус, ауд. 214<br>Лабораторная</div></div></td>
            </tr></tbody>
          </table>
        </body></html>
    """.trimIndent()
}
