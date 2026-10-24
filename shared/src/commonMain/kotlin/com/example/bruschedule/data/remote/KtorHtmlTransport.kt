package com.example.bruschedule.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess

class KtorHtmlTransport(
    private val client: HttpClient,
) : HtmlTransport, BinaryResourceTransport {
    fun close() = client.close()

    override suspend fun get(url: String): String {
        val isScheduleDataFile = GitHubScheduleDataPaths.isPublishedDataUrl(url)
        val isDailyMessagesFile = GitHubDailyMessagesRemoteDataSource.isDailyMessagesUrl(url)
        require(isScheduleDataFile || isDailyMessagesFile) {
            "Разрешены только опубликованные файлы GitHub Pages; сайты вузов не запрашиваются"
        }
        val response = client.get(url) {
            header(HttpHeaders.UserAgent, "Schedule/1.0 (student timetable app)")
            header(
                HttpHeaders.Accept,
                if (isDailyMessagesFile) "text/plain,text/*;q=0.9,*/*;q=0.8" else "text/html,application/xhtml+xml",
            )
            header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
        }
        if (!response.status.isSuccess()) {
            val host = if (isDailyMessagesFile) "GitHub" else url.substringAfter("://").substringBefore('/')
            error("$host ответил кодом ${response.status.value}")
        }
        return response.bodyAsText()
    }

    override suspend fun getBytes(url: String): ByteArray {
        require(GitHubScheduleDataPaths.isScheduleFileUrl(url)) {
            "Разрешены только файлы расписаний из GitHub Pages; сайты вузов не запрашиваются"
        }
        val response = client.get(url) {
            header(HttpHeaders.UserAgent, "Schedule/1.0 (student timetable app)")
            header(HttpHeaders.Accept, "application/octet-stream,application/pdf,*/*")
        }
        if (!response.status.isSuccess()) {
            val host = url.substringAfter("://").substringBefore('/')
            error("$host ответил кодом ${response.status.value}")
        }
        return response.bodyAsBytes().also { bytes ->
            require(bytes.size <= MAX_BINARY_RESPONSE_BYTES) {
                "Файл расписания превышает допустимый размер 30 МБ"
            }
        }
    }

    private companion object {
        const val MAX_BINARY_RESPONSE_BYTES = 30 * 1024 * 1024
    }
}
