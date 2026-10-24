package com.example.bruschedule

import com.example.bruschedule.data.remote.BruScheduleRemoteDataSource
import com.example.bruschedule.data.remote.BsuScheduleProfileCatalogRepository
import com.example.bruschedule.data.remote.HtmlTransport
import com.example.bruschedule.data.scraper.BsuBiologyScheduleHtmlParser
import com.example.bruschedule.data.scraper.BsuBiologyTeacherPage
import com.example.bruschedule.data.scraper.BsuScheduleHtmlParser
import com.example.bruschedule.data.scraper.BsuTeacherProfileSourceKey
import com.example.bruschedule.data.scraper.UniversityScheduleHtmlParser
import com.example.bruschedule.domain.model.ScheduleErrorInfo
import com.example.bruschedule.domain.model.ScheduleProfile
import com.example.bruschedule.domain.model.ScheduleProfileType
import com.example.bruschedule.domain.model.ScheduleWindow
import com.example.bruschedule.domain.model.UniversityIds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

class BsuBiologyScheduleParserTest {
    private val profile = ScheduleProfile(
        id = "bsu-bio-group-42",
        type = ScheduleProfileType.GROUP,
        name = "Группа 42",
        sourceUrl = "https://bio.bsu.by/schedule/?study_mode=%D0%94%D0%BD%D0%B5%D0%B2%D0%BD%D0%B0%D1%8F&course=3&week_date=2026-10-19",
        sourceKey = "${BsuBiologyScheduleHtmlParser.BIO_GROUP_SOURCE_KEY_PREFIX}48",
        universityId = UniversityIds.BSU,
    )
    private val groupPageProfile = ScheduleProfile(
        id = "bsu-bio-group-42-new",
        type = ScheduleProfileType.GROUP,
        name = "Группа 42",
        sourceUrl = "https://bsu.bio/schedule.php?study=d&course=3&group=42&date=2026-10-23",
        sourceKey = "${BsuBiologyScheduleHtmlParser.BIO_GROUP_NUMBER_SOURCE_KEY_PREFIX}42",
        universityId = UniversityIds.BSU,
    )

    @Test
    fun biologyParserSelectsTheRequestedGroupByDataGroupIdAndReadsSlotMetadata() {
        val parsed = BsuScheduleHtmlParser().parse(profile, biologyWeekHtml("2026-10-19", "Биологическая статистика (ЛК)"), LocalDate(2026, 10, 19))

        assertEquals(1, parsed.size)
        assertEquals("Биологическая статистика (ЛК)", parsed.single().subjectName)
        assertEquals("Нестерова Оксана Львовна", parsed.single().teacherName)
        assertEquals("ауд. 3", parsed.single().classroom)
        assertEquals("2026-10-19", parsed.single().lessonDate)
        assertEquals(1, parsed.single().dayOfWeek)
        assertEquals(1, parsed.single().slotNumber)
        assertEquals("09:00", parsed.single().startTime)
        assertEquals("10:25", parsed.single().endTime)
        assertEquals(com.example.bruschedule.domain.model.LessonType.LECTURE, parsed.single().type)
        assertTrue(parsed.single().sourceKey.contains("bio:48"))
    }

    @Test
    fun biologyGroupPickerMapsVisibleGroupNumbersToServerGroupIds() {
        val groups = BsuBiologyScheduleHtmlParser().discoverGroups(biologyWeekHtml("2026-10-19", "Математика"))

        assertEquals(listOf("47", "48"), groups.map { it.id })
        assertEquals(listOf("Группа 41", "Группа 42"), groups.map { it.name })
        assertEquals("Биология 6-05-0511-01", groups.last().programme)
    }

