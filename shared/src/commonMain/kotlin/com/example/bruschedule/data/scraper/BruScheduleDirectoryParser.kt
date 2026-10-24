package com.example.bruschedule.data.scraper

import com.example.bruschedule.domain.model.ProfileCandidate
import com.example.bruschedule.domain.model.ScheduleProfile
import com.example.bruschedule.domain.model.ScheduleProfileType
import com.example.bruschedule.domain.model.ScheduleWindow
import com.fleeksoft.ksoup.Ksoup
import kotlinx.datetime.LocalDate

data class FacultyDirectory(
    val name: String,
    val url: String,
)

class BruScheduleDirectoryParser {
    fun hasScheduleTable(html: String): Boolean =
        Ksoup.parse(html).select("table.schedule-table").isNotEmpty()

    fun facultyDirectories(html: String, pageUrl: String, type: ScheduleProfileType): List<FacultyDirectory> {
        if (type == ScheduleProfileType.TEACHER) return emptyList()
        val section = if ("part_time" in pageUrl) "part_time" else "full_time"
        val rootPath = "/$section/"
        return Ksoup.parse(html).select("a[href]")
            .mapNotNull { anchor ->
                val absolute = absoluteUrl(pageUrl, anchor.attr("href"))
                if (!absolute.startsWith("$ROOT/", ignoreCase = true)) return@mapNotNull null
                val path = absolute.substringAfter("rasp.bru.by", absolute).substringBefore('#').substringBefore('?')
                val isDatedSchedule = path.contains("_weeks_", ignoreCase = true) || path.contains("_session_", ignoreCase = true)
                val appearsToBeFacultyDirectory = !isDatedSchedule && (
                    path.endsWith("index.html", ignoreCase = true) || path.endsWith('/') ||
                        !path.substringAfterLast('/').contains('.')
                    )
                val isFormRootIndex = path.equals("${rootPath}index.html", ignoreCase = true)
                if (path.startsWith(rootPath, ignoreCase = true) && path != rootPath &&
                    !isFormRootIndex && appearsToBeFacultyDirectory
                ) {
                    val name = normalizeSpaces(anchor.text())
                        .replace(Regex("\\s+Групп:.*$", RegexOption.IGNORE_CASE), "")
                        .removePrefix("🏛️")
                        .trim()
                    FacultyDirectory(name = name.ifBlank { path.substringAfterLast('/').ifBlank { section } }, url = absolute)
                } else {
                    null
                }
            }
            .distinctBy(FacultyDirectory::url)
    }

    fun facultyDirectoryUrls(html: String, pageUrl: String, type: ScheduleProfileType): List<String> =
        facultyDirectories(html, pageUrl, type).map(FacultyDirectory::url)

    fun groupCandidates(
        html: String,
        facultyPageUrl: String,
        facultyNameHint: String? = null,
    ): List<ProfileCandidate> {
        val document = Ksoup.parse(html)
        val facultyName = facultyNameHint?.takeIf(String::isNotBlank)
            ?: document.select("h1").firstOrNull()?.text()
                ?.let(::normalizeSpaces)
                ?.removePrefix("🏛️")
                ?.trim()
                ?.takeUnless { it.contains("форма", ignoreCase = true) }
        return document.select("a.group-link[href], a[href*=_weeks_], a[href*=_session_]")
            .mapNotNull { anchor ->
                val label = normalizeSpaces(anchor.text())
                val name = anchor.select("strong, b").firstOrNull()?.text()
                    ?.let(::normalizeSpaces)
                    ?.takeIf(String::isNotBlank)
                    ?: label.substringBefore('(').split(Regex("\\s+\\d{1,2}\\.\\d{1,2}"), limit = 2)
                        .first().trim().trimEnd('—', '-', '•')
                if (name.isBlank()) return@mapNotNull null
                val period = anchor.select(".period-label").firstOrNull()?.text()?.let(::normalizeSpaces).orEmpty()
                val subtitle = listOfNotNull(facultyName, period.takeIf(String::isNotBlank)).joinToString(" · ")
                ProfileCandidate(
                    type = ScheduleProfileType.GROUP,
                    name = name,
                    sourceUrl = facultyPageUrl,
                    sourceKey = name,
                    subtitle = subtitle,
                )
            }
            .distinctBy { normalizeForMatch(it.name) + "|" + it.sourceUrl }
    }

