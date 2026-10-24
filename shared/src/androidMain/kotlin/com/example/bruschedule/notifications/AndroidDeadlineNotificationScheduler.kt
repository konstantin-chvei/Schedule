package com.example.bruschedule.notifications

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.bruschedule.domain.model.AcademicTask
import com.example.bruschedule.domain.repository.DeadlineNotificationScheduler
import kotlinx.coroutines.suspendCancellableCoroutine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

class AndroidDeadlineNotificationScheduler(
    private val activity: ComponentActivity,
) : DeadlineNotificationScheduler {
    private val appContext = activity.applicationContext
    private var permissionContinuation: kotlinx.coroutines.CancellableContinuation<Boolean>? = null
    private val permissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionContinuation?.let { continuation ->
            permissionContinuation = null
            if (continuation.isActive) continuation.resume(granted)
        }
    }

    override suspend fun requestPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return true

        return suspendCancellableCoroutine { continuation ->
            if (permissionContinuation != null) {
                continuation.resume(false)
                return@suspendCancellableCoroutine
            }
            permissionContinuation = continuation
            continuation.invokeOnCancellation { permissionContinuation = null }
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override suspend fun schedule(task: AcademicTask, offsetsMinutes: List<Int>): Boolean {
        cancel(task.id)
        if (task.isCompleted || offsetsMinutes.isEmpty()) return true
        if (!hasPermission()) return false

        createNotificationChannel(appContext)
        val now = System.currentTimeMillis()
        val dateLabel = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(task.dueDate))
        offsetsMinutes.distinct().forEach { offset ->
            val triggerAt = task.dueDate - offset * 60_000L
            if (triggerAt > now) {
                val key = reminderKey(task.id, triggerAt)
                val title = "Скоро дедлайн"
                val body = "${task.taskTitle} · срок $dateLabel"
                appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                    .edit().putString(key, "$title\u001f$body").apply()
                scheduleAlarm(appContext, key, title, body, triggerAt)
            }
        }
        return true
    }

    override suspend fun cancel(taskId: String) {
        val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val prefix = "deadline.$taskId."
        preferences.all.keys.filter { it.startsWith(prefix) }.forEach { key ->
            cancelAlarm(appContext, key)
            appContext.getSystemService(NotificationManager::class.java).cancel(key.hashCode())
            preferences.edit().remove(key).apply()
        }
    }

    private fun hasPermission(): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

    private companion object {
        const val PREFERENCES_NAME = "deadline_notification_alarms"
    }
}

private const val CHANNEL_ID = "deadline_reminders"
private const val EXTRA_KEY = "deadline_key"
private const val EXTRA_TITLE = "deadline_title"
private const val EXTRA_BODY = "deadline_body"
private const val REMINDER_PREFIX = "deadline."

private fun reminderKey(taskId: String, triggerAt: Long): String = "$REMINDER_PREFIX$taskId.$triggerAt"

private fun createNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Дедлайны", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Напоминания о сроках учебных заданий"
            },
        )
    }
}

private fun alarmPendingIntent(context: Context, key: String, title: String, body: String): PendingIntent {
    val intent = Intent(context, DeadlineAlarmReceiver::class.java)
        .setAction("com.example.bruschedule.DEADLINE.$key")
        .putExtra(EXTRA_KEY, key)
        .putExtra(EXTRA_TITLE, title)
        .putExtra(EXTRA_BODY, body)
    return PendingIntent.getBroadcast(
        context,
        key.hashCode(),
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

private fun scheduleAlarm(context: Context, key: String, title: String, body: String, triggerAt: Long) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    alarmManager.setAndAllowWhileIdle(
        AlarmManager.RTC_WAKEUP,
        triggerAt,
        alarmPendingIntent(context, key, title, body),
    )
}

private fun cancelAlarm(context: Context, key: String) {
    val pendingIntent = alarmPendingIntent(context, key, "", "")
    (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pendingIntent)
    pendingIntent.cancel()
}

class DeadlineAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val body = intent.getStringExtra(EXTRA_BODY).orEmpty()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            context.getSharedPreferences("deadline_notification_alarms", Context.MODE_PRIVATE)
                .edit().remove(key).apply()
            return
        }
        createNotificationChannel(context)
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            Notification.Builder(context)
        }
        val notification = builder
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .build()
        val notificationId = key.hashCode()
        context.getSystemService(NotificationManager::class.java).notify(notificationId, notification)
        context.getSharedPreferences("deadline_notification_alarms", Context.MODE_PRIVATE)
            .edit().remove(key).apply()
    }
}

class DeadlineBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val preferences = context.getSharedPreferences("deadline_notification_alarms", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        preferences.all.forEach { (key, value) ->
            if (!key.startsWith(REMINDER_PREFIX)) return@forEach
            val triggerAt = key.substringAfterLast('.').toLongOrNull() ?: return@forEach
            if (triggerAt <= now) {
                preferences.edit().remove(key).apply()
                return@forEach
            }
            val parts = (value as? String).orEmpty().split('\u001f', limit = 2)
            val title = parts.getOrNull(0).orEmpty()
            val body = parts.getOrNull(1).orEmpty()
            createNotificationChannel(context)
            scheduleAlarm(context, key, title, body, triggerAt)
        }
    }
}