    @Test
    fun serverRenderedGroupPageParsesWeekDaysSlotsSubgroupsTeachersRoomsAndNotes() {
        val parser = BsuBiologyScheduleHtmlParser()
        val html = biologyGroupPageHtml()

        assertTrue(parser.hasScheduleGrid(html))
        val lessons = BsuScheduleHtmlParser().parse(groupPageProfile, html, LocalDate(2026, 10, 19))

        assertEquals(3, lessons.size)
        assertEquals("Физическая культура", lessons[0].subjectName)
        assertEquals("Микитченко Надежда Владимировна", lessons[0].teacherName)
        assertEquals("Курчатова 10", lessons[0].classroom)
        assertEquals("10:35", lessons[0].startTime)
        assertEquals("12:00", lessons[0].endTime)
        assertEquals("2026-10-19", lessons[0].lessonDate)
        assertEquals(2, lessons[0].slotNumber)
        assertEquals(com.example.bruschedule.domain.model.LessonType.PRACTICE, lessons[0].type)
        assertTrue(lessons[0].notes.contains("С 26.10"))
        assertEquals("Микробиология · подгруппа 1", lessons[1].subjectName)
        assertEquals("Гринева Ирина Александровна", lessons[1].teacherName)
        assertEquals("301 · Курчатова 10", lessons[1].classroom)
        assertEquals("Микробиология · подгруппа 2", lessons[2].subjectName)
        assertEquals("Комар Елена Ивановна", lessons[2].teacherName)

        val migratedLegacyProfileLessons = parser.parse(profile, html, LocalDate(2026, 10, 19))
        assertEquals(lessons.map { it.subjectName }, migratedLegacyProfileLessons.map { it.subjectName })
    }

    @Test
    fun biologyTeacherProfileMergesMatchingLessonsAcrossFacultyGroupPages() {
        val sourceUrls = listOf(
            "https://bsu.bio/schedule.php?study=d&course=1&group=41&date=2026-10-19",
            "https://bsu.bio/schedule.php?study=d&course=1&group=42&date=2026-10-19",
        )
        val teacherName = "Микитченко Надежда Владимировна"
        val teacherProfile = ScheduleProfile(
            id = "bsu-bio-teacher-mikitchenko",
            type = ScheduleProfileType.TEACHER,
            name = teacherName,
            sourceUrl = sourceUrls.first(),
            sourceKey = BsuTeacherProfileSourceKey.encode(teacherName, sourceUrls),
            universityId = UniversityIds.BSU,
        )
        val parser = BsuBiologyScheduleHtmlParser()
        val html = parser.combineTeacherPages(
            listOf(
                BsuBiologyTeacherPage("41", LocalDate(2026, 10, 19), biologyGroupPageHtml()),
                BsuBiologyTeacherPage("42", LocalDate(2026, 10, 19), biologyGroupPageHtml()),
            ),
        )

        val lessons = parser.parse(teacherProfile, html, LocalDate(2026, 10, 19))

        assertEquals(2, lessons.size)
        assertTrue(lessons.all { it.teacherName == teacherName })
        assertTrue(lessons.map { it.sourceKey }.distinct().size == 2)
        assertTrue(lessons.any { it.sourceKey.contains("bio:41:") })
        assertTrue(lessons.any { it.sourceKey.contains("bio:42:") })
    }

    @Test
    fun biologyParserReadsWeekRangesThatCrossAMonthBoundary() {
        val html = biologyGroupPageHtml().replace("19–25 октября 2026", "26 октября – 1 ноября 2026")

        val lessons = BsuBiologyScheduleHtmlParser().parse(groupPageProfile, html, LocalDate(2026, 10, 26))

        assertEquals("2026-10-26", lessons.first().lessonDate)
        assertEquals("2026-10-29", lessons.last().lessonDate)
    }

    @Test
    fun multiWeekBiologyResponseKeepsTheServerDatesAndDoesNotGetExpandedAsAWeeklyTemplate() {
        val parser = BsuBiologyScheduleHtmlParser()
        val response = parser.combineWeeklyPages(
            listOf(
                LocalDate(2026, 10, 19) to biologyWeekHtml("2026-10-19", "Математика"),
                LocalDate(2026, 10, 26) to biologyWeekHtml("2026-10-26", "Биология"),
            ),
        )

        val lessons = UniversityScheduleHtmlParser().parse(profile, response, LocalDate(2026, 10, 19))

        assertEquals(listOf("2026-10-19", "2026-10-26"), lessons.map { it.lessonDate })
        assertEquals(listOf("Математика", "Биология"), lessons.map { it.subjectName })
        assertFalse(UniversityScheduleHtmlParser().hasWeeklyScheduleTemplate(profile, response))
        assertTrue(UniversityScheduleHtmlParser().hasWeeklyScheduleTemplate(profile, biologyWeekHtml("2026-10-19", "Математика")))
    }

