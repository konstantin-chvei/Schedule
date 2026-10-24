package com.example.bruschedule.domain.repository

import com.example.bruschedule.domain.model.AcademicTask
import com.example.bruschedule.domain.model.AppearancePreferences
import com.example.bruschedule.domain.model.SemesterSchedulePreferences
import com.example.bruschedule.domain.model.TypographyPreferences
import com.example.bruschedule.domain.model.Lesson
import com.example.bruschedule.domain.model.LessonType
import com.example.bruschedule.domain.model.ScheduleAppTheme
import com.example.bruschedule.domain.model.ProfileCandidate
import com.example.bruschedule.domain.model.ProfileSyncState
import com.example.bruschedule.domain.model.RefreshCommit
import com.example.bruschedule.domain.model.ScheduleProfile
import com.example.bruschedule.domain.model.ScheduleProfileType
import kotlinx.coroutines.flow.Flow

interface ScheduleRepository {
    fun observeProfiles(): Flow<List<ScheduleProfile>>
    fun observeAllLessons(): Flow<List<Lesson>>
    fun observeLessons(profileId: String): Flow<List<Lesson>>

    suspend fun getProfile(profileId: String): ScheduleProfile?
    suspend fun getLesson(lessonId: String): Lesson?
    suspend fun getLessons(profileId: String): List<Lesson>
    suspend fun saveProfile(profile: ScheduleProfile)
    suspend fun deleteProfile(profileId: String)

    suspend fun saveCustomLesson(lesson: Lesson)
    suspend fun saveCustomLessons(lessons: List<Lesson>)

    suspend fun saveLessonNotes(lessonId: String, notes: String): Boolean
    suspend fun saveLessonRating(lessonId: String, rating: Int): Boolean
    suspend fun toggleLessonSticker(lessonId: String, sticker: String): Boolean

    suspend fun deleteCustomLessonAndPreserveTasks(lessonId: String): Boolean
}

interface ScheduleSyncRepository {
    fun observeSyncState(profileId: String): Flow<ProfileSyncState?>
    suspend fun applyWebsiteSchedule(
        profileId: String,
        websiteLessons: List<Lesson>,
        successfulAtEpochMillis: Long,
    ): RefreshCommit
    suspend fun recordRefreshFailure(profileId: String, attemptedAtEpochMillis: Long, message: String)
    suspend fun rollbackToPrevious(profileId: String): Boolean
}

interface AcademicTaskRepository {
    fun observeTasks(): Flow<List<AcademicTask>>
    fun observeTasksForLesson(lessonId: String): Flow<List<AcademicTask>>
    suspend fun saveTask(task: AcademicTask)

    suspend fun saveTasks(tasks: List<AcademicTask>)
    suspend fun setTaskCompleted(taskId: String, completed: Boolean)
    suspend fun deleteTask(taskId: String)
}

interface ScheduleProfileCatalogRepository {
    suspend fun search(type: ScheduleProfileType, query: String): List<ProfileCandidate>

    suspend fun search(universityId: String, type: ScheduleProfileType, query: String): List<ProfileCandidate> =
        search(type, query)

    suspend fun search(
        universityId: String,
        type: ScheduleProfileType,
        query: String,
        onPartialResults: (List<ProfileCandidate>) -> Unit,
    ): List<ProfileCandidate> = search(universityId, type, query).also(onPartialResults)
}

interface AppearancePreferencesRepository {
    fun observe(): Flow<AppearancePreferences>
    suspend fun setTheme(theme: ScheduleAppTheme)
    suspend fun setLessonColor(type: LessonType, colorHex: String)
    suspend fun resetLessonColors()
}

interface SemesterSchedulePreferencesRepository {
    fun observe(): Flow<SemesterSchedulePreferences>
    suspend fun update(preferences: SemesterSchedulePreferences)
}

interface TypographyPreferencesRepository {
    fun observe(): Flow<TypographyPreferences>
    suspend fun update(preferences: TypographyPreferences)
}
