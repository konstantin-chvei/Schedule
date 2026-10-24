package com.example.bruschedule

import com.example.bruschedule.domain.model.AcademicTask
import com.example.bruschedule.domain.model.DeadlineReminderPreferences
import com.example.bruschedule.domain.model.TaskReminderMode
import com.example.bruschedule.domain.usecase.DeadlineReminderPolicy
import kotlin.test.Test
import kotlin.test.assertEquals

class DeadlineReminderPolicyTest {
    private val task = AcademicTask(
        id = "task-1",
        taskTitle = "Лабораторная",
        description = "",
        dueDate = 1_900_000_000_000L,
        isCompleted = false,
    )

    @Test
    fun inheritedTasksUseGlobalIntervals() {
        val defaults = DeadlineReminderPreferences(enabled = true, offsetsMinutes = listOf(1_440, 60))

        assertEquals(listOf(1_440, 60), DeadlineReminderPolicy.effectiveOffsets(task, defaults))
    }

    @Test
    fun customTaskIntervalsOverrideGlobalDefaults() {
        val customized = task.copy(
            reminderMode = TaskReminderMode.CUSTOM,
            reminderOffsetsMinutes = listOf(15, 4_320, 15),
        )
        val defaults = DeadlineReminderPreferences(enabled = true, offsetsMinutes = listOf(1_440))

        assertEquals(listOf(4_320, 15), DeadlineReminderPolicy.effectiveOffsets(customized, defaults))
    }

    @Test
    fun completedTasksAndDisabledGlobalRemindersNeverSchedule() {
        val defaults = DeadlineReminderPreferences(enabled = true, offsetsMinutes = listOf(1_440))

        assertEquals(emptyList(), DeadlineReminderPolicy.effectiveOffsets(task.copy(isCompleted = true), defaults))
        assertEquals(
            emptyList(),
            DeadlineReminderPolicy.effectiveOffsets(task, defaults.copy(enabled = false)),
        )
        assertEquals(
            emptyList(),
            DeadlineReminderPolicy.effectiveOffsets(task.copy(reminderMode = TaskReminderMode.NONE), defaults),
        )
    }
}
