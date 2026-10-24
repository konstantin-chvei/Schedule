package com.example.bruschedule.tools

import com.example.bruschedule.data.remote.BinaryResourceTransport
import com.example.bruschedule.data.remote.BruScheduleProfileCatalogRepository
import com.example.bruschedule.data.remote.BruScheduleRemoteDataSource
import com.example.bruschedule.data.remote.BsuScheduleProfileCatalogRepository
import com.example.bruschedule.data.remote.HtmlTransport
import com.example.bruschedule.data.scraper.BsuBiologyScheduleHtmlParser
import com.example.bruschedule.data.scraper.UniversityScheduleHtmlParser
import com.example.bruschedule.domain.model.DefaultUniversities
import com.example.bruschedule.domain.model.Lesson
import com.example.bruschedule.domain.model.ScheduleProfile
import com.example.bruschedule.domain.model.ScheduleProfileType
import com.example.bruschedule.domain.model.ScheduleWindow
import com.example.bruschedule.domain.model.SemesterSchedulePreferences
import com.example.bruschedule.domain.model.University
import com.example.bruschedule.domain.model.UniversityIds
import com.example.bruschedule.domain.usecase.ScheduleCycleExpander
import com.example.bruschedule.domain.usecase.SemesterCoveragePolicy
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * One-shot, explicit GitHub dataset import. Normal app builds/tests never contact university sites.
 * Run via the manual "Import schedule datasets" GitHub Actions workflow.
 */
class ScheduleDataImporterTest {
    @Test
    fun csvWriterEscapesQuotesAndCommasAndNormalizesLineBreaks() {
        assertEquals("\"Алгебра, геометрия\"", csvCell("Алгебра, геометрия"))
        assertEquals("\"Преподаватель \"\"И.И.\"\"\"", csvCell("Преподаватель \"И.И.\""))
        assertEquals("строка 1 строка 2", csvCell("строка 1\nстрока 2"))
        assertEquals("Ошибка [source URL] HTTP 403", redactUrls("Ошибка https://example.org/secret?key=abc HTTP 403"))
    }

    @Test
    fun importAllPublishedSchedulesWhenExplicitlyEnabled() {
        if (System.getenv("SCHEDULE_DATA_IMPORT") != "true") return

        val repositoryRoot = Path.of(
            System.getenv("SCHEDULE_REPOSITORY_ROOT")
                ?: error("SCHEDULE_REPOSITORY_ROOT is required for the explicit data import"),
        ).toAbsolutePath().normalize()
        val dataRoot = Path.of(
            System.getenv("SCHEDULE_DATA_ROOT")
                ?: repositoryRoot.resolve("schedule-data").toString(),
        ).toAbsolutePath().normalize()
        val selectedUniversity = System.getenv("SCHEDULE_DATA_UNIVERSITY")?.trim()?.lowercase(Locale.ROOT)
            ?.takeIf(String::isNotBlank)

        val transport = UniversitySourceHttpTransport()
        try {
            val summary = runBlocking {
                UniversalScheduleDatasetImporter(
                    transport = transport,
                    repositoryRoot = repositoryRoot,
                    dataRoot = dataRoot,
                ).import(selectedUniversity)
            }
            println(
                "Schedule data import complete: " +
                    "${summary.discovered} discovered, ${summary.imported} parsed profiles, " +
                    "${summary.preserved} preserved, ${summary.skipped} skipped; files are ready for the GitHub catalog.",
            )
            assertTrue("The import must produce at least one ready profile", summary.imported + summary.preserved > 0)
        } finally {
            transport.close()
        }
    }
}

private data class DatasetImportSummary(
    val discovered: Int,
    val imported: Int,
    val preserved: Int,
    val skipped: Int,
)

private data class CatalogEntry(
    val id: String,
    val name: String,
    val subtitle: String,
    val sourceKey: String,
    val schedulePath: String,
    val status: String,
    val updatedAt: String,
)

private data class FailedImport(
    val universityId: String,
    val profileType: String,
    val profileName: String,
    val reason: String,
)

