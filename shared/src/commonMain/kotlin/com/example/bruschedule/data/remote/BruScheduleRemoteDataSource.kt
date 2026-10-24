package com.example.bruschedule.data.remote

import com.example.bruschedule.data.scraper.BruScheduleDirectoryParser
import com.example.bruschedule.data.scraper.BsuBiologyScheduleHtmlParser
import com.example.bruschedule.data.scraper.BsuBiologyTeacherPage
import com.example.bruschedule.data.scraper.BsuTeacherProfileSourceKey
import com.example.bruschedule.data.scraper.ScheduleDocumentHtmlAdapter
import com.example.bruschedule.domain.model.ScheduleProfile
import com.example.bruschedule.domain.model.ScheduleProfileType
import com.example.bruschedule.domain.model.UniversityIds
import com.example.bruschedule.domain.model.ScheduleWindow
import com.example.bruschedule.domain.repository.ScheduleRemoteDataSource
import com.fleeksoft.ksoup.Ksoup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus

class BruScheduleRemoteDataSource(
    private val transport: HtmlTransport,
    private val directoryParser: BruScheduleDirectoryParser = BruScheduleDirectoryParser(),
    private val biologyScheduleParser: BsuBiologyScheduleHtmlParser = BsuBiologyScheduleHtmlParser(),
    private val documentAdapter: ScheduleDocumentHtmlAdapter = ScheduleDocumentHtmlAdapter(),
) : ScheduleRemoteDataSource {
    override suspend fun fetchHtml(profile: ScheduleProfile, window: ScheduleWindow): String {
        if (profile.universityId != UniversityIds.BRU) {
            if (profile.universityId == UniversityIds.BSU && profile.type == ScheduleProfileType.TEACHER) {
                val teacherSources = BsuTeacherProfileSourceKey.decode(profile.sourceKey)
                if (teacherSources != null) {
                    if (biologyScheduleParser.isBiologyProfile(profile)) {
                        return fetchBiologyTeacherWeeks(teacherSources.urls, window)
                    }
                    return fetchBsuTeacherPages(teacherSources.urls)
                }
            }
            if (profile.universityId == UniversityIds.BSU && biologyScheduleParser.isBiologyProfile(profile)) {
                return fetchBiologyWeeks(profile, window)
            }
            val timetableUrl = profile.sourceUrl?.trim()?.takeIf(String::isNotBlank)
                ?: error("Для этого вуза укажите прямую ссылку на страницу расписания")
            require(timetableUrl.startsWith("https://", ignoreCase = true)) {
                "Ссылка на расписание должна начинаться с https://"
            }
            val fetchUrl = if (profile.universityId == UniversityIds.BSU) {
                GoogleSheetsScheduleUrl.toHtmlTableUrl(timetableUrl) ?: timetableUrl
            } else {
                timetableUrl
            }
            val documentFormat = documentAdapter.formatForUrl(fetchUrl)
            if (documentFormat != null) {
                require(documentFormat.isSupported) {
                    "Формат ${documentFormat.extension} не поддерживается; используйте PDF, XLSX, DOCX, XLS или DOC"
                }
                val binaryTransport = transport as? BinaryResourceTransport
                    ?: error("Источник не поддерживает загрузку файлов расписания")
                val bytes = binaryTransport.getBytes(documentAdapter.downloadUrl(fetchUrl))
                return documentAdapter.toHtml(documentFormat, bytes)
            }
            return transport.get(fetchUrl)
        }

        var lastNetworkError: String? = null
        suspend fun fetchOrNull(url: String): String? = try {
            transport.get(url)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            lastNetworkError = failure.message?.takeIf(String::isNotBlank) ?: "Неизвестная ошибка сети"
            null
        }

        val configuredUrl = profile.sourceUrl?.takeIf(String::isNotBlank)?.let {
            directoryParser.absolutize(BruScheduleDirectoryParser.ROOT, it)
        }
        var configuredPage: Pair<String, String>? = null
        var failedConfiguredUrl: String? = null
        if (configuredUrl != null) {
            val configuredHtml = fetchOrNull(configuredUrl)
            if (configuredHtml != null) {
                configuredPage = configuredUrl to configuredHtml
                if (directoryParser.hasScheduleTable(configuredHtml)) return configuredHtml

                val scheduleUrl = directoryParser.scheduleUrlFromIndex(
                    html = configuredHtml,
                    pageUrl = configuredUrl,
                    profile = profile,
                    window = window,
                )
                if (scheduleUrl != null) {
                    val scheduleHtml = fetchOrNull(scheduleUrl)
                    if (scheduleHtml != null && directoryParser.hasScheduleTable(scheduleHtml)) return scheduleHtml
                }
            } else {
                failedConfiguredUrl = configuredUrl
            }
        }

        return when (profile.type) {
            ScheduleProfileType.GROUP -> {
                findGroupSchedule(profile, window, configuredPage, failedConfiguredUrl)?.let { return it }
                lastNetworkError?.let { detail ->
                    error("Не удалось найти расписание группы «${profile.name}». Последняя ошибка сайта: $detail")
                }
                error("Группа «${profile.name}» не найдена в каталогах дневной и заочной форм")
            }
            ScheduleProfileType.TEACHER -> {
                val indexUrl = BruScheduleDirectoryParser.TEACHERS_INDEX
                val indexHtml = if (configuredPage?.first == indexUrl) {
                    configuredPage.second
                } else {
                    fetchOrNull(indexUrl)
                } ?: error("Не удалось открыть каталог преподавателей rasp.bru.by: ${lastNetworkError ?: "ответ отсутствует"}")

                val teacherUrl = directoryParser.scheduleUrlFromIndex(
                    html = indexHtml,
                    pageUrl = indexUrl,
                    profile = profile,
                ) ?: error("Преподаватель «${profile.name}» не найден в каталоге rasp.bru.by")
                val teacherHtml = fetchOrNull(teacherUrl)
                    ?: error("Не удалось загрузить страницу преподавателя «${profile.name}»: ${lastNetworkError ?: "ответ отсутствует"}")
                if (!directoryParser.hasScheduleTable(teacherHtml)) {
                    error("На странице преподавателя «${profile.name}» не найдена таблица расписания")
                }
                teacherHtml
            }
        }
    }

    private suspend fun fetchBsuTeacherPages(sourceUrls: List<String>): String {
        require(sourceUrls.isNotEmpty()) { "Для расписания преподавателя не найдены источники факультета" }
        val pages = sourceUrls.distinct().map { sourceUrl ->
            val html = fetchBsuSchedulePage(sourceUrl)
            val document = Ksoup.parse(html)
            val title = document.selectFirst("title")?.text()?.trim().orEmpty()
            val body = document.body()?.html()?.takeIf(String::isNotBlank) ?: html
            buildString {
                append("<section data-bsu-teacher-source=\"true\" data-bsu-teacher-url=\"")
                append(escapeHtmlAttribute(sourceUrl))
                append("\" data-bsu-teacher-title=\"")
                append(escapeHtmlAttribute(title))
                append("\">")
                if (title.isNotBlank()) {
                    append("<h2 class=\"bsu-teacher-source-title\">")
                    append(escapeHtmlAttribute(title))
                    append("</h2>")
                }
                append(body)
                append("</section>")
            }
        }
        return pages.joinToString("\n")
    }

    private fun escapeHtmlAttribute(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private suspend fun fetchBsuSchedulePage(sourceUrl: String): String {
        require(sourceUrl.startsWith("https://", ignoreCase = true)) {
            "Ссылка на расписание преподавателя должна начинаться с https://"
        }
        val fetchUrl = GoogleSheetsScheduleUrl.toHtmlTableUrl(sourceUrl) ?: sourceUrl
        val documentFormat = documentAdapter.formatForUrl(fetchUrl)
        if (documentFormat == null) return transport.get(fetchUrl)
        require(documentFormat.isSupported) {
            "Формат ${documentFormat.extension} не поддерживается для расписания преподавателя"
        }
        val binaryTransport = transport as? BinaryResourceTransport
            ?: error("Источник не поддерживает загрузку файлов расписания преподавателя")
        val bytes = binaryTransport.getBytes(documentAdapter.downloadUrl(fetchUrl))
        return documentAdapter.toHtml(documentFormat, bytes)
    }

    private suspend fun fetchBiologyTeacherWeeks(sourceUrls: List<String>, window: ScheduleWindow): String {
        transport.primeBiologySession()
        val groupSources = sourceUrls.distinct().map { url ->
            require(url.startsWith("https://", ignoreCase = true) &&
                url.contains(BsuBiologyScheduleHtmlParser.BIOLOGY_GROUP_PAGE_PATH, ignoreCase = true)
            ) { "В профиле преподавателя Биофака найдена неподдерживаемая ссылка на группу" }
            val groupId = queryParameter(url, "group")
                ?: error("В источнике преподавателя Биофака не указан код группы")
            BiologyTeacherFetchSource(url, groupId)
        }
        require(groupSources.isNotEmpty()) { "Для преподавателя Биофака не найдены расписания групп" }
        val firstMonday = window.startDate.minus(DatePeriod(days = window.startDate.dayOfWeek.isoDayNumber - 1))
        val throughDate = window.semesterCoverageEndDate ?: window.endDate
        val mondays = buildList {
            var week = firstMonday
            while (week <= throughDate && size < MAX_BIOLOGY_WEEKS) {
                add(week)
                week = week.plus(DatePeriod(days = 7))
            }
        }
        require(mondays.isNotEmpty()) { "Не удалось определить недели расписания преподавателя Биофака" }
        require(mondays.last() <= throughDate || mondays.size < MAX_BIOLOGY_WEEKS) {
            "Период расписания преподавателя Биофака превышает допустимый диапазон"
        }
        val requests = groupSources.flatMap { source ->
            mondays.map { week -> BiologyTeacherFetchRequest(source.groupId, source.url, week) }
        }
        require(requests.size <= MAX_BIOLOGY_TEACHER_PAGE_REQUESTS) {
            "Для расписания преподавателя Биофака найдено слишком много групп; выберите профиль заново"
        }

        val pages = coroutineScope {
            val pending = Channel<BiologyTeacherFetchRequest>(capacity = requests.size)
            requests.forEach { request -> pending.send(request) }
            pending.close()
            val completed = Channel<Pair<BiologyTeacherFetchRequest, Result<String>>>()
            val workers = List(minOf(MAX_BIOLOGY_REQUESTS_IN_FLIGHT, requests.size)) {
                launch(start = CoroutineStart.UNDISPATCHED) {
                    for (request in pending) {
                        val result = try {
                            Result.success(transport.get(withWeekDate(request.sourceUrl, request.week.toString())))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Throwable) {
                            Result.failure(failure)
                        }
                        completed.send(request to result)
                    }
                }
            }
            val resultPages = mutableListOf<BsuBiologyTeacherPage>()
            repeat(requests.size) {
                val (request, result) = completed.receive()
                val html = result.getOrElse { failure ->
                    val detail = failure.message?.takeIf(String::isNotBlank) ?: failure.toString()
                    error("Не удалось загрузить расписание Биофака на неделю ${request.week}: $detail")
                }
                resultPages += BsuBiologyTeacherPage(request.groupId, request.week, html)
            }
            workers.joinAll()
            pending.close()
            completed.close()
            resultPages
        }
        return biologyScheduleParser.combineTeacherPages(pages)
    }

    private suspend fun fetchBiologyWeeks(profile: ScheduleProfile, window: ScheduleWindow): String {
        val configuredSourceUrl = profile.sourceUrl?.trim()?.takeIf(String::isNotBlank)
            ?: error("В профиле Биофака отсутствует ссылка на расписание")
        val sourceUrl = currentBiologySourceUrl(profile, configuredSourceUrl)
        transport.primeBiologySession()
        val firstMonday = window.startDate.minus(DatePeriod(days = window.startDate.dayOfWeek.isoDayNumber - 1))
        val throughDate = window.semesterCoverageEndDate ?: window.endDate
        val mondays = buildList {
            var week = firstMonday
            while (week <= throughDate && size < MAX_BIOLOGY_WEEKS) {
                add(week)
                week = week.plus(DatePeriod(days = 7))
            }
        }
        require(mondays.isNotEmpty()) { "Не удалось определить недели расписания Биофака" }
        require(mondays.last() <= throughDate || mondays.size < MAX_BIOLOGY_WEEKS) {
            "Период расписания Биофака превышает допустимый диапазон"
        }
        val responses = coroutineScope {
            val pendingWeeks = Channel<kotlinx.datetime.LocalDate>(capacity = mondays.size)
            mondays.forEach { pendingWeeks.send(it) }
            pendingWeeks.close()
            val completedWeeks = Channel<Pair<kotlinx.datetime.LocalDate, Result<String>>>()
            val workers = List(minOf(MAX_BIOLOGY_REQUESTS_IN_FLIGHT, mondays.size)) {
                launch(start = CoroutineStart.UNDISPATCHED) {
                    for (week in pendingWeeks) {
                        val result = try {
                            Result.success(transport.get(withWeekDate(sourceUrl, week.toString())))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Throwable) {
                            Result.failure(failure)
                        }
                        completedWeeks.send(week to result)
                    }
                }
            }
            val pages = mutableListOf<Pair<kotlinx.datetime.LocalDate, String>>()
            repeat(mondays.size) {
                val (week, result) = completedWeeks.receive()
                val html = result.getOrElse { failure ->
                    val detail = failure.message?.takeIf(String::isNotBlank) ?: failure.toString()
                    error("Не удалось загрузить расписание Биофака на неделю $week: $detail")
                }
                pages += week to html
            }
            workers.joinAll()
            pendingWeeks.close()
            completedWeeks.close()
            pages
        }
        return biologyScheduleParser.combineWeeklyPages(responses)
    }

    private fun currentBiologySourceUrl(profile: ScheduleProfile, configuredUrl: String): String {
        if (biologyScheduleParser.isGroupPageProfile(profile)) return configuredUrl
        val groupNumber = queryParameter(configuredUrl, "group")
            ?: BIOLOGY_GROUP_NUMBER.find(profile.name)?.value
            ?: error("Профиль Биофака устарел. Выберите группу заново в поиске")
        val course = queryParameter(configuredUrl, "course")
            ?: error("В профиле Биофака не указан курс. Выберите группу заново в поиске")
        val encodedUrl = configuredUrl.lowercase()
        val studyValue = queryParameter(configuredUrl, "study")
            ?: queryParameter(configuredUrl, "study_mode")
        val study = when {
            studyValue?.equals("d", ignoreCase = true) == true ||
                studyValue?.contains("днев", ignoreCase = true) == true || "%d0%94".lowercase() in encodedUrl -> "d"
            studyValue?.equals("z", ignoreCase = true) == true ||
                studyValue?.contains("заоч", ignoreCase = true) == true || "%d0%97" in encodedUrl -> "z"
            studyValue?.equals("m", ignoreCase = true) == true ||
                studyValue?.contains("магистр", ignoreCase = true) == true || "%d0%9c" in encodedUrl -> "m"
            else -> error("В профиле Биофака не указан режим обучения. Выберите группу заново в поиске")
        }
        return "${BsuBiologyScheduleHtmlParser.BIOLOGY_SCHEDULE_URL}?study=$study&course=$course" +
            "&group=${encodeQueryComponent(groupNumber)}"
    }

    private fun queryParameter(url: String, key: String): String? =
        url.substringAfter('?', "").substringBefore('#').split('&')
            .firstOrNull { it.substringBefore('=') == key }
            ?.substringAfter('=', "")
            ?.trim()
            ?.takeIf(String::isNotBlank)

    private fun encodeQueryComponent(value: String): String = buildString {
        val hex = "0123456789ABCDEF"
        value.encodeToByteArray().forEach { byte ->
            val code = byte.toInt() and 0xff
            val safe = code in 'a'.code..'z'.code || code in 'A'.code..'Z'.code ||
                code in '0'.code..'9'.code || code == '-'.code || code == '_'.code ||
                code == '.'.code || code == '~'.code
            if (safe) append(code.toChar())
            else append('%').append(hex[code shr 4]).append(hex[code and 0x0f])
        }
    }

    private fun withWeekDate(url: String, weekDate: String): String {
        val fragmentIndex = url.indexOf('#')
        val fragment = if (fragmentIndex >= 0) url.substring(fragmentIndex) else ""
        val withoutFragment = if (fragmentIndex >= 0) url.substring(0, fragmentIndex) else url
        val queryIndex = withoutFragment.indexOf('?')
        val base = if (queryIndex >= 0) withoutFragment.substring(0, queryIndex) else withoutFragment
        val parameters = if (queryIndex >= 0) {
            withoutFragment.substring(queryIndex + 1).split('&').filter(String::isNotBlank).toMutableList()
        } else {
            mutableListOf()
        }
        val dateParameter = if (url.contains(BsuBiologyScheduleHtmlParser.BIOLOGY_GROUP_PAGE_PATH, ignoreCase = true)) {
            "date"
        } else {
            "week_date"
        }
        val dateParameterIndex = parameters.indexOfFirst { it.substringBefore('=') == dateParameter }
        if (dateParameterIndex >= 0) {
            parameters[dateParameterIndex] = "$dateParameter=$weekDate"
        } else {
            parameters += "$dateParameter=$weekDate"
        }
        return "$base?${parameters.joinToString("&")}$fragment"
    }

    private suspend fun findGroupSchedule(
        profile: ScheduleProfile,
        window: ScheduleWindow,
        configuredPage: Pair<String, String>? = null,
        failedConfiguredUrl: String? = null,
    ): String? {
        var lastNetworkError: String? = null
        suspend fun fetchOrNull(url: String): String? = try {
            transport.get(url)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            lastNetworkError = failure.message?.takeIf(String::isNotBlank) ?: "Неизвестная ошибка сети"
            null
        }

        val roots = listOf(
            BruScheduleDirectoryParser.FULL_TIME_INDEX,
            BruScheduleDirectoryParser.PART_TIME_INDEX,
        )
        for (root in roots) {
            if (root == failedConfiguredUrl) continue
            val rootHtml = if (configuredPage?.first == root) configuredPage.second else fetchOrNull(root) ?: continue
            val rootLink = directoryParser.scheduleUrlFromIndex(rootHtml, root, profile, window)
            if (rootLink != null) {
                val rootSchedule = fetchOrNull(rootLink)
                if (rootSchedule != null && directoryParser.hasScheduleTable(rootSchedule)) return rootSchedule
            }

            val facultyPages = directoryParser.facultyDirectories(
                html = rootHtml,
                pageUrl = root,
                type = ScheduleProfileType.GROUP,
            )
            for (faculty in facultyPages) {
                if (faculty.url == failedConfiguredUrl) continue
                val facultyHtml = if (configuredPage?.first == faculty.url) {
                    configuredPage.second
                } else {
                    fetchOrNull(faculty.url) ?: continue
                }
                val scheduleUrl = directoryParser.scheduleUrlFromIndex(facultyHtml, faculty.url, profile, window) ?: continue
                val scheduleHtml = fetchOrNull(scheduleUrl) ?: continue
                if (directoryParser.hasScheduleTable(scheduleHtml)) return scheduleHtml
            }
        }
        lastNetworkError?.let { detail ->
            error("Ошибка доступа к каталогам расписаний rasp.bru.by: $detail")
        }
        return null
    }

    private data class BiologyTeacherFetchSource(
        val url: String,
        val groupId: String,
    )

    private data class BiologyTeacherFetchRequest(
        val groupId: String,
        val sourceUrl: String,
        val week: kotlinx.datetime.LocalDate,
    )

    private companion object {
        const val MAX_BIOLOGY_WEEKS = 54
        const val MAX_BIOLOGY_REQUESTS_IN_FLIGHT = 6
        const val MAX_BIOLOGY_TEACHER_PAGE_REQUESTS = 800
        val BIOLOGY_GROUP_NUMBER = Regex("\\d+[A-Za-zА-Яа-яЁё]?")
    }
}
