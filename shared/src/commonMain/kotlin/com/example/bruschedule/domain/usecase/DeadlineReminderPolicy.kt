package com.example.bruschedule.domain.usecase

import com.example.bruschedule.domain.model.AcademicTask
import com.example.bruschedule.domain.model.DeadlineReminderPreferences
import com.example.bruschedule.domain.model.TaskReminderMode

object DeadlineReminderPolicy {
    fun effectiveOffsets(
        task: AcademicTask,
        preferences: DeadlineReminderPreferences,
    ): List<Int> {
        if (task.isCompleted || !preferences.enabled || task.reminderMode == TaskReminderMode.NONE) return emptyList()
        val offsets = when (task.reminderMode) {
            TaskReminderMode.INHERIT -> preferences.offsetsMinutes
            TaskReminderMode.CUSTOM -> task.reminderOffsetsMinutes
            TaskReminderMode.NONE -> emptyList()
        }
        return offsets.filter { it > 0 }.distinct().sortedDescending()
    }
}