    @Test
    fun biologyRemoteMigratesLegacyProfilesAndLoadsEachWeekThroughTheSemesterCoverageHorizon() = runTest {
        val requested = mutableListOf<String>()
        val transport = object : HtmlTransport {
            override suspend fun get(url: String): String {
                requested += url
                return "<html><body>$url</body></html>"
            }
        }
        val window = ScheduleWindow(
            startDate = LocalDate(2026, 10, 19),
            endDate = LocalDate(2026, 11, 1),
            semesterCoverageEndDate = LocalDate(2026, 11, 5),
        )

        val combined = BruScheduleRemoteDataSource(transport).fetchHtml(profile, window)

        val weekRequests = requested.filter { "date=" in it }
        assertEquals(
            listOf("2026-10-19", "2026-10-26", "2026-11-02"),
            weekRequests.map { it.substringAfter("date=").substringBefore('&') }.sorted(),
        )
        assertTrue(weekRequests.all { it.startsWith("https://bsu.bio/schedule.php?") })
        assertTrue(weekRequests.all { it.contains("study=d") && it.contains("course=3") && it.contains("group=42") })
        assertTrue(requested.contains(BsuBiologyScheduleHtmlParser.BIOLOGY_SCHEDULE_URL))
        assertTrue(requested.none { "week_date=" in it || "bio.bsu.by" in it })
        assertTrue(combined.contains("BSU-BIO-WEEK:2026-10-19"))
        assertTrue(combined.contains("BSU-BIO-WEEK:2026-10-26"))
        assertTrue(combined.contains("BSU-BIO-WEEK:2026-11-02"))
    }

    @Test
    fun biologyRemoteStopsAfterTheFirstRejectedWeekInsteadOfFetchingTheWholeSemester() = runTest {
        val requested = mutableListOf<String>()
        val transport = object : HtmlTransport {
            override suspend fun get(url: String): String {
                requested += url
                if (url.contains("date=2026-10-19")) error("bsu.bio ответил кодом 406")
                return "<html><body>$url</body></html>"
            }
        }
        val window = ScheduleWindow(
            startDate = LocalDate(2026, 10, 19),
            endDate = LocalDate(2026, 11, 1),
            semesterCoverageEndDate = LocalDate(2027, 1, 31),
        )

        val failure = checkNotNull(runCatching {
            BruScheduleRemoteDataSource(transport).fetchHtml(groupPageProfile, window)
        }.exceptionOrNull())

        assertTrue(failure.message.orEmpty().contains("406"))
        assertTrue(ScheduleErrorInfo.fromThrowable(failure).code.contains("HTTP406"))
        assertTrue(requested.count { "date=" in it } <= 6)
    }

    @Test
    fun biologyGroupPageRemoteUsesTheDateParameterForEveryWeek() = runTest {
        val requested = mutableListOf<String>()
        val transport = object : HtmlTransport {
            override suspend fun get(url: String): String {
                requested += url
                return "<html><body>$url</body></html>"
            }
        }
        val window = ScheduleWindow(
            startDate = LocalDate(2026, 10, 19),
            endDate = LocalDate(2026, 11, 1),
            semesterCoverageEndDate = LocalDate(2026, 11, 5),
        )

        val combined = BruScheduleRemoteDataSource(transport).fetchHtml(groupPageProfile, window)

        val weekRequests = requested.filter { "date=" in it }
        assertEquals(
            listOf("2026-10-19", "2026-10-26", "2026-11-02"),
            weekRequests.map { it.substringAfter("date=").substringBefore('&') }.sorted(),
        )
        assertTrue(weekRequests.all { it.contains("study=d") && it.contains("group=42") })
        assertTrue(requested.contains(BsuBiologyScheduleHtmlParser.BIOLOGY_SCHEDULE_URL))
        assertTrue(combined.contains("BSU-BIO-WEEK:2026-10-19"))
        assertTrue(combined.contains("BSU-BIO-WEEK:2026-10-26"))
        assertTrue(combined.contains("BSU-BIO-WEEK:2026-11-02"))
    }

