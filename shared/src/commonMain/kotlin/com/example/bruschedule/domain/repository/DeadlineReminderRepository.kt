package com.example.bruschedule.domain.repository

import com.example.bruschedule.domain.model.AcademicTask
import com.example.bruschedule.domain.model.DeadlineReminderPreferences
import kotlinx.coroutines.flow.Flow

interface DeadlineReminderPreferencesRepository {
    fun observe(): Flow<DeadlineReminderPreferences>
    suspend fun update(preferences: DeadlineReminderPreferences)
}

interface DeadlineNotificationScheduler {
    suspend fun requestPermission(): Boolean

    suspend fun schedule(task: AcademicTask, offsetsMinutes: List<Int>): Boolean

    suspend fun cancel(taskId: String)
}

class NoOpDeadlineNotificationScheduler : DeadlineNotificationScheduler {
    override suspend fun requestPermission(): Boolean = true
    override suspend fun schedule(task: AcademicTask, offsetsMinutes: List<Int>): Boolean = true
    override suspend fun cancel(taskId: String) = Unit
}
