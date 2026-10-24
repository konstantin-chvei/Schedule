package com.example.bruschedule.data.settings

import com.example.bruschedule.domain.model.DEFAULT_DAILY_MESSAGES
import com.example.bruschedule.domain.repository.DailyMessagesRemoteDataSource
import com.example.bruschedule.domain.repository.DailyMessagesRepository
import com.russhwolf.settings.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Clock

class KmpSettingsDailyMessagesRepository(
    private val settings: Settings,
    private val remote: DailyMessagesRemoteDataSource,
    private val nowEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : DailyMessagesRepository {
    private val mutableMessages = MutableStateFlow(readCachedMessages())
    private val messages = mutableMessages.asStateFlow()

    override fun observeMessages() = messages

    override suspend fun refresh() {
        val now = nowEpochMillis()
        val lastSuccess = settings.getString(LAST_SUCCESS_KEY, "0").toLongOrNull() ?: 0L
        val lastAttempt = settings.getString(LAST_ATTEMPT_KEY, "0").toLongOrNull() ?: 0L
        if (now - lastSuccess < SUCCESS_TTL_MILLIS || now - lastAttempt < RETRY_TTL_MILLIS) return
        settings.putString(LAST_ATTEMPT_KEY, now.toString())
        try {
            val fetched = remote.fetchMessages().normalizedMessages()
            check(fetched.isNotEmpty()) { "Удалённый список ежедневных фраз пуст" }
            settings.putString(CACHE_KEY, fetched.joinToString("\n"))
            settings.putString(LAST_SUCCESS_KEY, nowEpochMillis().toString())
            mutableMessages.value = fetched
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    private fun readCachedMessages(): List<String> =
        settings.getString(CACHE_KEY, "")
            .split('\n')
            .normalizedMessages()
            .ifEmpty { DEFAULT_DAILY_MESSAGES }

    private fun List<String>.normalizedMessages(): List<String> = asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith('#') && it != REMOVED_DAILY_MESSAGE }
        .take(MAX_CACHED_MESSAGES)
        .toList()

    private companion object {
        const val CACHE_KEY = "daily_messages.cache"
        const val LAST_SUCCESS_KEY = "daily_messages.last_success_epoch_ms"
        const val LAST_ATTEMPT_KEY = "daily_messages.last_attempt_epoch_ms"
        const val SUCCESS_TTL_MILLIS = 24L * 60L * 60L * 1000L
        const val RETRY_TTL_MILLIS = 60L * 60L * 1000L
        const val MAX_CACHED_MESSAGES = 100
        const val REMOVED_DAILY_MESSAGE = "Если день начался не так — обнови расписание."
    }
}