    @Test
    fun biologyTeacherRefreshLoadsEveryMatchingGroupSourceForTheRequestedWeeks() = runTest {
        val urls = listOf(
            "https://bsu.bio/schedule.php?study=d&course=1&group=41&date=2026-10-23",
            "https://bsu.bio/schedule.php?study=d&course=1&group=42&date=2026-10-23",
        )
        val requested = mutableListOf<String>()
        val transport = object : HtmlTransport {
            override suspend fun get(url: String): String {
                requested += url
                return if (url.startsWith("https://bsu.bio/schedule.php?study=d&course=1&group=")) {
                    biologyGroupPageHtml()
                } else {
                    error("Unexpected Biology teacher URL: $url")
                }
            }
        }
        val teacherName = "Микитченко Надежда Владимировна"
        val teacherProfile = ScheduleProfile(
            id = "bsu-bio-teacher-refresh",
            type = ScheduleProfileType.TEACHER,
            name = teacherName,
            sourceUrl = urls.first(),
            sourceKey = BsuTeacherProfileSourceKey.encode(teacherName, urls),
            universityId = UniversityIds.BSU,
        )
        val window = ScheduleWindow(LocalDate(2026, 10, 19), LocalDate(2026, 10, 25))

        val combined = BruScheduleRemoteDataSource(transport).fetchHtml(teacherProfile, window)
        val lessons = BsuBiologyScheduleHtmlParser().parse(teacherProfile, combined, LocalDate(2026, 10, 19))

        val groupRequests = requested.filter { "group=" in it }
        assertEquals(2, groupRequests.size)
        assertTrue(groupRequests.all { it.contains("date=2026-10-19") })
        assertTrue(requested.contains(BsuBiologyScheduleHtmlParser.BIOLOGY_SCHEDULE_URL))
        assertTrue(combined.contains("BSU-BIO-TEACHER-PAGE:41:2026-10-19"))
        assertTrue(combined.contains("BSU-BIO-TEACHER-PAGE:42:2026-10-19"))
        assertEquals(2, lessons.size)
        assertTrue(lessons.all { it.teacherName == teacherName })
    }

    @Test
    fun biologyCatalogueSearchesTheNewStudyModeAndCourseFilters() {
        val indexUrl = "https://bsu.bio/schedule.php?study=d&course=3&date=2026-10-19"
        val expectedScheduleUrl = "https://bsu.bio/schedule.php?study=d&course=3&group=42&date=2026-10-19"
        val requested = mutableListOf<String>()
        val transport = object : HtmlTransport {
            override suspend fun get(url: String): String {
                requested += url
                return if (url == indexUrl) biologyGroupIndexHtml() else error("No fixture for $url")
            }
        }
        val fixedClock = object : Clock {
            override fun now(): Instant = Instant.parse("2026-10-23T09:00:00Z")
        }
        val progressUpdates = mutableListOf<List<com.example.bruschedule.domain.model.ProfileCandidate>>()

        val matches = runSuspending {
            BsuScheduleProfileCatalogRepository(transport, clock = fixedClock)
                .search(UniversityIds.BSU, ScheduleProfileType.GROUP, "42") { progressUpdates += it }
        }

        assertEquals(1, matches.size)
        assertEquals("Группа 42", matches.single().name)
        assertEquals(expectedScheduleUrl, matches.single().sourceUrl)
        assertEquals("bio-group-number:42", matches.single().sourceKey)
        assertTrue(matches.single().subtitle.contains("Дневная"))
        assertTrue(matches.single().subtitle.contains("3 курс"))
        assertTrue(requested.contains(indexUrl))
        assertEquals(15, requested.count { "?study=" in it })
        assertTrue(requested.none { "study=t" in it })
        assertTrue(progressUpdates.any { update -> update.any { it.sourceUrl == expectedScheduleUrl } })
    }

    @Test
    fun biologyTeacherSearchScansEachCurrentGroupPageAndCombinesSourcesByTeacher() = runTest {
        val indexUrl = "https://bsu.bio/schedule.php?study=d&course=3&date=2026-10-19"
        val groupUrls = listOf(
            "https://bsu.bio/schedule.php?study=d&course=3&group=41&date=2026-10-19",
            "https://bsu.bio/schedule.php?study=d&course=3&group=42&date=2026-10-19",
        )
        val requested = mutableListOf<String>()
        val transport = object : HtmlTransport {
            override suspend fun get(url: String): String {
                requested += url
                return when {
                    url == indexUrl -> biologyGroupIndexHtml()
                    url in groupUrls -> biologyGroupPageHtml()
                    "?study=" in url -> "<html><body>No timetable for this course</body></html>"
                    else -> error("No Biology teacher fixture for $url")
                }
            }
        }
        val fixedClock = object : Clock {
            override fun now(): Instant = Instant.parse("2026-10-23T09:00:00Z")
        }

        val matches = BsuScheduleProfileCatalogRepository(transport, clock = fixedClock)
            .search(UniversityIds.BSU, ScheduleProfileType.TEACHER, "Микитченко")

        assertEquals(1, matches.size)
        assertEquals(ScheduleProfileType.TEACHER, matches.single().type)
        assertEquals("Микитченко Надежда Владимировна", matches.single().name)
        assertEquals(2, BsuTeacherProfileSourceKey.decode(matches.single().sourceKey)?.urls?.size)
        assertEquals(groupUrls.first(), matches.single().sourceUrl)
        assertTrue(matches.single().subtitle.contains("расписаний: 2"))
        assertTrue(groupUrls.all(requested::contains))
    }

