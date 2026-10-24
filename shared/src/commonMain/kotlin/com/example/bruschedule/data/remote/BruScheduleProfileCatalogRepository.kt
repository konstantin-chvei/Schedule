package com.example.bruschedule.data.remote

import com.example.bruschedule.data.scraper.BruScheduleDirectoryParser
import com.example.bruschedule.domain.model.ProfileCandidate
import com.example.bruschedule.domain.model.ScheduleProfileType
import com.example.bruschedule.domain.model.UniversityIds
import com.example.bruschedule.domain.repository.ScheduleProfileCatalogRepository
import kotlinx.coroutines.CancellationException

class BruScheduleProfileCatalogRepository(
    private val transport: HtmlTransport,
    private val parser: BruScheduleDirectoryParser = BruScheduleDirectoryParser(),
) : ScheduleProfileCatalogRepository {
    override suspend fun search(
        universityId: String,
        type: ScheduleProfileType,
        query: String,
    ): List<ProfileCandidate> {
        require(universityId == UniversityIds.BRU) { "Каталог расписаний БРУ не подходит выбранному вузу" }
        return search(type, query)
    }

    override suspend fun search(type: ScheduleProfileType, query: String): List<ProfileCandidate> {
        require(query.trim().length >= 2) { "Введите не менее двух символов для поиска" }
        val candidates = when (type) {
            ScheduleProfileType.TEACHER -> searchTeachers()
            ScheduleProfileType.GROUP -> searchGroups(query.trim())
        }
        return filterCandidates(candidates, query)
            .distinctBy { "${it.type}|${it.sourceUrl}|${it.sourceKey}" }
            .take(40)
    }

    /** Discovers the complete published BRU group/teacher directory for static GitHub export. */
    suspend fun discoverAll(type: ScheduleProfileType): List<ProfileCandidate> {
        val candidates = when (type) {
            ScheduleProfileType.TEACHER -> searchTeachers()
            ScheduleProfileType.GROUP -> discoverAllGroups()
        }
        return candidates.distinctBy { "${it.type}|${it.sourceUrl}|${it.sourceKey}" }
            .sortedWith(compareBy({ it.name.lowercase() }, { it.subtitle }, { it.sourceUrl }))
    }

    private fun filterCandidates(candidates: List<ProfileCandidate>, query: String): List<ProfileCandidate> =
        candidates.filter { parser.matchesQuery(it, query) }
            .sortedBy { if (parser.isExactMatch(it, query)) 0 else 1 }

    private suspend fun searchTeachers(): List<ProfileCandidate> {
        val url = BruScheduleDirectoryParser.TEACHERS_INDEX
        val html = transport.get(url)
        return parser.teacherCandidates(html, url)
    }

    private suspend fun discoverAllGroups(): List<ProfileCandidate> {
        val candidates = mutableListOf<ProfileCandidate>()
        val failures = mutableListOf<String>()
        val roots = listOf(
            BruScheduleDirectoryParser.FULL_TIME_INDEX,
            BruScheduleDirectoryParser.PART_TIME_INDEX,
        )
        for (root in roots) {
            val rootHtml = try {
                transport.get(root)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                failures += root
                continue
            }
            candidates += parser.groupCandidates(rootHtml, root)
            val faculties = parser.facultyDirectories(rootHtml, root, ScheduleProfileType.GROUP)
            for (faculty in faculties) {
                val html = try {
                    transport.get(faculty.url)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    failures += faculty.url
                    continue
                }
                candidates += parser.groupCandidates(html, faculty.url, faculty.name)
            }
        }
        if (candidates.isEmpty() && failures.isNotEmpty()) {
            error("Не удалось загрузить каталоги групп rasp.bru.by (${failures.size} страниц недоступно)")
        }
        return candidates
    }

    private suspend fun searchGroups(query: String): List<ProfileCandidate> {
        val results = mutableListOf<ProfileCandidate>()
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
            val rootHtml = fetchOrNull(root) ?: continue

            val rootMatches = parser.groupCandidates(rootHtml, root).filter { parser.matchesQuery(it, query) }
            val exactRootMatches = rootMatches.filter { parser.isExactMatch(it, query) }
            if (exactRootMatches.isNotEmpty()) return exactRootMatches
            results += rootMatches

            for (faculty in parser.facultyDirectories(rootHtml, root, ScheduleProfileType.GROUP)) {
                val facultyHtml = fetchOrNull(faculty.url) ?: continue
                val facultyMatches = parser.groupCandidates(facultyHtml, faculty.url, faculty.name).filter {
                    parser.matchesQuery(it, query)
                }
                val exactMatches = facultyMatches.filter { parser.isExactMatch(it, query) }
                if (exactMatches.isNotEmpty()) {
                    return exactMatches
                }
                results += facultyMatches
            }
        }
        if (results.isEmpty()) {
            lastNetworkError?.let { detail -> error("Не удалось загрузить каталог групп rasp.bru.by: $detail") }
        }
        return results
    }
}