    fun teacherCandidates(html: String, teachersIndexUrl: String): List<ProfileCandidate> {
        return Ksoup.parse(html).select("a[href]")
            .mapNotNull { anchor ->
                val href = anchor.attr("href")
                if (!href.contains("/teachers/")) return@mapNotNull null
                val label = normalizeSpaces(anchor.text())
                val name = label.replace(Regex("\\s+\\d+\\s*$"), "")
                    .replace(Regex("\\s+(доцент|профессор|ассистент|преподаватель|старший преподаватель).*", RegexOption.IGNORE_CASE), "")
                    .trim()
                if (name.isBlank()) return@mapNotNull null
                ProfileCandidate(
                    type = ScheduleProfileType.TEACHER,
                    name = name,
                    sourceUrl = absoluteUrl(teachersIndexUrl, href),
                    sourceKey = href,
                    subtitle = label.removePrefix(name).trim(),
                )
            }
            .distinctBy { it.sourceUrl }
    }

    fun scheduleUrlFromIndex(
        html: String,
        pageUrl: String,
        profile: ScheduleProfile,
        window: ScheduleWindow? = null,
    ): String? {
        val document = Ksoup.parse(html)
        val key = normalizeForMatch(profile.sourceKey?.takeIf(String::isNotBlank) ?: profile.name)
        val anchors = document.select("a[href]")
        val candidates = anchors.mapNotNull { anchor ->
            val href = anchor.attr("href")
            val isCandidate = when (profile.type) {
                ScheduleProfileType.GROUP ->
                    href.contains("_weeks_", ignoreCase = true) || href.contains("_session_", ignoreCase = true) ||
                        anchor.attr("class").split(Regex("\\s+")).contains("group-link")
                ScheduleProfileType.TEACHER -> href.contains("/teachers/", ignoreCase = true)
            }
            if (!isCandidate || key.isBlank()) return@mapNotNull null

            val label = normalizeForMatch(anchor.text())
            val path = normalizeForMatch(percentDecode(href))
            val exactGroupMatch = if (profile.type == ScheduleProfileType.GROUP) {
                val visibleName = anchor.select("strong").firstOrNull()?.text()
                    ?.let(::normalizeSpaces)
                    ?: normalizeSpaces(anchor.text()).substringBefore('(')
                        .split(GROUP_DATE_SUFFIX, limit = 2).first().trim()
                val fileName = href.substringBefore('#').substringBefore('?').substringAfterLast('/')
                val pathName = percentDecode(fileName)
                    .substringBeforeIgnoreCase("_weeks_")
                    .substringBeforeIgnoreCase("_session_")
                normalizeForMatch(visibleName) == key || normalizeForMatch(pathName) == key
            } else {
                false
            }
            val labelNameKey = normalizeForMatch(profile.name.trim().substringBefore(' '))
            val matches = when (profile.type) {
                ScheduleProfileType.GROUP -> exactGroupMatch
                ScheduleProfileType.TEACHER ->
                    label.contains(key) || key.contains(label) || path.contains(key) ||
                        (labelNameKey.length >= 2 && label.contains(labelNameKey))
            }
            if (matches) absoluteUrl(pageUrl, href) to exactGroupMatch else null
        }
        val exactCandidates = candidates.filter { it.second }
        val orderedCandidates = exactCandidates.ifEmpty { candidates }
        return if (window == null) {
            orderedCandidates.firstOrNull()?.first
        } else {
            orderedCandidates.firstOrNull { linkCoversWindow(it.first, window) }?.first
                ?: orderedCandidates.firstOrNull()?.first
        }
    }

    private fun linkCoversWindow(url: String, window: ScheduleWindow): Boolean {
        val match = WEEK_RANGE.find(percentDecode(url)) ?: return false
        val start = compactDate(match.groupValues[1]) ?: return false
        val end = compactDate(match.groupValues[2]) ?: return false
        return start <= window.endDate && end >= window.startDate
    }

    private fun compactDate(value: String): LocalDate? {
        if (value.length != 8) return null
        return runCatching {
            LocalDate(
                year = value.substring(0, 4).toInt(),
                monthNumber = value.substring(4, 6).toInt(),
                dayOfMonth = value.substring(6, 8).toInt(),
            )
        }.getOrNull()
    }

    fun matchesQuery(candidate: ProfileCandidate, query: String): Boolean {
        val normalizedQuery = normalizeForMatch(query)
        if (normalizedQuery.isBlank()) return true
        val normalizedName = normalizeForMatch(candidate.name)
        if (candidate.type == ScheduleProfileType.GROUP) return normalizedName.contains(normalizedQuery)
        if (normalizedName.contains(normalizedQuery) || normalizedQuery.contains(normalizedName)) return true

        val surname = normalizeForMatch(query.trim().substringBefore(' '))
        return surname.length >= 2 && normalizedName.contains(surname)
    }