    @Test
    fun biologyCatalogueFindsASingleSymbolGroupWithoutMatchingLongerCodes() = runTest {
        val indexUrl = "https://bsu.bio/schedule.php?study=d&course=1&date=2026-10-19"
        val requested = mutableListOf<String>()
        val transport = object : HtmlTransport {
            override suspend fun get(url: String): String {
                requested += url
                return when (url) {
                    BsuBiologyScheduleHtmlParser.BIOLOGY_SCHEDULE_URL -> "<html><body>catalogue</body></html>"
                    indexUrl -> """
                        <html><body>
                          <a href="/schedule.php?study=d&amp;course=1&amp;group=41&amp;date=2026-10-19">Группа 41</a>
                          <a href="/schedule.php?study=d&amp;course=1&amp;group=1&amp;date=2026-10-19">Группа 1</a>
                        </body></html>
                    """.trimIndent()
                    else -> "<html><body>no matching groups</body></html>"
                }
            }
        }
        val fixedClock = object : Clock {
            override fun now(): Instant = Instant.parse("2026-10-23T09:00:00Z")
        }

        val matches = BsuScheduleProfileCatalogRepository(transport, clock = fixedClock)
            .search(UniversityIds.BSU, ScheduleProfileType.GROUP, "1") { }

        assertEquals(1, matches.size)
        assertEquals("Группа 1", matches.single().name)
        assertEquals("bio-group-number:1", matches.single().sourceKey)
        assertEquals(
            "https://bsu.bio/schedule.php?study=d&course=1&group=1&date=2026-10-19",
            matches.single().sourceUrl,
        )
        assertTrue(requested.contains(indexUrl))
        assertTrue(requested.all { it.startsWith(BsuBiologyScheduleHtmlParser.BIOLOGY_SCHEDULE_URL) })

        val candidate = matches.single()
        val profile = ScheduleProfile(
            id = "bsu-bio-single-symbol-1",
            type = ScheduleProfileType.GROUP,
            name = candidate.name,
            sourceUrl = candidate.sourceUrl,
            sourceKey = candidate.sourceKey,
            universityId = UniversityIds.BSU,
        )
        val lessons = BsuBiologyScheduleHtmlParser().parse(
            profile,
            """
                <div class="schedule-heading"><h2>Группа №1 — Первый курс</h2><div class="week">19–25 октября 2026</div></div>
                <section class="day"><h2 class="day-title">Понедельник</h2>
                  <div class="lesson"><div class="lesson-time"><div class="lesson-number">1 пара</div><div class="lesson-hours">09:00-10:25</div></div>
                    <div class="lesson-main"><div class="lesson-entry">
                      <div class="discipline-line lesson-type-practice"><span class="discipline">Физическая культура</span><span class="lesson-type">Практика</span></div>
                    </div></div>
                  </div>
                </section>
            """.trimIndent(),
            LocalDate(2026, 10, 19),
        )
        assertEquals("Физическая культура", lessons.single().subjectName)
        assertTrue(lessons.single().sourceKey.contains("bio:1:"))
    }

    @Test
    fun biologyCataloguePublishesMatchingGroupsBeforeTheRemainingCoursePagesFinish() = runTest {
        val indexUrl = "https://bsu.bio/schedule.php?study=d&course=3&date=2026-10-19"
        val releaseRemainingPages = CompletableDeferred<Unit>()
        val firstMatch = CompletableDeferred<List<com.example.bruschedule.domain.model.ProfileCandidate>>()
        val transport = object : HtmlTransport {
            override suspend fun get(url: String): String = when {
                url == BsuBiologyScheduleHtmlParser.BIOLOGY_SCHEDULE_URL -> "<html><body>catalogue</body></html>"
                url == indexUrl -> biologyGroupIndexHtml()
                url.startsWith(BsuBiologyScheduleHtmlParser.BIOLOGY_SCHEDULE_URL) -> {
                    releaseRemainingPages.await()
                    "<html><body>other course</body></html>"
                }
                else -> "<html><body>no links</body></html>"
            }
        }
        val fixedClock = object : Clock {
            override fun now(): Instant = Instant.parse("2026-10-23T09:00:00Z")
        }
        val searchJob = async {
            BsuScheduleProfileCatalogRepository(transport, clock = fixedClock)
                .search(UniversityIds.BSU, ScheduleProfileType.GROUP, "42") { candidates ->
                    if (candidates.any { it.sourceKey == "bio-group-number:42" }) firstMatch.complete(candidates)
                }
        }

        try {
            val earlyCandidates = withTimeout(5_000) { firstMatch.await() }
            assertTrue(earlyCandidates.any { it.sourceKey == "bio-group-number:42" })
            assertTrue(searchJob.isActive)
        } finally {
            releaseRemainingPages.complete(Unit)
        }

        assertEquals(1, searchJob.await().size)
    }

