package com.example.bruschedule.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.bruschedule.domain.model.AcademicTask
import com.example.bruschedule.domain.model.AcademicTrack
import com.example.bruschedule.domain.model.AppearancePreferences
import com.example.bruschedule.domain.model.DEFAULT_DAILY_MESSAGES
import com.example.bruschedule.domain.model.SemesterSchedulePreferences
import com.example.bruschedule.domain.model.TypographyPreferences
import com.example.bruschedule.domain.model.TypographyRole
import com.example.bruschedule.domain.model.University
import com.example.bruschedule.domain.model.UniversityIds
import com.example.bruschedule.domain.model.DefaultUniversities
import com.example.bruschedule.domain.model.DomainIds
import com.example.bruschedule.domain.model.DeadlineLinkType
import com.example.bruschedule.domain.model.DeadlineReminderPreferences
import com.example.bruschedule.domain.model.TaskReminderMode
import com.example.bruschedule.domain.model.Lesson
import com.example.bruschedule.domain.model.LessonType
import com.example.bruschedule.domain.model.LessonTypePalette
import com.example.bruschedule.domain.model.LessonRecurrence
import com.example.bruschedule.domain.model.ProfileCandidate
import com.example.bruschedule.domain.model.ProfileSyncState
import com.example.bruschedule.domain.model.ScheduleAppTheme
import com.example.bruschedule.domain.model.ScheduleErrorInfo
import com.example.bruschedule.domain.model.ScheduleProfile
import com.example.bruschedule.domain.model.ScheduleProfileType
import com.example.bruschedule.domain.repository.AcademicTaskRepository
import com.example.bruschedule.domain.repository.AppearancePreferencesRepository
import com.example.bruschedule.domain.repository.DailyMessagesRepository
import com.example.bruschedule.domain.repository.DeadlineNotificationScheduler
import com.example.bruschedule.domain.repository.DeadlineReminderPreferencesRepository
import com.example.bruschedule.domain.repository.ScheduleProfileCatalogRepository
import com.example.bruschedule.domain.repository.ScheduleRepository
import com.example.bruschedule.domain.repository.ScheduleSyncRepository
import com.example.bruschedule.domain.repository.SemesterSchedulePreferencesRepository
import com.example.bruschedule.domain.repository.TypographyPreferencesRepository
import com.example.bruschedule.domain.repository.UniversityRepository
import com.example.bruschedule.domain.usecase.DailyMessageSelector
import com.example.bruschedule.domain.usecase.DeadlineReminderPolicy
import com.example.bruschedule.domain.usecase.SemesterCoveragePolicy
import com.example.bruschedule.domain.usecase.CustomLessonRecurrenceExpander
import com.example.bruschedule.domain.usecase.ScheduleConflictDetector
import com.example.bruschedule.domain.usecase.ScheduleLessonConflict
import com.example.bruschedule.domain.usecase.RefreshOutcome
import com.example.bruschedule.domain.usecase.RefreshScheduleUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

enum class MainDestination { SCHEDULE, DEADLINES, SETTINGS, SCHEDULE_MANAGEMENT }

data class LessonUiItem(
    val lesson: Lesson,
    val pendingTaskCount: Int,
    val isCurrent: Boolean,
)

data class DeadlineUiItem(
    val task: AcademicTask,
    val subjectName: String,
    val profileName: String?,
    val lessonType: LessonType?,
    val linkLabel: String,
    val linkDetail: String? = null,
    val linkedLessonMissing: Boolean = false,
)

data class ProfileSearchUiState(
    val query: String = "",
    val type: ScheduleProfileType = ScheduleProfileType.GROUP,
    val loading: Boolean = false,
    val candidates: List<ProfileCandidate> = emptyList(),
    val error: ScheduleErrorInfo? = null,
)

data class ScheduleUiState(
    val profiles: List<ScheduleProfile> = emptyList(),
    val selectedProfile: ScheduleProfile? = null,
    val universities: List<University> = listOf(DefaultUniversities.BRU),
    val selectedUniversity: University = DefaultUniversities.BRU,
    val selectedDate: LocalDate = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date,
    val todayDate: LocalDate = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date,
    val weekDatesWithLessons: Set<String> = emptySet(),
    val allScheduleDatesWithLessons: Set<String> = emptySet(),
    val lessons: List<LessonUiItem> = emptyList(),
    val availableLessons: List<Lesson> = emptyList(),
    val scheduleConflicts: List<ScheduleLessonConflict> = emptyList(),
    val deadlines: List<DeadlineUiItem> = emptyList(),
    val deadlineReminderPreferences: DeadlineReminderPreferences = DeadlineReminderPreferences(),
    val semesterSchedulePreferences: SemesterSchedulePreferences = SemesterSchedulePreferences(),
    val dailyMessage: String = DEFAULT_DAILY_MESSAGES.first(),
    val destination: MainDestination = MainDestination.SCHEDULE,
    val syncState: ProfileSyncState? = null,
    val isRefreshing: Boolean = false,
    val hasRemoteSchedule: Boolean = false,
    val scheduleCoverageStartDate: LocalDate? = null,
    val scheduleCoverageEndDate: LocalDate? = null,
    val appearance: AppearancePreferences = AppearancePreferences(),
    val typography: TypographyPreferences = TypographyPreferences(),
    val notice: String? = null,
    val profileSearch: ProfileSearchUiState = ProfileSearchUiState(),
    val nowEpochMillis: Long = 0L,
    val isLoading: Boolean = true,
)