    fun isExactMatch(candidate: ProfileCandidate, query: String): Boolean =
        normalizeForMatch(candidate.name) == normalizeForMatch(query)

    fun absolutize(pageUrl: String, href: String): String = absoluteUrl(pageUrl, href)

    private fun absoluteUrl(pageUrl: String, href: String): String {
        val cleanHref = href.trim()
        val absolute = when {
            cleanHref.startsWith("http://", ignoreCase = true) || cleanHref.startsWith("https://", ignoreCase = true) -> cleanHref
            cleanHref.startsWith("//") -> "https:$cleanHref"
            cleanHref.startsWith('/') -> "$ROOT$cleanHref"
            else -> "${pageUrl.substringBeforeLast('/', pageUrl)}/$cleanHref"
        }
        return normalizeSiteUrl(absolute)
    }

    private fun normalizeSiteUrl(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url
        val authorityStart = schemeEnd + 3
        val pathStart = url.indexOf('/', authorityStart)
        val authorityEnd = if (pathStart < 0) url.length else pathStart
        val authority = url.substring(authorityStart, authorityEnd)
        val host = authority.substringBefore(':')
        val isSiteHost = host.equals("rasp.bru.by", ignoreCase = true) ||
            host.equals("www.rasp.bru.by", ignoreCase = true)
        val suffix = if (pathStart < 0) "/" else url.substring(pathStart)
        val canonical = if (isSiteHost) "$ROOT$suffix" else url
        return encodeUrlPath(canonical)
    }

    private fun encodeUrlPath(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url
        val pathStart = url.indexOf('/', schemeEnd + 3)
        if (pathStart < 0) return url
        val suffixStart = listOf(url.indexOf('?', pathStart), url.indexOf('#', pathStart))
            .filter { it >= 0 }
            .minOrNull() ?: url.length
        val pathBytes = url.substring(pathStart, suffixStart).encodeToByteArray()
        val hex = "0123456789ABCDEF"
        return buildString(url.length) {
            append(url.substring(0, pathStart))
            var index = 0
            while (index < pathBytes.size) {
                val value = pathBytes[index].toInt() and 0xff
                val char = value.toChar()
                if (char == '%' && index + 2 < pathBytes.size &&
                    pathBytes[index + 1].toInt().toChar().isHexDigit() &&
                    pathBytes[index + 2].toInt().toChar().isHexDigit()
                ) {
                    append('%')
                    append(pathBytes[index + 1].toInt().toChar())
                    append(pathBytes[index + 2].toInt().toChar())
                    index += 3
                } else if (
                    char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' ||
                    char in "-._~/!\$&'()*+,;=:@"
                ) {
                    append(char)
                    index++
                } else {
                    append('%')
                    append(hex[value ushr 4])
                    append(hex[value and 0x0f])
                    index++
                }
            }
            append(url.substring(suffixStart))
        }
    }

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun String.substringBeforeIgnoreCase(delimiter: String): String {
        val index = lowercase().indexOf(delimiter.lowercase())
        return if (index < 0) this else substring(0, index)
    }

    private fun percentDecode(value: String): String = value
        .replace("%20", " ", ignoreCase = true)
        .replace("%2D", "-", ignoreCase = true)
        .replace("%5F", "_", ignoreCase = true)
        .replace("%28", "(", ignoreCase = true)
        .replace("%29", ")", ignoreCase = true)

    private fun normalizeSpaces(value: String): String = value.replace(Regex("\\s+"), " ").trim()

    private fun normalizeForMatch(value: String): String = value.lowercase()
        .replace('ё', 'е')
        .filter { it.isLetterOrDigit() }

    companion object {
        private val GROUP_DATE_SUFFIX = Regex("\\s+\\d{1,2}\\.\\d{1,2}")
        private val WEEK_RANGE = Regex("_weeks_(20\\d{6})_(20\\d{6})", RegexOption.IGNORE_CASE)
        const val ROOT = "https://rasp.bru.by"
        const val FULL_TIME_INDEX = "$ROOT/full_time/index.html"
        const val PART_TIME_INDEX = "$ROOT/part_time/index.html"
        const val TEACHERS_INDEX = "$ROOT/index1.html"
    }
}
