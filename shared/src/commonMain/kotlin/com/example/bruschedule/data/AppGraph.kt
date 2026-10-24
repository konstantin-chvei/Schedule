package com.example.bruschedule.data

import app.cash.sqldelight.db.SqlDriver
import com.example.bruschedule.data.local.SqlDelightScheduleRepository
import com.example.bruschedule.data.local.SqlDelightUniversityRepository
import com.example.bruschedule.data.local.db.ScheduleDatabase
import com.example.bruschedule.data.remote.GitHubScheduleProfileCatalogRepository
import com.example.bruschedule.data.remote.GitHubScheduleRemoteDataSource
import com.example.bruschedule.data.remote.HtmlTransport
import com.example.bruschedule.data.remote.createPlatformHtmlTransport
import com.example.bruschedule.data.scraper.UniversityScheduleHtmlParser
import com.example.bruschedule.data.settings.KmpSettingsAppearanceRepository
import com.example.bruschedule.data.settings.KmpSettingsDeadlineReminderRepository
import com.example.bruschedule.data.settings.KmpSettingsSemesterScheduleRepository
import com.example.bruschedule.data.settings.KmpSettingsDailyMessagesRepository
import com.example.bruschedule.data.settings.KmpSettingsTypographyPreferencesRepository
import com.example.bruschedule.data.remote.GitHubDailyMessagesRemoteDataSource
import com.example.bruschedule.data.settings.KmpSettingsParserVersionFlags
import com.example.bruschedule.domain.repository.AppearancePreferencesRepository
import com.example.bruschedule.domain.repository.DailyMessagesRepository
import com.example.bruschedule.domain.repository.SemesterSchedulePreferencesRepository
import com.example.bruschedule.domain.repository.TypographyPreferencesRepository
import com.example.bruschedule.domain.repository.UniversityRepository
import com.example.bruschedule.domain.repository.DeadlineNotificationScheduler
import com.example.bruschedule.domain.repository.DeadlineReminderPreferencesRepository
import com.example.bruschedule.domain.repository.NoOpDeadlineNotificationScheduler
import com.example.bruschedule.domain.repository.ParserVersionRepository
import com.example.bruschedule.domain.repository.AcademicTaskRepository
import com.example.bruschedule.domain.repository.ScheduleProfileCatalogRepository
import com.example.bruschedule.domain.repository.ScheduleRepository
import com.example.bruschedule.domain.repository.ScheduleSyncRepository
import com.example.bruschedule.domain.usecase.RefreshScheduleUseCase
import com.russhwolf.settings.Settings

data class AppGraph(
    val scheduleRepository: ScheduleRepository,
    val syncRepository: ScheduleSyncRepository,
    val taskRepository: AcademicTaskRepository,
    val profileCatalog: ScheduleProfileCatalogRepository,
    val refreshSchedule: RefreshScheduleUseCase,
    val versionFlags: ParserVersionRepository,
    val appearancePreferences: AppearancePreferencesRepository,
    val deadlineReminderPreferences: DeadlineReminderPreferencesRepository,
    val semesterSchedulePreferences: SemesterSchedulePreferencesRepository,
    val dailyMessages: DailyMessagesRepository,
    val typographyPreferences: TypographyPreferencesRepository,
    val universityRepository: UniversityRepository,
    val deadlineNotificationScheduler: DeadlineNotificationScheduler,
)

fun createAppGraph(
    driver: SqlDriver,
    settings: Settings = Settings(),
    transport: HtmlTransport = createPlatformHtmlTransport(),
    deadlineNotificationScheduler: DeadlineNotificationScheduler = NoOpDeadlineNotificationScheduler(),
): AppGraph {
    val database = ScheduleDatabase(driver)
    val localRepository = SqlDelightScheduleRepository(database)
    val universityRepository = SqlDelightUniversityRepository(database, settings)
    val versionFlags = KmpSettingsParserVersionFlags(settings)
    val appearancePreferences = KmpSettingsAppearanceRepository(settings)
    val deadlineReminderPreferences = KmpSettingsDeadlineReminderRepository(settings)
    val semesterSchedulePreferences = KmpSettingsSemesterScheduleRepository(settings)
    val dailyMessages = KmpSettingsDailyMessagesRepository(
        settings = settings,
        remote = GitHubDailyMessagesRemoteDataSource(transport),
    )
    val typographyPreferences = KmpSettingsTypographyPreferencesRepository(settings)

    if (versionFlags.databaseMarker() < CURRENT_DATABASE_MARKER) {
        versionFlags.setDatabaseMarker(CURRENT_DATABASE_MARKER)
    }

    val scraper = UniversityScheduleHtmlParser()
    val remote = GitHubScheduleRemoteDataSource(transport)
    val profileCatalog = GitHubScheduleProfileCatalogRepository(transport)
    return AppGraph(
        scheduleRepository = localRepository,
        syncRepository = localRepository,
        taskRepository = localRepository,
        profileCatalog = profileCatalog,
        refreshSchedule = RefreshScheduleUseCase(
            schedules = localRepository,
            syncRepository = localRepository,
            remote = remote,
            scraper = scraper,
            versionFlags = versionFlags,
            semesterSchedulePreferences = semesterSchedulePreferences,
        ),
        versionFlags = versionFlags,
        appearancePreferences = appearancePreferences,
        deadlineReminderPreferences = deadlineReminderPreferences,
        semesterSchedulePreferences = semesterSchedulePreferences,
        dailyMessages = dailyMessages,
        typographyPreferences = typographyPreferences,
        universityRepository = universityRepository,
        deadlineNotificationScheduler = deadlineNotificationScheduler,
    )
}

private const val CURRENT_DATABASE_MARKER = 3