private class UniversalScheduleDatasetImporter(
    private val transport: UniversitySourceHttpTransport,
    private val repositoryRoot: Path,
    private val dataRoot: Path,
) {
    private val remote = BruScheduleRemoteDataSource(transport)
    private val parser = UniversityScheduleHtmlParser()
    private val preferences = SemesterSchedulePreferences()
    private val failedImports = mutableListOf<FailedImport>()
    private var discoveredCount = 0
    private var importedCount = 0
    private var preservedCount = 0

    suspend fun import(selectedUniversityId: String?): DatasetImportSummary {
        val universities = when (selectedUniversityId) {
            null, "", "all" -> listOf(DefaultUniversities.BRU, DefaultUniversities.BSU)
            UniversityIds.BRU -> listOf(DefaultUniversities.BRU)
            UniversityIds.BSU -> listOf(DefaultUniversities.BSU)
            else -> error("Unsupported university selection: $selectedUniversityId")
        }
        require(dataRoot.startsWith(repositoryRoot) && dataRoot != repositoryRoot) {
            "SCHEDULE_DATA_ROOT must point to a directory inside the checked-out repository"
        }
        Files.createDirectories(dataRoot)
        val allPublishedUniversities = listOf(DefaultUniversities.BRU, DefaultUniversities.BSU)
        val rootEntries = allPublishedUniversities.map { university ->
            listOf(university.id, university.name, university.websiteUrl, "true")
        }
        writeCsv(dataRoot.resolve("universities.csv"), listOf("id", "name", "website_url", "enabled"), rootEntries)

        for (university in universities) {
            val groups = when (university.id) {
                UniversityIds.BRU -> BruScheduleProfileCatalogRepository(transport)
                    .discoverAll(ScheduleProfileType.GROUP)
                UniversityIds.BSU -> BsuScheduleProfileCatalogRepository(transport)
                    .discoverAll(ScheduleProfileType.GROUP)
                else -> error("No parser for ${university.id}")
            }
            val teachers = when (university.id) {
                UniversityIds.BRU -> BruScheduleProfileCatalogRepository(transport)
                    .discoverAll(ScheduleProfileType.TEACHER)
                UniversityIds.BSU -> BsuScheduleProfileCatalogRepository(transport)
                    .discoverAll(ScheduleProfileType.TEACHER)
                else -> error("No parser for ${university.id}")
            }
            discoveredCount += groups.size + teachers.size
            println("${university.id}: discovered ${groups.size} groups and ${teachers.size} teachers")
            importType(university, ScheduleProfileType.GROUP, groups)
            importType(university, ScheduleProfileType.TEACHER, teachers)
            writeUniversityMetadata(university)
        }

        writeImportReport(universities.map(University::id))
        return DatasetImportSummary(
            discovered = discoveredCount,
            imported = importedCount,
            preserved = preservedCount,
            skipped = failedImports.size,
        )
    }

    private suspend fun importType(
        university: University,
        type: ScheduleProfileType,
        candidates: List<com.example.bruschedule.domain.model.ProfileCandidate>,
    ) {
        val typeDirectory = type.directoryName()
        val indexPath = dataRoot.resolve("universities/${university.id}/$typeDirectory/index.csv")
        val existing = readIndex(indexPath)
        val existingIds = existing.mapTo(mutableSetOf(), CatalogEntry::id)
        val usedIds = mutableSetOf<String>()
        val output = linkedMapOf<String, CatalogEntry>()
        val sortedCandidates = candidates
            .distinctBy { "${it.name}|${it.subtitle}|${it.sourceUrl}|${it.sourceKey}" }
            .sortedWith(compareBy({ it.name.lowercase(Locale.ROOT) }, { it.subtitle }, { it.sourceUrl }))

        var processedForType = 0
        for (candidate in sortedCandidates) {
            val old = findExisting(candidate.name, candidate.subtitle, candidate.sourceKey, existing, usedIds)
            val id = old?.id ?: uniqueId(
                candidate.name,
                candidate.sourceUrl,
                candidate.sourceKey,
                usedIds + existingIds,
            )
            usedIds += id
            val relativePath = "schedules/$typeDirectory/$id.csv"
            val profile = ScheduleProfile(
                id = id,
                type = type,
                name = candidate.name,
                sourceUrl = candidate.sourceUrl,
                sourceKey = candidate.sourceKey,
                universityId = university.id,
            )
            try {
                val scheduleWindow = scheduleWindow(university.id, id)
                val sourceHtml = remote.fetchHtml(profile, scheduleWindow)
                val parsed = parser.parse(profile, sourceHtml, scheduleWindow.startDate)
                val expanded = expandLessons(profile, sourceHtml, parsed, scheduleWindow)
                require(expanded.isNotEmpty()) { "В источнике не осталось пар в выбранном периоде" }
                val csv = serializeLessons(expanded)
                require(csv.toByteArray(StandardCharsets.UTF_8).size <= MAX_SCHEDULE_BYTES) {
                    "Набор расписания превышает лимит GitHub-файла 30 МБ"
                }
                val outputPath = dataRoot.resolve("universities/${university.id}/$relativePath")
                atomicWrite(outputPath, csv)
                val publicSourceKey = if (isBiologyGroup(candidate)) candidate.sourceKey else id
                output[id] = CatalogEntry(
                    id = id,
                    name = candidate.name,
                    subtitle = candidate.subtitle,
                    sourceKey = publicSourceKey,
                    schedulePath = relativePath,
                    status = "ready",
                    updatedAt = Instant.now().toString(),
                )
                importedCount++
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val rawDetail = failure.message?.takeIf(String::isNotBlank) ?: failure::class.simpleName.orEmpty()
                val detail = redactUrls(rawDetail).take(300)
                failedImports += FailedImport(university.id, typeDirectory, candidate.name, detail)
                old?.takeIf { isReadyFile(university.id, it) }?.let { existingEntry ->
                    output[existingEntry.id] = existingEntry
                    preservedCount++
                }
                System.err.println("Skipped ${university.id}/$typeDirectory/${candidate.name}: $detail")
            }
            processedForType++
            if (processedForType % PROGRESS_INTERVAL == 0 || processedForType == sortedCandidates.size) {
                println("${university.id}/$typeDirectory: processed $processedForType/${sortedCandidates.size}; " +
                    "$importedCount parsed, $preservedCount preserved, ${failedImports.size} skipped")
            }
            delay(PROFILE_DELAY_MILLIS)
        }

        val discoveredNames = sortedCandidates.map { normalize(it.name) }.toSet()
        existing.filter { entry ->
            entry.status == "ready" && entry.id !in output && normalize(entry.name) !in discoveredNames &&
                isReadyFile(university.id, entry)
        }.forEach { stale ->
            output[stale.id] = stale
            preservedCount++
        }
        writeIndex(indexPath, output.values.sortedBy { it.name.lowercase(Locale.ROOT) })
    }

    private fun scheduleWindow(universityId: String, profileId: String): ScheduleWindow {
        val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        val monday = today.minus(DatePeriod(days = today.dayOfWeek.isoDayNumber - 1))
        val throughDate = SemesterCoveragePolicy.coverageEndDateForUniversity(
            today = today,
            preferences = preferences,
            universityId = universityId,
            profileId = profileId,
        )
        return ScheduleWindow(
            startDate = monday,
            endDate = monday.plus(DatePeriod(days = 13)),
            semesterCoverageEndDate = throughDate,
        )
    }

    private fun expandLessons(
        profile: ScheduleProfile,
        sourceHtml: String,
        parsed: List<Lesson>,
        window: ScheduleWindow,
    ): List<Lesson> {
        val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        val throughDate = checkNotNull(window.semesterCoverageEndDate)
        return when {
            parser.hasAlternatingTwoWeekCycle(profile, sourceHtml) ->
                ScheduleCycleExpander.expand(parsed, today, throughDate)
            parser.hasWeeklyScheduleTemplate(profile, sourceHtml) ->
                ScheduleCycleExpander.expandWeekly(parsed, today, throughDate)
            else -> parsed.filter { lesson ->
                val date = runCatching { LocalDate.parse(lesson.lessonDate) }.getOrNull()
                date != null && date in today..throughDate
            }
        }
    }

    private fun findExisting(
        name: String,
        subtitle: String,
        parserKey: String,
        existing: List<CatalogEntry>,
        alreadyUsed: Set<String>,
    ): CatalogEntry? {
        val matching = existing.filter { it.id !in alreadyUsed && normalize(it.name) == normalize(name) }
        return matching.firstOrNull { it.sourceKey == parserKey }
            ?: matching.firstOrNull { normalize(it.subtitle) == normalize(subtitle) }
            ?: matching.firstOrNull { it.id == slug(name) }
            ?: matching.singleOrNull()
    }

    private fun uniqueId(name: String, sourceUrl: String, parserKey: String, alreadyUsed: Set<String>): String {
        val base = slug(name).ifBlank { "profile" }
        if (base !in alreadyUsed) return base
        val suffix = sha256("$sourceUrl|$parserKey").take(10)
        var id = "${base.take(60 - suffix.length - 1)}-$suffix"
        var counter = 2
        while (id in alreadyUsed) {
            val counterText = counter.toString()
            id = "${base.take(60 - counterText.length - 1)}-$counterText"
            counter++
        }
        return id
    }

    private fun writeIndex(path: Path, entries: List<CatalogEntry>) {
        val fields = listOf("id", "name", "subtitle", "source_key", "schedule_path", "status", "updated_at")
        val rows = entries.map { entry ->
            listOf(entry.id, entry.name, entry.subtitle, entry.sourceKey, entry.schedulePath, entry.status, entry.updatedAt)
        }
        writeCsv(path, fields, rows)
    }

    private fun readIndex(path: Path): List<CatalogEntry> {
        if (!Files.isRegularFile(path)) return emptyList()
        val rows = parseCsv(String(Files.readAllBytes(path), StandardCharsets.UTF_8))
        if (rows.isEmpty()) return emptyList()
        val header = rows.first().map { it.removePrefix("\uFEFF").trim().lowercase(Locale.ROOT) }
        fun indexOf(vararg names: String): Int? = header.indexOfFirst { it in names }.takeIf { it >= 0 }
        val idColumn = indexOf("id") ?: return emptyList()
        val nameColumn = indexOf("name") ?: return emptyList()
        val subtitleColumn = indexOf("subtitle")
        val keyColumn = indexOf("source_key", "key")
        val pathColumn = indexOf("schedule_path", "path")
        val statusColumn = indexOf("status", "availability")
        val updatedColumn = indexOf("updated_at", "updated")
        return rows.drop(1).mapNotNull { row ->
            val id = row.getOrNull(idColumn).orEmpty().trim()
            val name = row.getOrNull(nameColumn).orEmpty().trim()
            if (!SAFE_ID_PATTERN.matches(id) || name.isBlank()) return@mapNotNull null
            CatalogEntry(
                id = id,
                name = name,
                subtitle = row.valueAt(subtitleColumn),
                sourceKey = row.valueAt(keyColumn).ifBlank { id },
                schedulePath = row.valueAt(pathColumn),
                status = row.valueAt(statusColumn),
                updatedAt = row.valueAt(updatedColumn),
            )
        }
    }

    private fun isReadyFile(universityId: String, entry: CatalogEntry): Boolean {
        if (entry.status != "ready") return false
        val relative = entry.schedulePath.replace('\\', '/')
        if (
            relative.startsWith("/") ||
            !(relative.startsWith("schedules/groups/") || relative.startsWith("schedules/teachers/")) ||
            ".." in relative.split('/')
        ) return false
        val path = dataRoot.resolve("universities/$universityId/$relative").normalize()
        return path.startsWith(dataRoot.resolve("universities/$universityId").normalize()) &&
            Files.isRegularFile(path) && Files.size(path) > 0
    }

    private fun writeUniversityMetadata(university: University) {
        val directory = dataRoot.resolve("universities/${university.id}")
        val groups = readIndex(directory.resolve("groups/index.csv")).count { isReadyFile(university.id, it) }
        val teachers = readIndex(directory.resolve("teachers/index.csv")).count { isReadyFile(university.id, it) }
        val json = """
            {
              "schema_version": 1,
              "id": ${json(university.id)},
              "name": ${json(university.name)},
              "website_url": ${json(university.websiteUrl)},
              "generated_at": ${json(Instant.now().toString())},
              "profiles": {"groups": $groups, "teachers": $teachers}
            }
        """.trimIndent() + "\n"
        atomicWrite(directory.resolve("university.json"), json)
    }

    private fun writeImportReport(universityIds: List<String>) {
        val serializedFailures = failedImports.joinToString(",\n") { failure ->
            "    {\"university\": ${json(failure.universityId)}, " +
                "\"type\": ${json(failure.profileType)}, " +
                "\"profile\": ${json(failure.profileName)}, " +
                "\"reason\": ${json(failure.reason)}}"
        }
        val report = buildString {
            appendLine("{")
            appendLine("  \"generated_at\": ${json(Instant.now().toString())},")
            appendLine("  \"universities\": [${universityIds.joinToString(", ") { json(it) }}],")
            appendLine("  \"discovered_profiles\": $discoveredCount,")
            appendLine("  \"imported_profiles\": $importedCount,")
            appendLine("  \"preserved_profiles\": $preservedCount,")
            appendLine("  \"skipped_profiles\": ${failedImports.size},")
            appendLine("  \"failures\": [${if (serializedFailures.isBlank()) "" else "\n$serializedFailures\n  "}]")
            appendLine("}")
        }
        atomicWrite(dataRoot.resolve("import-report.json"), report)
    }

    private fun isBiologyGroup(candidate: com.example.bruschedule.domain.model.ProfileCandidate): Boolean =
        candidate.type == ScheduleProfileType.GROUP &&
            (candidate.sourceKey.startsWith(BsuBiologyScheduleHtmlParser.BIO_GROUP_NUMBER_SOURCE_KEY_PREFIX) ||
                candidate.sourceKey.startsWith(BsuBiologyScheduleHtmlParser.BIO_GROUP_SOURCE_KEY_PREFIX))

    private fun serializeLessons(lessons: List<Lesson>): String {
        val header = listOf(
            "date", "start_time", "end_time", "subject", "teacher", "classroom", "type", "slot_number", "source_key",
        )
        val rows = lessons.map { lesson ->
            listOf(
                lesson.lessonDate,
                lesson.startTime,
                lesson.endTime,
                lesson.subjectName,
                lesson.teacherName,
                lesson.classroom,
                lesson.type.csvValue(),
                lesson.slotNumber.toString(),
                lesson.sourceKey,
            )
        }
        return buildString {
            appendLine(header.joinToString(",", transform = ::csvCell))
            rows.forEach { row -> appendLine(row.joinToString(",", transform = ::csvCell)) }
        }
    }

    private fun ScheduleProfileType.directoryName(): String = when (this) {
        ScheduleProfileType.GROUP -> "groups"
        ScheduleProfileType.TEACHER -> "teachers"
    }

    private fun com.example.bruschedule.domain.model.LessonType.csvValue(): String = when (this) {
        com.example.bruschedule.domain.model.LessonType.LECTURE -> "lecture"
        com.example.bruschedule.domain.model.LessonType.LABORATORY -> "laboratory"
        com.example.bruschedule.domain.model.LessonType.PRACTICE -> "practice"
        com.example.bruschedule.domain.model.LessonType.UNKNOWN -> "unknown"
    }

    private fun List<String>.valueAt(index: Int?): String = index?.let { getOrNull(it) }.orEmpty()

    private fun writeCsv(path: Path, header: List<String>, rows: List<List<String>>) {
        atomicWrite(path, buildString {
            appendLine(header.joinToString(",", transform = ::csvCell))
            rows.forEach { row -> appendLine(row.joinToString(",", transform = ::csvCell)) }
        })
    }

    private fun parseCsv(content: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < content.length) {
            val char = content[index]
            when {
                char == '"' && quoted && content.getOrNull(index + 1) == '"' -> {
                    field.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    row += field.toString().trim()
                    field.clear()
                }
                (char == '\n' || char == '\r') && !quoted -> {
                    row += field.toString().trim()
                    if (row.any(String::isNotBlank)) rows += row
                    row = mutableListOf()
                    field.clear()
                    if (char == '\r' && content.getOrNull(index + 1) == '\n') index++
                }
                else -> field.append(char)
            }
            index++
        }
        require(!quoted) { "CSV file contains an unterminated quoted field" }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row += field.toString().trim()
            if (row.any(String::isNotBlank)) rows += row
        }
        return rows
    }

    private fun atomicWrite(path: Path, content: String) {
        Files.createDirectories(path.parent)
        val temp = path.resolveSibling("${path.fileName}.tmp")
        Files.write(temp, content.toByteArray(StandardCharsets.UTF_8))
        try {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace('ё', 'е')
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun slug(value: String): String {
        val transliteration = mapOf(
            'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "yo",
            'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m",
            'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
            'ф' to "f", 'х' to "h", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "shch",
            'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
        )
        return buildString {
            value.lowercase(Locale.ROOT).forEach { char ->
                val replacement = transliteration[char] ?: char.toString()
                replacement.forEach { out ->
                    if (out.isLetterOrDigit() && out.code < 128) append(out)
                    else if (isNotEmpty() && last() != '-') append('-')
                }
            }
        }.trim('-').replace(Regex("-+"), "-").take(60).trimEnd('-')
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .take(6)
        .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }

    private fun json(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }

    private companion object {
        const val MAX_SCHEDULE_BYTES = 30 * 1024 * 1024
        const val PROFILE_DELAY_MILLIS = 150L
        const val PROGRESS_INTERVAL = 25
        val SAFE_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,79}")
    }
}