    private fun biologyGroupIndexHtml(): String = """
        <html><body>
          <a href="/schedule.php?study=d&amp;course=3&amp;group=41&amp;date=2026-10-19" title="Экология">Группа 41</a>
          <a href="/schedule.php?study=d&amp;course=3&amp;group=42&amp;date=2026-10-19" title="Биология 6-05-0511-01">Группа 42</a>
        </body></html>
    """.trimIndent()

    private fun biologyGroupPageHtml(): String = """
        <html><body>
          <h2>Группа №42 — Биология 6-05-0511-01</h2>
          <p>19–25 октября 2026</p>
          <h2>Понедельник</h2>
          <div>2 пара</div><div>10:35-12:00</div>
          <div>Физическая культура</div><div>/</div><div>Практика</div>
          <div>Преподаватель:</div><div>Микитченко Надежда Владимировна</div>
          <div>Ауд. —</div><div>(Курчатова 10)</div>
          <div>⚠️</div><div>С 26.10</div>
          <h2>Четверг</h2>
          <div>1 пара</div><div>09:00-10:25</div>
          <div>Подгруппа 1</div><div>Микробиология</div><div>/</div><div>Лабораторная</div>
          <div>Преподаватель:</div><div>Гринева Ирина Александровна</div>
          <div>Ауд.</div><div><strong>301</strong></div><div>(Курчатова 10)</div>
          <div>Подгруппа 2</div><div>Микробиология</div><div>/</div><div>Лабораторная</div>
          <div>Преподаватель:</div><div>Комар Елена Ивановна</div>
          <div>Ауд.</div><div><strong>303</strong></div><div>(Курчатова 10)</div>
        </body></html>
    """.trimIndent()

    private fun biologyWeekHtml(weekStart: String, subject: String): String {
        val parts = weekStart.split('-')
        val displayDate = "${parts[2]}.${parts[1]}.${parts[0]}"
        val endDate = LocalDate.parse(weekStart).plus(kotlinx.datetime.DatePeriod(days = 6))
        val endDisplay = "${endDate.dayOfMonth.toString().padStart(2, '0')}.${endDate.monthNumber.toString().padStart(2, '0')}.${endDate.year}"
        return """
            <div id="week-info">$displayDate — $endDisplay</div>
            <table class="grid-table">
              <thead id="schedule-header"><tr>
                <th class="day-header">День</th><th class="time-header">Время</th>
                <th class="group-header"><span class="group-header-number">Группа 41</span><span class="group-header-name">Экология</span></th>
                <th class="group-header"><span class="group-header-number">Группа 42</span><span class="group-header-name">Биология 6-05-0511-01</span></th>
              </tr></thead>
              <tbody id="schedule-body"><tr>
                <td class="time-header" data-day="0" data-slot-id="1"><strong>1 пара</strong><br><span>09:00-10:25</span></td>
                <td class="cell" data-day="0" data-slot="1" data-group-id="47"><div class="lesson-card lecture"><div class="lesson-title">Не выбранный курс</div></div></td>
                <td class="cell" data-day="0" data-slot="1" data-group-id="48">
                  <div class="lesson-card lecture is-readonly-preview">
                    <div class="lesson-title">$subject</div>
                    <div class="lesson-teacher"><a class="teacher-link">Нестерова Оксана Львовна</a></div>
                    <div class="lesson-room">ауд. 3</div>
                  </div>
                </td>
              </tr></tbody>
            </table>
            <div id="schedule-group-filter-list">
              <label class="schedule-group-option"><input type="checkbox" value="47"><span>Группа 41</span></label>
              <label class="schedule-group-option"><input type="checkbox" value="48"><span>Группа 42</span></label>
            </div>
        """.trimIndent()
    }

    private fun <T> runSuspending(block: suspend () -> T): T {
        var value: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) {
                value = result
            }
        })
        return checkNotNull(value).getOrThrow()
    }
}
