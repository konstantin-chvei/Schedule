package com.example.bruschedule.data

import android.content.Context
import androidx.activity.ComponentActivity
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.example.bruschedule.data.local.db.ScheduleDatabase
import com.example.bruschedule.domain.repository.NoOpDeadlineNotificationScheduler
import com.example.bruschedule.notifications.AndroidDeadlineNotificationScheduler

fun createAndroidAppGraph(context: Context): AppGraph = createAppGraph(
    driver = AndroidSqliteDriver(
        schema = ScheduleDatabase.Schema,
        context = context.applicationContext,
        name = "bru-schedule.db", // Keep the legacy filename to preserve existing on-device schedules.
    ),
    deadlineNotificationScheduler = (context as? ComponentActivity)
        ?.let(::AndroidDeadlineNotificationScheduler)
        ?: NoOpDeadlineNotificationScheduler(),
)