/** External source access is restricted to the two universities and the document hosts their parsers use. */
private class UniversitySourceHttpTransport : HtmlTransport, BinaryResourceTransport {
    private val client = HttpClient(CIO) {
        followRedirects = false
        install(HttpCookies) { storage = AcceptAllCookiesStorage() }
        install(HttpTimeout) {
            requestTimeoutMillis = 45_000
            connectTimeoutMillis = 12_000
            socketTimeoutMillis = 45_000
        }
    }
    private val hostLocks = ConcurrentHashMap<String, Mutex>()
    private val lastRequestAtNanos = ConcurrentHashMap<String, Long>()
    private val cacheMonitor = Any()
    private val responseCache = LinkedHashMap<String, ByteArray>(16, 0.75f, true)
    private var responseCacheBytes = 0L

    override suspend fun get(url: String): String = fetch(url, binary = false).toString(StandardCharsets.UTF_8)

    override suspend fun getBytes(url: String): ByteArray = fetch(url, binary = true)

    fun close() = client.close()

    private suspend fun fetch(originalUrl: String, binary: Boolean): ByteArray {
        val originalCacheKey = cacheKey(originalUrl, binary)
        cached(originalCacheKey)?.let { return it }
        var currentUrl = originalUrl
        var redirects = 0
        while (true) {
            val host = validateAllowedUrl(currentUrl)
            var attempt = 0
            while (true) {
                pace(host)
                val response = try {
                    client.get(currentUrl) {
                        header(HttpHeaders.UserAgent, USER_AGENT)
                        header(
                            HttpHeaders.Accept,
                            if (binary) "application/octet-stream,application/pdf,*/*;q=0.8"
                            else "text/html,application/xhtml+xml,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,*/*;q=0.8",
                        )
                        header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (attempt >= MAX_RETRIES) throw failure
                    delay(retryDelayMillis(attempt))
                    attempt++
                    continue
                }

                if (response.status.value in 300..399) {
                    val location = response.headers[HttpHeaders.Location]
                        ?: error("Источник вернул redirect без адреса")
                    if (redirects >= MAX_REDIRECTS) {
                        error("Превышен лимит переходов между официальными источниками")
                    }
                    currentUrl = URI(currentUrl).resolve(location).toString()
                    redirects++
                    break
                }

                if (!response.status.isSuccess()) {
                    val status = response.status.value
                    val retryable = status == 408 || status == 429 || status in 500..599
                    if (retryable && attempt < MAX_RETRIES) {
                        val retryAfterSeconds = response.headers["Retry-After"]?.toLongOrNull()
                        val delayMillis = retryAfterSeconds?.coerceIn(0, MAX_RETRY_AFTER_SECONDS)?.times(1_000)
                            ?: retryDelayMillis(attempt)
                        delay(delayMillis)
                        attempt++
                        continue
                    }
                    error("Источник ответил HTTP $status")
                }

                val contentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                require(contentLength == null || contentLength <= MAX_SCHEDULE_BYTES) {
                    "Источник вернул файл больше 30 МБ"
                }
                val bytes = response.bodyAsBytes()
                require(bytes.size <= MAX_SCHEDULE_BYTES) { "Источник вернул файл больше 30 МБ" }
                require(bytes.isNotEmpty()) { "Источник вернул пустой файл" }
                cache(originalCacheKey, bytes)
                cache(cacheKey(currentUrl, binary), bytes)
                return bytes
            }
        }
    }

    private suspend fun pace(host: String) {
        val lock = hostLocks.computeIfAbsent(host) { Mutex() }
        lock.withLock {
            val previous = lastRequestAtNanos[host]
            if (previous != null) {
                val elapsedMillis = (System.nanoTime() - previous) / NANOS_PER_MILLISECOND
                if (elapsedMillis < MIN_HOST_REQUEST_GAP_MILLIS) {
                    delay(MIN_HOST_REQUEST_GAP_MILLIS - elapsedMillis)
                }
            }
            lastRequestAtNanos[host] = System.nanoTime()
        }
    }

    private fun validateAllowedUrl(value: String): String {
        val uri = runCatching { URI(value) }.getOrElse { error("Некорректный URL источника") }
        val host = uri.host?.lowercase(Locale.ROOT) ?: error("В источнике отсутствует host")
        val isAllowed = uri.scheme.equals("https", ignoreCase = true) &&
            (uri.port == -1 || uri.port == 443) && uri.userInfo == null && (
                host == "rasp.bru.by" || host == "bsu.bio" || host == "bsu.by" || host.endsWith(".bsu.by") ||
                    host == "docs.google.com" || host == "drive.google.com" ||
                    host == "drive.usercontent.google.com" || host.endsWith(".googleusercontent.com") ||
                    host == "dropbox.com" || host.endsWith(".dropbox.com") ||
                    host == "dropboxusercontent.com" || host.endsWith(".dropboxusercontent.com")
                )
        require(isAllowed) { "Источник не входит в разрешённые сайты университетов и их документов" }
        return host
    }

    private fun retryDelayMillis(attempt: Int): Long = (1_000L shl attempt.coerceAtMost(3)).coerceAtMost(8_000L)

    private fun cacheKey(url: String, binary: Boolean): String = "${if (binary) "binary" else "html"}|$url"

    private fun cached(key: String): ByteArray? = synchronized(cacheMonitor) {
        responseCache[key]?.copyOf()
    }

    private fun cache(key: String, bytes: ByteArray) = synchronized(cacheMonitor) {
        if (bytes.size > MAX_CACHE_BYTES) return@synchronized
        responseCache.remove(key)?.let { responseCacheBytes -= it.size }
        while (responseCacheBytes + bytes.size > MAX_CACHE_BYTES && responseCache.isNotEmpty()) {
            val eldest = responseCache.entries.iterator()
            val removed = eldest.next()
            responseCacheBytes -= removed.value.size
            eldest.remove()
        }
        val copy = bytes.copyOf()
        responseCache[key] = copy
        responseCacheBytes += copy.size
    }

    private companion object {
        const val MAX_REDIRECTS = 6
        const val MAX_RETRIES = 2
        const val MAX_RETRY_AFTER_SECONDS = 60L
        const val MAX_SCHEDULE_BYTES = 30 * 1024 * 1024
        const val MAX_CACHE_BYTES = 64 * 1024 * 1024
        const val MIN_HOST_REQUEST_GAP_MILLIS = 300L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val USER_AGENT = "ScheduleGitHubDatasetImporter/1.0 (explicit manual import)"
    }
}

private fun redactUrls(value: String): String {
    val output = StringBuilder()
    var index = 0
    while (index < value.length) {
        val schemeLength = when {
            value.startsWith("https://", index, ignoreCase = true) -> 8
            value.startsWith("http://", index, ignoreCase = true) -> 7
            else -> 0
        }
        if (schemeLength == 0) {
            output.append(value[index])
            index++
        } else {
            output.append("[source URL]")
            index += schemeLength
            while (
                index < value.length && !value[index].isWhitespace() && value[index] != '"' &&
                value[index] != '\'' && value[index] != '<' && value[index] != '>'
            ) {
                index++
            }
        }
    }
    return output.toString().replace('\n', ' ').replace('\r', ' ').trim()
}

private fun csvCell(value: String): String {
    val normalized = value.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')
    return if (normalized.any { it == ',' || it == '"' }) {
        "\"${normalized.replace("\"", "\"\"")}\""
    } else {
        normalized
    }
}
