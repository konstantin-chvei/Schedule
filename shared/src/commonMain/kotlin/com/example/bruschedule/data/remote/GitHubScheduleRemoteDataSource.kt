package com.example.bruschedule.data.remote

import com.example.bruschedule.data.scraper.ScheduleDocumentHtmlAdapter
import com.example.bruschedule.domain.model.ScheduleProfile
import com.example.bruschedule.domain.model.ScheduleWindow
import com.example.bruschedule.domain.repository.ScheduleRemoteDataSource
import kotlinx.coroutines.CancellationException

class GitHubScheduleRemoteDataSource(
    private val transport: HtmlTransport,
    private val documentAdapter: ScheduleDocumentHtmlAdapter = ScheduleDocumentHtmlAdapter(),
) : ScheduleRemoteDataSource {
    override suspend fun fetchHtml(profile: ScheduleProfile, window: ScheduleWindow): String {
        val sourceUrl = profile.sourceUrl?.trim()?.takeIf(String::isNotBlank)
            ?: error("У профиля нет файла расписания в GitHub")
        val fileUrls = GitHubScheduleDataPaths.scheduleUrls(sourceUrl)
        require(fileUrls.isNotEmpty()) {
            "Источник этого профиля не является опубликованным файлом расписания GitHub. Найдите группу или преподавателя заново"
        }

        var lastFailure: Throwable? = null
        for (fileUrl in fileUrls) {
            try {
                val documentFormat = documentAdapter.formatForUrl(fileUrl)
                return if (documentFormat == null) {
                    transport.get(fileUrl)
                } else {
                    require(documentFormat.isSupported) {
                        "Формат ${documentFormat.extension} пока не поддерживается в приложении"
                    }
                    val binaryTransport = transport as? BinaryResourceTransport
                        ?: error("Транспорт не поддерживает скачивание файлов расписания из GitHub")
                    documentAdapter.toHtml(documentFormat, binaryTransport.getBytes(fileUrl))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                lastFailure = failure
            }
        }
        val detail = lastFailure?.message?.takeIf(String::isNotBlank) ?: "нет ответа"
        error("Не удалось загрузить опубликованное расписание из GitHub: $detail")
    }
}