data class DeadlineDraft(
    val title: String,
    val description: String,
    val dueDateIso: String,
    val linkType: DeadlineLinkType,
    val profileId: String?,
    val subjectName: String,
    val lessonId: String?,
    val reminderMode: TaskReminderMode,
    val reminderOffsetsMinutes: List<Int>,
    val lessonToPersist: Lesson? = null,
    val taskId: String? = null,
    val isCompleted: Boolean = false,
    val lessonRecurrence: LessonRecurrence = LessonRecurrence.NONE,
)

class ScheduleViewModel(
    private val schedules: ScheduleRepository,
    private val syncRepository: ScheduleSyncRepository,
    private val tasks: AcademicTaskRepository,
    private val profileCatalog: ScheduleProfileCatalogRepository,
    private val refreshSchedule: RefreshScheduleUseCase,
    private val appearancePreferences: AppearancePreferencesRepository,
    private val deadlineReminderPreferencesRepository: DeadlineReminderPreferencesRepository,
    private val semesterSchedulePreferencesRepository: SemesterSchedulePreferencesRepository,
    private val dailyMessagesRepository: DailyMessagesRepository,
    private val typographyPreferencesRepository: TypographyPreferencesRepository,
    private val universityRepository: UniversityRepository,
    private val deadlineNotificationScheduler: DeadlineNotificationScheduler,
    private val clock: Clock = Clock.System,
) : ViewModel() {
    private val initialDate = today()
    private val selectedProfileId = MutableStateFlow<String?>(null)
    private val selectedDate = MutableStateFlow(initialDate)
    private val destination = MutableStateFlow(MainDestination.SCHEDULE)
    private val refreshing = MutableStateFlow(false)
    private val notice = MutableStateFlow<String?>(null)
    private val profileSearch = MutableStateFlow(ProfileSearchUiState())
    private var profileSearchJob: Job? = null
    private val profileSearchGeneration = MutableStateFlow(0L)
    private val pendingRefreshProfileIds = mutableListOf<String>()
    private var activeRefreshProfileId: String? = null
    private val nowEpochMillis = MutableStateFlow(clock.now().toEpochMilliseconds())
    private val appearance = appearancePreferences.observe().stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AppearancePreferences(),
    )
    private val reminderPreferences = deadlineReminderPreferencesRepository.observe().stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = DeadlineReminderPreferences(),
    )
    private val semesterPreferences = semesterSchedulePreferencesRepository.observe().stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = SemesterSchedulePreferences(),
    )
    private val dailyMessages = dailyMessagesRepository.observeMessages().stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = DEFAULT_DAILY_MESSAGES,
    )
    private val typography = MutableStateFlow(TypographyPreferences())

    private data class ScreenPreferencesState(
        val appearance: AppearancePreferences,
        val deadlineReminders: DeadlineReminderPreferences,
        val semester: SemesterSchedulePreferences,
        val dailyMessages: List<String>,
        val typography: TypographyPreferences,
    )

    private val screenPreferences = combine(
        appearance,
        reminderPreferences,
        semesterPreferences,
        dailyMessages,
        typography,
    ) { currentAppearance, reminders, semester, messages, currentTypography ->
        ScreenPreferencesState(currentAppearance, reminders, semester, messages, currentTypography)
    }

    private data class DeadlineMoveResult(
        val movedCount: Int,
        val reminderError: ScheduleErrorInfo? = null,
    )

    private data class BaseState(
        val profiles: List<ScheduleProfile>,
        val lessons: List<Lesson>,
        val tasks: List<AcademicTask>,
        val universities: List<University>,
        val selectedUniversityId: String,
    )

    private data class CoreSelection(
        val profileId: String?,
        val date: LocalDate,
        val destination: MainDestination,
    )

    private data class TransientState(
        val refreshing: Boolean,
        val notice: String?,
        val profileSearch: ProfileSearchUiState,
        val now: Long,
    )

    private data class SelectionState(
        val core: CoreSelection,
        val syncState: ProfileSyncState?,
        val transient: TransientState,
    )

    private val baseState: Flow<BaseState> = combine(
        schedules.observeProfiles(),
        schedules.observeAllLessons(),
        tasks.observeTasks(),
        universityRepository.observeUniversities(),
        universityRepository.observeSelectedUniversityId(),
    ) { profiles, lessons, academicTasks, universities, selectedUniversityId ->
        BaseState(profiles, lessons, academicTasks, universities, selectedUniversityId)
    }

    private val syncState: Flow<ProfileSyncState?> = selectedProfileId.flatMapLatest { id ->
        if (id == null) flowOf(null) else syncRepository.observeSyncState(id)
    }

    private val coreSelection: Flow<CoreSelection> = combine(
        selectedProfileId,
        selectedDate,
        destination,
    ) { profileId, date, selectedDestination ->
        CoreSelection(profileId, date, selectedDestination)
    }

    private val transientState: Flow<TransientState> = combine(
        refreshing,
        notice,
        profileSearch,
        nowEpochMillis,
    ) { isRefreshing, message, searchState, now ->
        TransientState(isRefreshing, message, searchState, now)
    }

    private val selectionState: Flow<SelectionState> = combine(
        coreSelection,
        syncState,
        transientState,
    ) { core, currentSyncState, transient ->
        SelectionState(core, currentSyncState, transient)
    }

    val uiState = combine(baseState, selectionState, screenPreferences) { base, selection, preferences ->
        val selectedUniversity = base.universities.firstOrNull { it.id == base.selectedUniversityId }
            ?: base.universities.firstOrNull()
            ?: DefaultUniversities.BRU
        val universityProfiles = base.profiles.filter { it.universityId == selectedUniversity.id }
        val selectedId = selection.core.profileId
            ?.takeIf { profileId -> universityProfiles.any { it.id == profileId } }
            ?: universityProfiles.firstOrNull()?.id
        val selectedProfile = universityProfiles.firstOrNull { it.id == selectedId }
        val universityProfileIds = universityProfiles.mapTo(mutableSetOf(), ScheduleProfile::id)
        val universityLessons = base.lessons.filter { it.profileId in universityProfileIds }
        val universityTasks = base.tasks.filter { it.profileId == null || it.profileId in universityProfileIds }
        val profileLessons = if (selectedProfile == null) emptyList() else universityLessons.filter {
            it.profileId == selectedProfile.id
        }
        val now = selection.transient.now
        val nowLocal = kotlinx.datetime.Instant.fromEpochMilliseconds(now)
            .toLocalDateTime(TimeZone.currentSystemDefault())
        val todayDate = nowLocal.date
        val coverageStartDate = selectedProfile?.let { SemesterCoveragePolicy.coverageStartDate(todayDate) }
        val coverageEndDate = selectedProfile?.let {
            SemesterCoveragePolicy.coverageEndDateForUniversity(
                todayDate,
                preferences.semester,
                selectedUniversity.id,
                it.id,
            )
        }
        val displayedDate = when {
            selection.core.date < todayDate -> todayDate
            coverageEndDate != null && selection.core.date > coverageEndDate -> coverageEndDate
            else -> selection.core.date
        }
        val visibleProfileLessons = profileLessons.filter { lesson ->
            val lessonDate = runCatching { LocalDate.parse(lesson.lessonDate) }.getOrNull()
            lessonDate != null && lessonDate >= todayDate &&
                (coverageEndDate == null || lessonDate <= coverageEndDate)
        }
        val weekStart = displayedDate.minus(DatePeriod(days = displayedDate.dayOfWeek.isoDayNumber - 1))
        val weekEnd = weekStart.plus(DatePeriod(days = 6))
        val lessonsInWeek = visibleProfileLessons.filter {
            it.lessonDate >= weekStart.toString() && it.lessonDate <= weekEnd.toString()
        }
        val datesWithLessons = lessonsInWeek.mapTo(mutableSetOf()) { it.lessonDate }
        val lessonsForDate = visibleProfileLessons
            .filter { it.lessonDate == displayedDate.toString() }
            .sortedWith(compareBy({ it.startTime }, { it.slotNumber }))
            .map { lesson ->
                LessonUiItem(
                    lesson = lesson,
                    pendingTaskCount = universityTasks.count { it.lessonId == lesson.id && !it.isCompleted },
                    isCurrent = lesson.isCurrentAt(nowLocal),
                )
            }
        val lessonById = universityLessons.associateBy(Lesson::id)
        val profileById = universityProfiles.associateBy(ScheduleProfile::id)
        val orderedDeadlines = universityTasks
            .sortedWith(compareBy<AcademicTask>({ it.dueDate }, { it.isCompleted }, { it.taskTitle }))
            .map { task ->
                val linkedLesson = task.lessonId?.let(lessonById::get)
                val taskProfileId = task.profileId ?: linkedLesson?.profileId
                DeadlineUiItem(
                    task = task,
                    subjectName = when (task.linkType) {
                        DeadlineLinkType.NONE -> "Общий дедлайн"
                        DeadlineLinkType.SUBJECT -> linkedLesson?.subjectName
                            ?: task.subjectNameSnapshot.ifBlank { "Предмет не указан" }
                        DeadlineLinkType.LESSON -> linkedLesson?.subjectName
                            ?: task.subjectNameSnapshot.ifBlank { "Пара" }
                    },
                    profileName = taskProfileId?.let { profileById[it]?.name },
                    lessonType = linkedLesson?.type,
                    linkLabel = when (task.linkType) {
                        DeadlineLinkType.SUBJECT -> "ПРЕДМЕТ"
                        DeadlineLinkType.LESSON -> if (linkedLesson == null) "СВЯЗЬ С ПАРОЙ ПОТЕРЯНА" else "КОНКРЕТНАЯ ПАРА"
                        DeadlineLinkType.NONE -> "БЕЗ ПРИВЯЗКИ"
                    },
                    linkDetail = when {
                        task.linkType == DeadlineLinkType.LESSON && linkedLesson == null -> "Пара удалена или перенесена. Измените привязку дедлайна."
                        task.linkType == DeadlineLinkType.LESSON -> linkedLesson?.let { "${it.lessonDate} · ${it.startTime}" }
                        else -> null
                    },
                    linkedLessonMissing = task.linkType == DeadlineLinkType.LESSON && linkedLesson == null,
                )
            }
        ScheduleUiState(
            profiles = universityProfiles,
            selectedProfile = selectedProfile,
            universities = base.universities,
            selectedUniversity = selectedUniversity,
            selectedDate = displayedDate,
            todayDate = todayDate,
            weekDatesWithLessons = datesWithLessons,
            allScheduleDatesWithLessons = visibleProfileLessons.mapTo(mutableSetOf()) { it.lessonDate },
            lessons = lessonsForDate,
            availableLessons = universityLessons,
            scheduleConflicts = ScheduleConflictDetector.find(visibleProfileLessons, universityTasks),
            deadlines = orderedDeadlines,
            deadlineReminderPreferences = preferences.deadlineReminders,
            semesterSchedulePreferences = preferences.semester,
            dailyMessage = DailyMessageSelector.forDate(todayDate, preferences.dailyMessages),
            destination = selection.core.destination,
            syncState = if (selectedId == selection.core.profileId) selection.syncState else null,
            isRefreshing = selection.transient.refreshing,
            hasRemoteSchedule = profileLessons.any { !it.isCustom },
            scheduleCoverageStartDate = coverageStartDate,
            scheduleCoverageEndDate = coverageEndDate,
            appearance = preferences.appearance,
            typography = preferences.typography,
            notice = selection.transient.notice,
            profileSearch = selection.transient.profileSearch,
            nowEpochMillis = now,
            isLoading = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        initialValue = ScheduleUiState(selectedDate = initialDate, nowEpochMillis = nowEpochMillis.value),
    )

    init {
        viewModelScope.launch {
            try {
                dailyMessagesRepository.refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Keep the cached or bundled messages when the network is unavailable.
            }
        }
        viewModelScope.launch {
            typographyPreferencesRepository.observe().collect { typography.value = it }
        }
        viewModelScope.launch {
            combine(
                schedules.observeProfiles(),
                universityRepository.observeSelectedUniversityId(),
            ) { profiles, universityId -> profiles.filter { it.universityId == universityId } }
                .collect { profiles ->
                    val activeId = selectedProfileId.value
                    if (profiles.isEmpty()) {
                        selectedProfileId.value = null
                    } else if (activeId == null || profiles.none { it.id == activeId }) {
                        selectedProfileId.value = profiles.first().id
                    }
                }
        }
        viewModelScope.launch {
            while (true) {
                nowEpochMillis.value = clock.now().toEpochMilliseconds()
                delay(30_000)
            }
        }
    }

    fun selectProfile(profileId: String) {
        if (uiState.value.profiles.none { it.id == profileId }) {
            notice.value = "Это расписание больше не сохранено. Выберите другое или добавьте его заново."
            return
        }
        selectedProfileId.value = profileId
    }

    fun selectUniversity(universityId: String) {
        profileSearchGeneration.value = profileSearchGeneration.value + 1
        profileSearchJob?.cancel()
        profileSearchJob = null
        if (uiState.value.universities.none { it.id == universityId }) {
            notice.value = "Этот вуз больше не доступен. Выберите другой или добавьте его заново."
            return
        }
        viewModelScope.launch {
            try {
                universityRepository.selectUniversity(universityId)
                profileSearch.value = ProfileSearchUiState()
                val profile = schedules.observeProfiles().first().firstOrNull {
                    it.universityId == universityId
                }
                selectedProfileId.value = profile?.id
                selectedDate.value = today()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                notice.value = errorNotice("Не удалось выбрать вуз", failure, "UNIVERSITY")
            }
        }
    }

    fun selectDate(date: LocalDate) {
        val current = uiState.value
        val start = current.scheduleCoverageStartDate ?: return
        val end = current.scheduleCoverageEndDate ?: return
        if (date in start..end) selectedDate.value = date
    }

    fun changeWeek(weeks: Int) {
        val current = uiState.value
        val firstCoveredDate = current.scheduleCoverageStartDate ?: return
        val lastCoveredDate = current.scheduleCoverageEndDate ?: return
        val targetDate = current.selectedDate.plus(DatePeriod(days = weeks * 7))
        val targetWeek = targetDate.startOfWeek()
        if (targetWeek < firstCoveredDate.startOfWeek() || targetWeek > lastCoveredDate.startOfWeek()) return
        selectedDate.value = if (targetDate > lastCoveredDate) lastCoveredDate else targetDate
    }

    fun showDestination(destination: MainDestination) {
        this.destination.value = destination
    }

    fun setTheme(theme: ScheduleAppTheme) = updateAppearance {
        appearancePreferences.setTheme(theme)
    }

    fun setTypographyScale(role: TypographyRole, scale: Float) {
        persistTypography(typography.value.withScale(role, scale))
    }

    fun resetTypographyScales() {
        val defaultScale = TypographyPreferences.MIN_FONT_SCALE
        persistTypography(
            TypographyPreferences(
                displayScale = defaultScale,
                headingScale = defaultScale,
                bodyScale = defaultScale,
                captionScale = defaultScale,
            ),
        )
    }

    private fun persistTypography(updated: TypographyPreferences) {
        val previous = typography.value
        typography.value = updated
        viewModelScope.launch {
            try {
                typographyPreferencesRepository.update(updated)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (typography.value == updated) typography.value = previous
                notice.value = errorNotice("Не удалось сохранить размер шрифта", failure, "PREFERENCES")
            }
        }
    }

    fun setLessonColor(type: LessonType, colorHex: String) = updateAppearance {
        appearancePreferences.setLessonColor(type, colorHex)
    }

    fun resetLessonColors() = updateAppearance {
        appearancePreferences.resetLessonColors()
    }

    fun setAcademicTrack(universityId: String, track: AcademicTrack) {
        val current = semesterPreferences.value
        if (current.trackForUniversity(universityId) == track) return
        val updated = runCatching { current.withTrackForUniversity(universityId, track) }
            .getOrElse { failure ->
                notice.value = failure.message ?: "Этот вариант сессии не подходит выбранному вузу"
                return
            }
        updateSemesterSchedulePreferences(updated)
    }

    fun setUniversitySessionEndDate(universityId: String, sessionEndDateIso: String?) {
        val normalizedDate = sessionEndDateIso?.let { value ->
            runCatching { LocalDate.parse(value).toString() }.getOrNull()
        }
        if (sessionEndDateIso != null && normalizedDate == null) {
            notice.value = "Введите дату последнего экзамена в формате ДД.ММ.ГГГГ"
            return
        }
        updateSemesterSchedulePreferences(
            semesterPreferences.value.withUniversitySessionEndDate(universityId, normalizedDate),
        )
    }

    fun setIfzoSessionEndDate(sessionEndDateIso: String) {
        val normalizedDate = runCatching { LocalDate.parse(sessionEndDateIso).toString() }.getOrNull()
        if (normalizedDate == null) {
            notice.value = "Введите дату последнего экзамена в формате ДД.ММ.ГГГГ"
            return
        }
        val updated = semesterPreferences.value
            .withTrackForUniversity(UniversityIds.BRU, AcademicTrack.IFZO)
            .withUniversitySessionEndDate(UniversityIds.BRU, normalizedDate)
        updateSemesterSchedulePreferences(updated)
    }

    fun setProfileSessionEndDate(profileId: String, sessionEndDateIso: String?) {
        if (uiState.value.profiles.none { it.id == profileId }) {
            notice.value = "Выбранное расписание больше не доступно"
            return
        }
        val normalizedDate = sessionEndDateIso?.let { value ->
            runCatching { LocalDate.parse(value).toString() }.getOrNull()
        }
        if (sessionEndDateIso != null && normalizedDate == null) {
            notice.value = "Введите дату последнего экзамена в формате ДД.ММ.ГГГГ"
            return
        }
        val current = semesterPreferences.value
        val profileDates = current.profileSessionEndDatesIso.toMutableMap()
        if (normalizedDate == null) profileDates.remove(profileId) else profileDates[profileId] = normalizedDate
        updateSemesterSchedulePreferences(current.copy(profileSessionEndDatesIso = profileDates))
    }

    private fun updateSemesterSchedulePreferences(preferences: SemesterSchedulePreferences) {
        viewModelScope.launch {
            try {
                semesterSchedulePreferencesRepository.update(preferences)
                val today = today()
                val activeProfileId = selectedProfileId.value ?: uiState.value.selectedProfile?.id
                val activeProfile = activeProfileId?.let { id -> uiState.value.profiles.firstOrNull { it.id == id } }
                val universityId = activeProfile?.universityId ?: uiState.value.selectedUniversity.id
                val end = SemesterCoveragePolicy.coverageEndDateForUniversity(
                    today,
                    preferences,
                    universityId,
                    activeProfileId,
                )
                if (selectedDate.value < today || selectedDate.value > end) selectedDate.value = today
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                notice.value = errorNotice("Не удалось сохранить период семестра", failure, "PREFERENCES")
            }
        }
    }

    private fun updateAppearance(update: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                update()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                notice.value = errorNotice("Не удалось сохранить настройки оформления", failure, "PREFERENCES")
            }
        }
    }

    fun dismissNotice() {
        notice.value = null
    }

    fun refreshSelectedProfile() {
        val id = selectedProfileId.value ?: uiState.value.selectedProfile?.id
        if (id == null) {
            notice.value = "Сначала выберите или добавьте расписание"
            return
        }
        launchRefresh(id)
    }

    private fun launchRefresh(profileId: String) {
        if (refreshing.value) {
            if (profileId != activeRefreshProfileId && profileId !in pendingRefreshProfileIds) {
                pendingRefreshProfileIds += profileId
                notice.value = "Расписание добавлено в очередь на обновление после текущей загрузки"
            }
            return
        }
        activeRefreshProfileId = profileId
        refreshing.value = true
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.Default) { refreshSchedule(profileId) }
                notice.value = when (result) {
                    is RefreshOutcome.Success -> "Обновлено: ${result.lessonCount} пар · ручных сохранено ${result.preservedCustomLessonCount}"
                    is RefreshOutcome.Failure -> "${result.error.message} · Код: ${result.error.code}"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                val error = ScheduleErrorInfo.fromThrowable(failure, operation = "SYNC")
                runCatching {
                    syncRepository.recordRefreshFailure(profileId, clock.now().toEpochMilliseconds(), error.encodeForStorage())
                }
                notice.value = "${error.message} · Код: ${error.code}"
            } finally {
                refreshing.value = false
                activeRefreshProfileId = null
                val nextProfileId = pendingRefreshProfileIds.firstOrNull()
                if (nextProfileId != null) {
                    pendingRefreshProfileIds.removeAt(0)
                    launchRefresh(nextProfileId)
                }
            }
        }
    }

    fun rollbackSelectedProfile() {
        val id = selectedProfileId.value ?: uiState.value.selectedProfile?.id
        if (id == null) {
            notice.value = "Сначала выберите расписание"
            return
        }
        viewModelScope.launch {
            try {
                val restored = withContext(Dispatchers.Default) { syncRepository.rollbackToPrevious(id) }
                notice.value = if (restored) "Восстановлен локальный сейв расписания" else "Локальный сейв ещё не создан"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                notice.value = errorNotice("Не удалось восстановить локальный сейв", failure, "DATABASE")
            }
        }
    }

    fun deleteSelectedProfile() {
        val selected = uiState.value.selectedProfile ?: return
        val nextProfileId = uiState.value.profiles.firstOrNull { it.id != selected.id }?.id
        val linkedTaskIds = uiState.value.deadlines
            .filter { it.task.profileId == selected.id }
            .map { it.task.id }
        viewModelScope.launch {
            try {
                linkedTaskIds.forEach { deadlineNotificationScheduler.cancel(it) }
                schedules.deleteProfile(selected.id)
                selectedProfileId.value = nextProfileId
                notice.value = "Расписание «${selected.name}» и связанные локальные данные удалены"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                notice.value = errorNotice("Не удалось удалить расписание", failure, "DATABASE")
            }
        }
    }

    fun searchProfiles(type: ScheduleProfileType, query: String) {
        profileSearchJob?.cancel()
        val requestGeneration = profileSearchGeneration.value + 1
        profileSearchGeneration.value = requestGeneration
        profileSearchJob = null
        val university = uiState.value.selectedUniversity
        if (query.isBlank()) {
            profileSearch.value = ProfileSearchUiState(query = query, type = type)
            notice.value = "Введите код группы или имя преподавателя"
            return
        }
        profileSearch.value = ProfileSearchUiState(query = query, type = type, loading = true)
        profileSearchJob = viewModelScope.launch {
            try {
                val candidates = withContext(Dispatchers.Default) {
                    profileCatalog.search(university.id, type, query) { partialCandidates ->
                        val currentSearch = profileSearch.value
                        if (requestGeneration == profileSearchGeneration.value &&
                            uiState.value.selectedUniversity.id == university.id &&
                            currentSearch.query == query && currentSearch.type == type
                        ) {
                            profileSearch.value = currentSearch.copy(
                                candidates = partialCandidates,
                                loading = currentSearch.loading && partialCandidates.isEmpty(),
                            )
                        }
                    }
                }
                if (requestGeneration != profileSearchGeneration.value || uiState.value.selectedUniversity.id != university.id) return@launch
                profileSearch.value = ProfileSearchUiState(query, type, candidates = candidates)
                if (candidates.isEmpty()) {
                    notice.value = "По запросу «$query» ничего не найдено. Проверьте ввод или попробуйте позже."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (requestGeneration != profileSearchGeneration.value || uiState.value.selectedUniversity.id != university.id) return@launch
                val partialCandidates = profileSearch.value
                    .takeIf { it.query == query && it.type == type }
                    ?.candidates
                    .orEmpty()
                profileSearch.value = ProfileSearchUiState(
                    query = query,
                    type = type,
                    candidates = partialCandidates,
                    error = ScheduleErrorInfo.fromThrowable(failure, operation = "SEARCH"),
                )
            }
        }
    }

    fun addProfileCandidate(candidate: ProfileCandidate) {
        profileSearchGeneration.value = profileSearchGeneration.value + 1
        profileSearchJob?.cancel()
        profileSearchJob = null
        if (!candidate.scheduleAvailable) {
            notice.value = "Для «${candidate.name}» расписание пока недоступно."
            return
        }
        val profile = ScheduleProfile(
            id = DomainIds.new("profile"),
            type = candidate.type,
            name = candidate.name,
            sourceUrl = candidate.sourceUrl,
            sourceKey = candidate.sourceKey,
            universityId = candidate.universityId,
        )
        viewModelScope.launch {
            try {
                schedules.saveProfile(profile)
                selectedProfileId.value = profile.id
                profileSearch.value = ProfileSearchUiState(type = candidate.type)
                notice.value = "Профиль «${candidate.name}» добавлен · загружаем расписание…"
                launchRefresh(profile.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                notice.value = errorNotice("Не удалось добавить расписание", failure, "PROFILE")
            }
        }
    }

    fun saveCustomLesson(
        lesson: Lesson,
        recurrence: LessonRecurrence = LessonRecurrence.NONE,
        detachFromWebsiteSource: Boolean = false,
        moveDeadlinesWithPair: Boolean = false,
    ) {
        val custom = lesson.copy(
            isCustom = true,
            colorHex = LessonTypePalette.accent(lesson.type),
            sourceKey = if (detachFromWebsiteSource) "custom:${lesson.id}" else lesson.sourceKey.ifBlank { "custom:${lesson.id}" },
        )
        viewModelScope.launch {
            try {
                val occurrences = persistCustomLessonOccurrences(custom, recurrence)
                val deadlineMove = if (moveDeadlinesWithPair) {
                    moveLinkedDeadlinesToLessonDate(custom)
                } else {
                    DeadlineMoveResult(movedCount = 0)
                }
                notice.value = when {
                    deadlineMove.movedCount > 0 && deadlineMove.reminderError != null ->
                        "Сроки перенесены (${deadlineMove.movedCount}), но напоминания не удалось обновить · Код: ${deadlineMove.reminderError.code}"
                    deadlineMove.movedCount > 0 ->
                        "Пара сохранена · дедлайнов перенесено на её дату: ${deadlineMove.movedCount}"
                    recurrence != LessonRecurrence.NONE ->
                        "Добавлено пар: ${occurrences.size} · до ${occurrences.last().lessonDate}"
                    else -> "Пара сохранена локально"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                notice.value = errorNotice("Не удалось сохранить пару или связанные дедлайны", failure, "DATABASE")
            }
        }
    }

    fun saveLessonNotes(lessonId: String, notes: String) {
        viewModelScope.launch {
            try {
                val saved = schedules.saveLessonNotes(lessonId, notes)
                notice.value = if (saved) "Заметки к паре сохранены" else "Эта пара уже отсутствует в расписании"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                notice.value = errorNotice("Не удалось сохранить заметки к паре", failure, "DATABASE")
            }
        }
    }

    fun saveLessonRating(lessonId: String, rating: Int) {
        if (rating !in 1..5) return
        viewModelScope.launch {
            try {
                schedules.saveLessonRating(lessonId, rating)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                notice.value = errorNotice("Не удалось сохранить оценку пары", failure, "DATABASE")
            }
        }
    }

    fun toggleLessonSticker(lessonId: String, sticker: String) {
        viewModelScope.launch {
            try {
                schedules.toggleLessonSticker(lessonId, sticker)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                notice.value = errorNotice("Не удалось сохранить стикер пары", failure, "DATABASE")
            }
        }
    }

    private suspend fun persistCustomLessonOccurrences(
        lesson: Lesson,
        recurrence: LessonRecurrence,
    ): List<Lesson> {
        val anchorDate = LocalDate.parse(lesson.lessonDate)
        val coverageEndDate = uiState.value.scheduleCoverageEndDate
            ?.takeIf { it >= anchorDate }
            ?: anchorDate
        val occurrences = CustomLessonRecurrenceExpander.expand(lesson, recurrence, coverageEndDate)
        schedules.saveCustomLessons(occurrences)
        return occurrences
    }

    private suspend fun moveLinkedDeadlinesToLessonDate(lesson: Lesson): DeadlineMoveResult {
        val newLessonDate = LocalDate.parse(lesson.lessonDate)
        val zone = TimeZone.currentSystemDefault()
        val linkedTasks = tasks.observeTasksForLesson(lesson.id).first()
        val updatedTasks = linkedTasks.map { task ->
            val previousDateTime = kotlinx.datetime.Instant.fromEpochMilliseconds(task.dueDate).toLocalDateTime(zone)
            val newDueDate = LocalDateTime(newLessonDate, previousDateTime.time)
                .toInstant(zone)
                .toEpochMilliseconds()
            task.copy(dueDate = newDueDate)
        }
        tasks.saveTasks(updatedTasks)

        var reminderError: ScheduleErrorInfo? = null
        for (task in updatedTasks.filter { !it.isCompleted }) {
            try {
                if (!syncTaskReminders(task, reminderPreferences.value, requestPermission = false) && reminderError == null) {
                    reminderError = ScheduleErrorInfo.fromDiagnostic("reminder scheduler returned false", "TASK")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (reminderError == null) reminderError = ScheduleErrorInfo.fromThrowable(failure, "TASK")
            }
        }
        return DeadlineMoveResult(updatedTasks.size, reminderError)
    }

    fun deleteCustomLesson(lessonId: String) {
        viewModelScope.launch {
            try {
                val deleted = schedules.deleteCustomLessonAndPreserveTasks(lessonId)
                notice.value = if (deleted) {
                    "Личная пара удалена. Связанные дедлайны сохранены и привязаны к предмету."
                } else {
                    "Пара уже удалена или не является личной."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                notice.value = errorNotice("Не удалось удалить личную пару", failure, "DATABASE")
            }
        }
    }

    fun newLessonDraft(): Lesson? {
        val current = uiState.value
        val profile = current.selectedProfile ?: return null
        val id = DomainIds.new("custom-lesson")
        return Lesson(
            id = id,
            profileId = profile.id,
            subjectName = "",
            teacherName = "",
            classroom = "",
            startTime = "08:30",
            endTime = "10:05",
            type = LessonType.UNKNOWN,
            colorHex = LessonTypePalette.UNKNOWN,
            isCustom = true,
            version = 0,
            lessonDate = current.selectedDate.toString(),
            dayOfWeek = current.selectedDate.dayOfWeek.isoDayNumber,
            slotNumber = 0,
            sourceKey = "custom:$id",
        )
    }

    fun addTask(draft: DeadlineDraft) {
        if (draft.title.isBlank()) {
            notice.value = "Введите название задания"
            return
        }
        val dueDate = runCatching {
            val date = LocalDate.parse(draft.dueDateIso.trim())
            LocalDateTime(date, LocalTime(hour = 9, minute = 0))
                .toInstant(TimeZone.currentSystemDefault())
                .toEpochMilliseconds()
        }.getOrElse {
            notice.value = "Дата должна быть в формате ГГГГ-ММ-ДД"
            return
        }
        if (draft.linkType == DeadlineLinkType.SUBJECT &&
            (draft.profileId.isNullOrBlank() || draft.subjectName.isBlank())
        ) {
            notice.value = "Выберите профиль и предмет"
            return
        }
        if (draft.linkType == DeadlineLinkType.LESSON && draft.lessonId.isNullOrBlank()) {
            notice.value = "Выберите свою пару"
            return
        }
        val task = AcademicTask(
            id = draft.taskId ?: DomainIds.new("task"),
            lessonId = draft.lessonId.takeIf { draft.linkType == DeadlineLinkType.LESSON },
            taskTitle = draft.title.trim(),
            description = draft.description.trim(),
            dueDate = dueDate,
            isCompleted = draft.isCompleted,
            profileId = draft.profileId.takeIf { draft.linkType != DeadlineLinkType.NONE },
            subjectNameSnapshot = draft.subjectName.trim().takeIf { draft.linkType != DeadlineLinkType.NONE }.orEmpty(),
            linkType = draft.linkType,
            reminderMode = draft.reminderMode,
            reminderOffsetsMinutes = draft.reminderOffsetsMinutes.distinct().sortedDescending(),
        )
        viewModelScope.launch {
            try {
                draft.lessonToPersist?.let { persistCustomLessonOccurrences(it, draft.lessonRecurrence) }
                tasks.saveTask(task)
                val remindersReady = syncTaskReminders(task, reminderPreferences.value)
                notice.value = if (!remindersReady) {
                    "Дедлайн сохранён, но разрешение на уведомления не выдано"
                } else if (draft.taskId == null) {
                    "Дедлайн добавлен"
                } else {
                    "Изменения дедлайна сохранены"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                notice.value = errorNotice("Не удалось сохранить дедлайн", failure, "TASK")
            }
        }
    }

    fun setTaskCompleted(taskId: String, completed: Boolean) {
        viewModelScope.launch {
            try {
                tasks.setTaskCompleted(taskId, completed)
                val task = uiState.value.deadlines.firstOrNull { it.task.id == taskId }?.task
                if (completed) {
                    deadlineNotificationScheduler.cancel(taskId)
                } else if (task != null && !syncTaskReminders(task.copy(isCompleted = false), reminderPreferences.value)) {
                    notice.value = "Дедлайн открыт, но разрешение на уведомления не выдано"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                notice.value = errorNotice("Не удалось изменить статус дедлайна", failure, "TASK")
            }
        }
    }

    fun setDeadlineReminderPreferences(preferences: DeadlineReminderPreferences) {
        viewModelScope.launch {
            try {
                val granted = !preferences.enabled || deadlineNotificationScheduler.requestPermission()
                val savedPreferences = if (granted) preferences else preferences.copy(enabled = false)
                deadlineReminderPreferencesRepository.update(savedPreferences)
                val currentTasks = tasks.observeTasks().first()
                if (!savedPreferences.enabled) {
                    currentTasks.forEach { deadlineNotificationScheduler.cancel(it.id) }
                } else {
                    currentTasks.forEach { syncTaskReminders(it, savedPreferences, requestPermission = false) }
                }
                notice.value = when {
                    !granted -> "Разрешите уведомления в настройках устройства, чтобы включить напоминания"
                    !savedPreferences.enabled -> "Напоминания отключены"
                    else -> "Настройки напоминаний сохранены"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                notice.value = errorNotice("Не удалось обновить напоминания", failure, "TASK")
            }
        }
    }

    private suspend fun syncTaskReminders(
        task: AcademicTask,
        preferences: DeadlineReminderPreferences,
        requestPermission: Boolean = true,
    ): Boolean {
        deadlineNotificationScheduler.cancel(task.id)
        val offsets = DeadlineReminderPolicy.effectiveOffsets(task, preferences)
        if (offsets.isEmpty()) return true
        if (requestPermission && !deadlineNotificationScheduler.requestPermission()) {
            deadlineReminderPreferencesRepository.update(preferences.copy(enabled = false))
            return false
        }
        return deadlineNotificationScheduler.schedule(task, offsets)
    }

    private fun errorNotice(prefix: String, failure: Throwable, operation: String): String {
        val error = ScheduleErrorInfo.fromThrowable(failure, operation)
        return "$prefix: ${error.message} · Код: ${error.code}"
    }

    private fun LocalDate.startOfWeek(): LocalDate =
        minus(DatePeriod(days = dayOfWeek.isoDayNumber - 1))

    private fun today(): LocalDate =
        clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

    private fun Lesson.isCurrentAt(now: kotlinx.datetime.LocalDateTime): Boolean {
        if (lessonDate != now.date.toString()) return false
        val starts = runCatching { kotlinx.datetime.LocalTime.parse(startTime) }.getOrNull() ?: return false
        val ends = runCatching { kotlinx.datetime.LocalTime.parse(endTime) }.getOrNull() ?: return false
        return now.time >= starts && now.time < ends
    }
}
