package com.example.bruschedule.data.remote

import com.example.bruschedule.domain.model.ProfileCandidate
import com.example.bruschedule.domain.model.ScheduleProfileType
import com.example.bruschedule.domain.model.UniversityIds
import com.example.bruschedule.domain.repository.ScheduleProfileCatalogRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

class GitHubScheduleProfileCatalogRepository(
    private val transport: HtmlTransport,
    private val clock: Clock = Clock.System,
    private val cacheTtlMillis: Long = DEFAULT_CACHE_TTL_MILLIS,
) : ScheduleProfileCatalogRepository {
    private data class CachedIndex(val loadedAtMillis: Long, val candidates: List<ProfileCandidate>)

    private val cache = mutableMapOf<String, CachedIndex>()
    private val cacheMutex = Mutex()

    override suspend fun search(type: ScheduleProfileType, query: String): List<ProfileCandidate> =
        search(UniversityIds.BRU, type, query)

    override suspend fun search(
        universityId: String,
        type: ScheduleProfileType,
        query: String,
    ): List<ProfileCandidate> = search(universityId, type, query) { }

    override suspend fun search(
        universityId: String,
        type: ScheduleProfileType,
        query: String,
        onPartialResults: (List<ProfileCandidate>) -> Unit,
    ): List<ProfileCandidate> {
        val cleanQuery = normalize(query)
        require(cleanQuery.isNotBlank()) { "Введите код группы или имя преподавателя" }
        val index = loadIndex(universityId, type)
        val results = index.asSequence()
            .filter { candidate ->
                normalize(candidate.name).contains(cleanQuery) ||
                    normalize(candidate.subtitle).contains(cleanQuery)
            }
            .sortedWith(
                compareBy<ProfileCandidate> { !normalize(it.name).startsWith(cleanQuery) }
                    .thenBy { !it.scheduleAvailable }
                    .thenBy { normalize(it.name) },
            )
            .take(MAX_SEARCH_RESULTS)
            .toList()
        onPartialResults(results)
        return results
    }

    private suspend fun loadIndex(universityId: String, type: ScheduleProfileType): List<ProfileCandidate> {
        val cacheKey = "$universityId|${type.name}"
        val now = clock.now().toEpochMilliseconds()
        return cacheMutex.withLock {
            val cached = cache[cacheKey]
            if (cached != null && now - cached.loadedAtMillis < cacheTtlMillis) {
                return@withLock cached.candidates
            }

            var lastFailure: Throwable? = null
            for (indexUrl in GitHubScheduleDataPaths.indexUrls(universityId, type)) {
                try {
                    val csv = transport.get(indexUrl)
                    val pagesUrl = if (indexUrl.startsWith(GitHubScheduleDataPaths.LEGACY_PAGES_URL)) {
                        GitHubScheduleDataPaths.LEGACY_PAGES_URL
                    } else {
                        GitHubScheduleDataPaths.PAGES_URL
                    }
                    val candidates = parseIndex(universityId, type, csv, pagesUrl)
                    cache[cacheKey] = CachedIndex(now, candidates)
                    return@withLock candidates
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    lastFailure = failure
                }
            }
            cached?.let { return@withLock it.candidates }
            val detail = lastFailure?.message?.takeIf(String::isNotBlank)
            error(
                if (detail == null) {
                    "Не удалось открыть каталог расписаний GitHub. Проверьте подключение и повторите поиск"
                } else {
                    "Не удалось открыть каталог расписаний GitHub: $detail"
                },
            )
        }
    }

    private fun parseIndex(
        universityId: String,
        type: ScheduleProfileType,
        content: String,
        pagesUrl: String,
    ): List<ProfileCandidate> {
        val rows = ScheduleCsvTable.parse(content)
        if (rows.isEmpty()) return emptyList()
        val header = rows.first().map(::normalizeHeader)
        val columns = IndexColumns(header)
        val idColumn = columns.required("id", "slug")
        val nameColumn = columns.required("name", "label")
        val subtitleColumn = columns.find("subtitle", "faculty", "detail")
        val pathColumn = columns.find("schedule_path", "schedule", "path")
        val statusColumn = columns.find("status", "availability")
        val updatedColumn = columns.find("updated_at", "updated")
        val sourceKeyColumn = columns.find("source_key", "profile_key", "key")
        val expectedFolder = if (type == ScheduleProfileType.GROUP) "groups" else "teachers"

        return rows.drop(1).mapNotNull { row ->
            val id = row.valueAt(idColumn).trim()
            val name = row.valueAt(nameColumn).trim()
            if (id.isBlank() || name.isBlank()) return@mapNotNull null
            val path = row.valueAt(pathColumn).trim()
            val safePath = path.ifBlank { "schedules/$expectedFolder/$id.html" }
            val scheduleUrl = GitHubScheduleDataPaths.scheduleUrl(universityId, type, safePath, pagesUrl)
            val status = row.valueAt(statusColumn).trim().lowercase()
            val available = when {
                status.isBlank() -> path.isNotBlank()
                status in READY_STATUSES -> true
                else -> false
            }
            val details = listOfNotNull(
                row.valueAt(subtitleColumn).trim().takeIf(String::isNotBlank),
                row.valueAt(updatedColumn).trim().takeIf(String::isNotBlank)?.let { "обновлено $it" },
                if (available) "Расписание доступно" else "Расписание пока недоступно",
            ).joinToString(" · ")
            ProfileCandidate(
                type = type,
                name = name,
                sourceUrl = scheduleUrl,
                sourceKey = row.valueAt(sourceKeyColumn).trim().ifBlank { id },
                subtitle = details,
                universityId = universityId,
                scheduleAvailable = available,
            )
        }
    }

    private fun normalize(value: String): String = value
        .trim()
        .lowercase()
        .replace('ё', 'е')
        .replace(WHITESPACE, " ")

    private fun normalizeHeader(value: String): String = value
        .trim()
        .removePrefix("\uFEFF")
        .lowercase()
        .replace('ё', 'е')
        .replace(Regex("""[\s-]+"""), "_")

    private fun List<String>.valueAt(index: Int?): String = index?.let { getOrNull(it) }.orEmpty()

    private class IndexColumns(private val header: List<String>) {
        fun find(vararg names: String): Int? {
            val aliases = names.map { it.trim().lowercase().replace('ё', 'е').replace(Regex("""[\s-]+"""), "_") }
            return header.indexOfFirst { it in aliases }.takeIf { it >= 0 }
        }

        fun required(vararg names: String): Int = find(*names)
            ?: error("В каталоге GitHub отсутствует столбец ${names.first()}")
    }

    private companion object {
        const val MAX_SEARCH_RESULTS = 100
        const val DEFAULT_CACHE_TTL_MILLIS = 12L * 60L * 60L * 1000L
        val READY_STATUSES = setOf("ready", "available", "published")
        val WHITESPACE = Regex("\\s+")
    }
}
