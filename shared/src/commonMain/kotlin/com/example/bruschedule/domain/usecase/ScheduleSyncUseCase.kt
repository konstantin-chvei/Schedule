package com.example.bruschedule.domain.usecase

import com.example.bruschedule.domain.model.ScheduleErrorInfo
import com.example.bruschedule.domain.model.ScheduleProfile
import com.example.bruschedule.domain.model.ScheduleWindow
import com.example.bruschedule.domain.repository.ScheduleRepository
import com.example.bruschedule.domain.repository.ScheduleSyncRepository
import com.example.bruschedule.domain.repository.ParserVersionRepository
import com.example.bruschedule.domain.repository.ScheduleHtmlParser
import com.example.bruschedule.domain.repository.ScheduleRemoteDataSource
import com.example.bruschedule.domain.repository.SemesterSchedulePreferencesRepository
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

sealed interface RefreshOutcome {
    data class Success(
        val profileId: String,
        val lessonCount: Int,
        val preservedCustomLessonCount: Int,
        val completedAtEpochMillis: Long,
    ) : RefreshOutcome

    data class Failure(val error: ScheduleErrorInfo) : RefreshOutcome
}

class RefreshScheduleUseCase(
    private val schedules: ScheduleRepository,
    private val syncRepository: ScheduleSyncRepository,
    private val remote: ScheduleRemoteDataSource,
    private val scraper: ScheduleHtmlParser,
    private val versionFlags: ParserVersionRepository,
    private val semesterSchedulePreferences: SemesterSchedulePreferencesRepository,
    private val clock: Clock = Clock.System,
) {
    suspend operator fun invoke(profileId: String): RefreshOutcome {
        val attemptedAt = clock.now().toEpochMilliseconds()
        return try {
            val profile: ScheduleProfile = schedules.getProfile(profileId)
                ?: error("Профиль расписания не найден")
            val today = clock.now()
                .toLocalDateTime(TimeZone.currentSystemDefault())
                .date
            val preferences = semesterSchedulePreferences.observe().first()
            val semesterEnd = SemesterCoveragePolicy.coverageEndDateForUniversity(
                today,
                preferences,
                profile.universityId,
                profile.id,
            )
            val window = ScheduleWindow.currentAndNextWeek(clock).copy(semesterCoverageEndDate = semesterEnd)
            val html = remote.fetchHtml(profile, window)
            val parsed = scraper.parse(
                profile = profile,
                html = html,
                referenceDate = window.startDate,
            )
            if (parsed.isEmpty()) {
                error("На странице не найдено ни одной пары. Локальное расписание не изменено.")
            }
            val websiteLessons = when {
                scraper.hasAlternatingTwoWeekCycle(profile, html) ->
                    ScheduleCycleExpander.expand(parsed, today, semesterEnd)
                scraper.hasWeeklyScheduleTemplate(profile, html) ->
                    ScheduleCycleExpander.expandWeekly(parsed, today, semesterEnd)
                else -> parsed.filter { lesson ->
                    val date = runCatching { LocalDate.parse(lesson.lessonDate) }.getOrNull()
                    date == null || date in today..semesterEnd
                }
            }
            if (websiteLessons.isEmpty()) {
                error("Не удалось развернуть пары на семестровый период. Локальное расписание не изменено.")
            }
            val commit = syncRepository.applyWebsiteSchedule(
                profileId = profile.id,
                websiteLessons = websiteLessons,
                successfulAtEpochMillis = clock.now().toEpochMilliseconds(),
            )
            versionFlags.markParserVersion(profile.id, scraper.formatVersion)
            RefreshOutcome.Success(
                profileId = profile.id,
                lessonCount = commit.appliedLessonCount,
                preservedCustomLessonCount = commit.preservedCustomLessonCount,
                completedAtEpochMillis = clock.now().toEpochMilliseconds(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            val error = ScheduleErrorInfo.fromThrowable(failure, operation = "SYNC")
            runCatching {
                syncRepository.recordRefreshFailure(profileId, attemptedAt, error.encodeForStorage())
            }
            RefreshOutcome.Failure(error)
        }
    }
}
