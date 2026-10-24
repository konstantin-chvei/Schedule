package com.example.bruschedule.data.remote

import com.example.bruschedule.domain.repository.DailyMessagesRemoteDataSource
import kotlinx.coroutines.CancellationException

class GitHubDailyMessagesRemoteDataSource(
    private val transport: HtmlTransport,
) : DailyMessagesRemoteDataSource {
    override suspend fun fetchMessages(): List<String> {
        var lastFailure: Throwable? = null
        for (url in DAILY_MESSAGES_URLS) {
            try {
                return transport.get(url)
                    .lineSequence()
                    .map { it.removePrefix("\uFEFF").trim() }
                    .filter { it.isNotEmpty() && !it.startsWith('#') }
                    .take(100)
                    .toList()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                lastFailure = failure
            }
        }
        throw lastFailure ?: IllegalStateException("Не удалось загрузить сообщения GitHub Pages")
    }

    companion object {
        const val DAILY_MESSAGES_URL =
            "${GitHubScheduleDataPaths.PAGES_URL}content/daily-messages.txt"
        const val LEGACY_DAILY_MESSAGES_URL =
            "${GitHubScheduleDataPaths.LEGACY_PAGES_URL}content/daily-messages.txt"
        val DAILY_MESSAGES_URLS = listOf(DAILY_MESSAGES_URL, LEGACY_DAILY_MESSAGES_URL)

        fun isDailyMessagesUrl(url: String): Boolean = url in DAILY_MESSAGES_URLS
    }
}
