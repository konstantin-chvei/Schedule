package com.example.bruschedule.data.scraper

import com.example.bruschedule.domain.model.Lesson
import com.example.bruschedule.domain.model.LessonType
import com.example.bruschedule.domain.model.LessonTypePalette
import com.example.bruschedule.domain.model.ScheduleProfile
import com.example.bruschedule.domain.model.ScheduleProfileType
import com.example.bruschedule.domain.repository.ScheduleHtmlParser
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus

class BsuBiologyScheduleHtmlParser : ScheduleHtmlParser {
    override val formatVersion: Int = 5

    override fun parse(profile: ScheduleProfile, html: String, referenceDate: LocalDate): List<Lesson> {
        require(isBiologyProfile(profile)) { "Профиль не относится к расписанию Биологического факультета БГУ" }
        if (profile.type == ScheduleProfileType.TEACHER) {
            return parseTeacherProfile(profile, html, referenceDate)
        }
        val groupId = profile.sourceKey
            ?.let { sourceKey ->
                when {
                    sourceKey.startsWith(BIO_GROUP_NUMBER_SOURCE_KEY_PREFIX) ->
                        sourceKey.removePrefix(BIO_GROUP_NUMBER_SOURCE_KEY_PREFIX)
                    sourceKey.startsWith(BIO_GROUP_SOURCE_KEY_PREFIX) ->
                        sourceKey.removePrefix(BIO_GROUP_SOURCE_KEY_PREFIX)
                    else -> null
                }
            }
            ?.takeIf(String::isNotBlank)
            ?: error("В профиле Биофака не указан идентификатор учебной группы")
        val pages = splitWeeklyPages(html, referenceDate)
        val lessons = pages.flatMap { (weekDate, pageHtml) ->
            if (isGroupPageProfile(profile) || isServerRenderedGroupPage(pageHtml)) {
                parseGroupPage(profile, groupId, weekDate, pageHtml)
            } else {
                parseWeek(profile, groupId, weekDate, pageHtml)
            }
        }.distinctBy(Lesson::sourceKey)
            .sortedWith(compareBy({ it.lessonDate }, { it.startTime }, { it.slotNumber }, { it.id }))
        require(lessons.isNotEmpty()) {
            "В расписании Биофака не найдены пары выбранной группы. Обновите список групп и попробуйте снова"
        }
        return lessons
    }

    override fun hasAlternatingTwoWeekCycle(profile: ScheduleProfile, html: String): Boolean = false

    override fun hasWeeklyScheduleTemplate(profile: ScheduleProfile, html: String): Boolean =
        isBiologyProfile(profile) &&
            !(profile.type == ScheduleProfileType.TEACHER && BsuTeacherProfileSourceKey.decode(profile.sourceKey) != null) &&
            !hasWeeklyEnvelope(html)

    fun hasScheduleGrid(html: String): Boolean {
        val document = Ksoup.parse(html)
        val hasLegacyGrid = document.selectFirst("table.grid-table") != null &&
            document.select("#schedule-header th.group-header").isNotEmpty() &&
            document.select("#schedule-body td.cell[data-group-id]").isNotEmpty()
        if (hasLegacyGrid) return true
        return isServerRenderedGroupPage(html)
    }

    fun discoverGroups(html: String): List<BsuBiologyGroup> {
        val document = Ksoup.parse(html)
        val headers = document.select("#schedule-header th.group-header").toList()
        if (headers.isEmpty()) {
            return document.select("a[href]").mapNotNull { link ->
                val href = link.attr("href")
                val groupNumber = queryParameter(href, "group")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val number = link.text().trim().ifBlank { groupNumber }
                BsuBiologyGroup(
                    id = groupNumber,
                    number = number,
                    programme = link.attr("title").trim(),
                    name = number,
                )
            }.distinctBy(BsuBiologyGroup::id)
        }
        val cellGroupIds = document.select("#schedule-body td.cell[data-group-id]")
            .map { it.attr("data-group-id").trim() }
            .filter(String::isNotBlank)
            .distinct()
        val optionGroupIds = document.select("#schedule-group-filter-list input[type=checkbox][value]")
            .map { it.attr("value").trim() }
            .filter(String::isNotBlank)
        val ids = when {
            cellGroupIds.size >= headers.size -> cellGroupIds
            optionGroupIds.size >= headers.size -> optionGroupIds
            else -> emptyList()
        }
        return headers.mapIndexedNotNull { index, header ->
            val groupId = ids.getOrNull(index) ?: return@mapIndexedNotNull null
            val number = header.selectFirst(".group-header-number")?.text()?.trim().orEmpty()
                .ifBlank { header.text().substringBefore(' ').trim() }
            if (number.isBlank()) return@mapIndexedNotNull null
            val programme = header.selectFirst(".group-header-name")?.text()?.trim().orEmpty()
            BsuBiologyGroup(
                id = groupId,
                number = number,
                programme = programme,
                name = number,
            )
        }.distinctBy(BsuBiologyGroup::id)
    }

    fun discoverTeacherNames(html: String): List<String> {
        val document = Ksoup.parse(html)
        val names = linkedSetOf<String>()
        document.select(".lesson-teacher a, .lesson-teacher, .teacher-link, [class*=teacher]")
            .forEach { element ->
                splitTeacherValues(element.text()).forEach { name -> names += name }
            }
        val lines = htmlLines(html)
        lines.forEachIndexed { index, line ->
            if (!line.startsWith("Преподаватель", ignoreCase = true)) return@forEachIndexed
            val inline = line.substringAfter(':', "").trim()
            val value = inline.takeIf(::isTeacherValue)
                ?: lines.drop(index + 1).firstOrNull(::isTeacherValue)
            value?.let { teacherText -> splitTeacherValues(teacherText).forEach { names += it } }
        }
        return names.toList()
    }

    fun combineTeacherPages(pages: List<BsuBiologyTeacherPage>): String = pages.joinToString("\n") { page ->
        "$TEACHER_PAGE_MARKER_PREFIX${page.groupId}:${page.weekDate}-->\n${page.html}"
    }

    fun isBiologyProfile(profile: ScheduleProfile): Boolean =
        profile.sourceKey?.let { sourceKey ->
            sourceKey == BIOLOGY_FACULTY_SOURCE_KEY ||
                sourceKey.startsWith(BIO_GROUP_SOURCE_KEY_PREFIX) ||
                sourceKey.startsWith(BIO_GROUP_NUMBER_SOURCE_KEY_PREFIX)
        } == true || profile.sourceUrl?.let { sourceUrl ->
            sourceUrl.contains(BIOLOGY_HOST_PATH, ignoreCase = true) ||
                sourceUrl.contains(BIOLOGY_GROUP_PAGE_PATH, ignoreCase = true)
        } == true

    fun isGroupPageProfile(profile: ScheduleProfile): Boolean =
        profile.sourceUrl?.contains(BIOLOGY_GROUP_PAGE_PATH, ignoreCase = true) == true

    fun combineWeeklyPages(pages: List<Pair<LocalDate, String>>): String = pages
        .joinToString("\n") { (weekDate, html) -> "$WEEK_MARKER_PREFIX$weekDate-->\n$html" }

    private fun parseTeacherProfile(
        profile: ScheduleProfile,
        html: String,
        referenceDate: LocalDate,
    ): List<Lesson> {
        val rawSourceKey = profile.sourceKey?.takeIf(String::isNotBlank)
        val targetTeacher = BsuTeacherProfileSourceKey.teacherName(rawSourceKey)
            ?: rawSourceKey?.takeIf { it !in BsuFacultyTeacherScheduleParserRegistry.registeredFacultyNames }
            ?: profile.name
        val markers = TEACHER_PAGE_MARKER.findAll(html).toList()
        val lessons = if (markers.isNotEmpty()) {
            markers.flatMapIndexed { index, marker ->
                val groupId = marker.groupValues[1]
                val weekDate = runCatching { LocalDate.parse(marker.groupValues[2]) }.getOrNull()
                    ?: return@flatMapIndexed emptyList()
                val endIndex = markers.getOrNull(index + 1)?.range?.first ?: html.length
                val pageHtml = html.substring(marker.range.last + 1, endIndex)
                if (groupId.isBlank()) return@flatMapIndexed emptyList()
                if (isGroupPageProfile(profile) || isServerRenderedGroupPage(pageHtml)) {
                    parseGroupPage(profile, groupId, weekDate, pageHtml)
                } else {
                    parseWeek(profile, groupId, weekDate, pageHtml)
                }
            }
        } else {
            val groupId = profile.sourceUrl?.let { queryParameter(it, "group") }
                ?.takeIf(String::isNotBlank)
                ?: error("В источнике расписания Биофака не указан код группы преподавателя")
            splitWeeklyPages(html, referenceDate).flatMap { (weekDate, pageHtml) ->
                if (isGroupPageProfile(profile) || isServerRenderedGroupPage(pageHtml)) {
                    parseGroupPage(profile, groupId, weekDate, pageHtml)
                } else {
                    parseWeek(profile, groupId, weekDate, pageHtml)
                }
            }
        }
        val parsed = lessons.filter { BsuTeacherNameMatcher.matches(it.teacherName, targetTeacher) }
            .distinctBy(Lesson::sourceKey)
            .sortedWith(compareBy({ it.lessonDate }, { it.startTime }, { it.slotNumber }, { it.id }))
        require(parsed.isNotEmpty()) {
            "В расписании Биофака не найдены пары преподавателя «$targetTeacher». Проверьте источник факультета"
        }
        return parsed
    }

    private fun splitTeacherValues(value: String): List<String> {
        val clean = value.substringAfter(':', value).trim()
        return clean.split(Regex("[;,/|]+"))
            .map(::normalizeLine)
            .filter(::isTeacherValue)
    }

    private fun isTeacherValue(value: String): Boolean {
        val clean = normalizeLine(value).trim('.', ':', ' ', '–', '-')
        if (clean.length !in 4..90 || clean.any(Char::isDigit)) return false
        val normalized = clean.lowercase().replace('ё', 'е')
        if (normalized.startsWith("ауд") || normalized == "преподаватель" || normalized == "—") return false
        return clean.any(Char::isLetter) && clean.any(Char::isWhitespace)
    }

    private fun parseGroupPage(
        profile: ScheduleProfile,
        groupId: String,
        requestedWeek: LocalDate,
        html: String,
    ): List<Lesson> {
        val document = Ksoup.parse(html)
        val lines = htmlLines(html)
        val weekStart = weekStartFromLines(lines)
            ?: requestedWeek.minus(DatePeriod(days = requestedWeek.dayOfWeek.isoDayNumber - 1))

        if (document.selectFirst("section.day .lesson-entry .discipline") != null) {
            return parseStructuredGroupPage(profile, groupId, document, weekStart)
        }
        var index = lines.indexOfFirst { WEEK_RANGE_MONTH_LEFT.containsMatchIn(it) || WEEK_RANGE_ONE_MONTH.containsMatchIn(it) }
            .takeIf { it >= 0 }?.plus(1) ?: 0
        var dayIndex: Int? = null
        val result = mutableListOf<Lesson>()

        while (index < lines.size) {
            val line = lines[index]
            val parsedDay = weekdayNumber(line)
            if (parsedDay != null) {
                dayIndex = parsedDay - 1
                index++
                continue
            }
            val slotMatch = SLOT_LINE.matchEntire(line)
            if (slotMatch == null) {
                index++
                continue
            }
            val currentDay = dayIndex
            val slot = slotMatch.groupValues[1].toIntOrNull()
            if (currentDay == null || slot == null) {
                index++
                continue
            }
            val boundary = nextScheduleBoundary(lines, index + 1)
            val timeIndex = (index + 1 until boundary).firstOrNull { TIME_RANGE.containsMatchIn(lines[it]) }
            if (timeIndex == null) {
                index = boundary
                continue
            }
            val timeMatch = TIME_RANGE.find(lines[timeIndex])
            if (timeMatch == null) {
                index = boundary
                continue
            }
            val start = normalizeTime(timeMatch.groupValues[1])
            val end = normalizeTime(timeMatch.groupValues[2])
            val date = runCatching { weekStart.plus(DatePeriod(days = currentDay)) }.getOrNull()
            if (date != null) {
                result += parseGroupSlot(
                    profile = profile,
                    groupId = groupId,
                    date = date,
                    slot = slot,
                    start = start,
                    end = end,
                    lines = lines.subList(timeIndex + 1, boundary),
                )
            }
            index = boundary
        }
        return result
    }

    private fun parseStructuredGroupPage(
        profile: ScheduleProfile,
        groupId: String,
        document: com.fleeksoft.ksoup.nodes.Document,
        weekStart: LocalDate,
    ): List<Lesson> {
        val result = mutableListOf<Lesson>()
        val slotsByDate = mutableMapOf<String, Int>()
        document.select("section.day").forEach dayLoop@ { daySection ->
            val weekday = weekdayNumber(daySection.selectFirst(".day-title")?.text().orEmpty())
                ?: return@dayLoop
            val date = runCatching { weekStart.plus(DatePeriod(days = weekday - 1)) }.getOrNull()
                ?: return@dayLoop
            daySection.select(".lesson").forEach lessonLoop@ { lessonElement ->
                val time = TIME_RANGE.find(lessonElement.selectFirst(".lesson-hours")?.text().orEmpty())
                    ?: return@lessonLoop
                val slot = SLOT_LINE.find(lessonElement.selectFirst(".lesson-number")?.text().orEmpty())
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: ((slotsByDate[date.toString()] ?: 0) + 1)
                slotsByDate[date.toString()] = maxOf(slotsByDate[date.toString()] ?: 0, slot)
                lessonElement.select(".lesson-entry").toList().forEachIndexed entryLoop@ { entryIndex, entry ->
                    val rawSubject = entry.selectFirst(".discipline")?.text()?.trim().orEmpty()
                    if (rawSubject.isBlank()) return@entryLoop
                    val subgroup = entry.selectFirst(".subgroup-label")?.text()
                        ?.let { SUBGROUP_LINE.find(it) }
                        ?.groupValues?.getOrNull(1)?.trim().orEmpty()
                    val subject = if (subgroup.isBlank()) rawSubject else "$rawSubject · подгруппа $subgroup"
                    val infoLines = entry.select(".lesson-info > div")
                        .map { it.text().trim() }
                        .filter(String::isNotBlank)
                    val teacher = valueAfterLabel(infoLines, 0, infoLines.size, "преподаватель")
                        .orEmpty()
                        .takeUnless { it == "—" }
                        .orEmpty()
                    val room = roomFromLines(infoLines, 0, infoLines.size)
                    val notes = infoLines.filter(::isWarning)
                        .map { it.removePrefix("⚠️").trim() }
                        .filter(String::isNotBlank)
                        .joinToString(" · ")
                    val start = normalizeTime(time.groupValues[1])
                    val end = normalizeTime(time.groupValues[2])
                    val type = lessonType(entry.selectFirst(".lesson-type")?.text().orEmpty())
                    val sourceKey = "${profile.id}|$date|$slot|$start-$end|bio:$groupId:$entryIndex"
                    result += Lesson(
                        id = "web:$sourceKey",
                        profileId = profile.id,
                        subjectName = subject,
                        teacherName = teacher,
                        classroom = room,
                        startTime = start,
                        endTime = end,
                        type = type,
                        colorHex = LessonTypePalette.accent(type),
                        isCustom = false,
                        version = 1,
                        lessonDate = date.toString(),
                        dayOfWeek = date.dayOfWeek.isoDayNumber,
                        slotNumber = slot,
                        sourceKey = sourceKey,
                        notes = notes,
                    )
                }
            }
        }
        return result.distinctBy(Lesson::sourceKey)
            .sortedWith(compareBy({ it.lessonDate }, { it.startTime }, { it.slotNumber }, { it.id }))
    }

    private fun parseGroupSlot(
        profile: ScheduleProfile,
        groupId: String,
        date: LocalDate,
        slot: Int,
        start: String,
        end: String,
        lines: List<String>,
    ): List<Lesson> {
        val separators = lines.indices.filter { lines[it] == "/" }
        return separators.mapIndexedNotNull { separatorIndex, separator ->
            val previousSeparator = separators.getOrNull(separatorIndex - 1) ?: -1
            val nextSeparator = separators.getOrNull(separatorIndex + 1) ?: lines.size
            val subjectIndex = (previousSeparator + 1 until separator).lastOrNull { candidate ->
                !isSubjectMetadata(lines[candidate])
            } ?: return@mapIndexedNotNull null
            val typeIndex = (separator + 1 until nextSeparator).firstOrNull { candidate ->
                lines[candidate].isNotBlank() && !isWarning(lines[candidate])
            } ?: return@mapIndexedNotNull null
            val rawSubject = lines[subjectIndex].trim()
            if (rawSubject.isBlank()) return@mapIndexedNotNull null
            val subgroup = (previousSeparator + 1 until subjectIndex)
                .mapNotNull { SUBGROUP_LINE.matchEntire(lines[it])?.groupValues?.getOrNull(1)?.trim() }
                .lastOrNull()
            val subject = if (subgroup.isNullOrBlank()) rawSubject else "$rawSubject · подгруппа $subgroup"
            val teacher = valueAfterLabel(lines, typeIndex + 1, nextSeparator, "преподаватель")
                .orEmpty()
                .takeUnless { it == "—" }
                .orEmpty()
            val room = roomFromLines(lines, typeIndex + 1, nextSeparator)
            val notes = lines.subList(typeIndex + 1, nextSeparator)
                .filter(::isWarning)
                .map { it.removePrefix("⚠️").trim() }
                .filter(String::isNotBlank)
                .joinToString(" · ")
            val type = lessonType(lines[typeIndex])
            val ordinal = separatorIndex
            val sourceKey = "${profile.id}|$date|$slot|$start-$end|bio:$groupId:$ordinal"
            Lesson(
                id = "web:$sourceKey",
                profileId = profile.id,
                subjectName = subject,
                teacherName = teacher,
                classroom = room,
                startTime = start,
                endTime = end,
                type = type,
                colorHex = LessonTypePalette.accent(type),
                isCustom = false,
                version = 1,
                lessonDate = date.toString(),
                dayOfWeek = date.dayOfWeek.isoDayNumber,
                slotNumber = slot,
                sourceKey = sourceKey,
                notes = notes,
            )
        }
    }

    private fun htmlLines(html: String): List<String> {
        val bodyHtml = Ksoup.parse(html).selectFirst("body")?.html().orEmpty().ifBlank { html }
        return bodyHtml
            .replace(BR_TAG, "\n")
            .replace(BLOCK_START_TAG, "\n")
            .replace(BLOCK_END_TAG, "\n")
            .replace(HTML_TAG, " ")
            .replace("&nbsp;", " ", ignoreCase = true)
            .replace("&#160;", " ", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
            .split('\n')
            .map(::normalizeLine)
            .filter(String::isNotBlank)
    }

    private fun weekStartFromLines(lines: List<String>): LocalDate? {
        val range = lines.firstNotNullOfOrNull { line ->
            WEEK_RANGE_MONTH_LEFT.find(line)?.let { match ->
                val day = match.groupValues[1].toIntOrNull() ?: return@let null
                val month = russianMonthNumber(match.groupValues[2]) ?: return@let null
                val year = match.groupValues[4].toIntOrNull() ?: return@let null
                runCatching { LocalDate(year, month, day) }.getOrNull()
            } ?: WEEK_RANGE_ONE_MONTH.find(line)?.let { match ->
                val day = match.groupValues[1].toIntOrNull() ?: return@let null
                val month = russianMonthNumber(match.groupValues[2]) ?: return@let null
                val year = match.groupValues[3].toIntOrNull() ?: return@let null
                runCatching { LocalDate(year, month, day) }.getOrNull()
            }
        }
        return range
    }

    private fun nextScheduleBoundary(lines: List<String>, start: Int): Int {
        for (index in start until lines.size) {
            if (weekdayNumber(lines[index]) != null || SLOT_LINE.matches(lines[index])) return index
        }
        return lines.size
    }

    private fun weekdayNumber(line: String): Int? = when (normalizeLine(line).lowercase().replace('ё', 'е')) {
        "понедельник" -> 1
        "вторник" -> 2
        "среда" -> 3
        "четверг" -> 4
        "пятница" -> 5
        "суббота" -> 6
        "воскресенье" -> 7
        else -> null
    }

    private fun isSubjectMetadata(line: String): Boolean {
        val normalized = normalizeLine(line).lowercase().replace('ё', 'е')
        return normalized == "/" || normalized.startsWith("подгруппа ") || isWarning(line) ||
            normalized.startsWith("преподаватель") || normalized.startsWith("ауд") ||
            normalized.startsWith("(") || TIME_RANGE.containsMatchIn(normalized) ||
            SLOT_LINE.matches(normalized) || weekdayNumber(normalized) != null
    }

    private fun isWarning(line: String): Boolean {
        val normalized = normalizeLine(line).lowercase().replace('ё', 'е')
        return normalized.startsWith("⚠") || normalized.startsWith("неделя с ") ||
            normalized.startsWith("с ") && SIMPLE_DATE.containsMatchIn(normalized) ||
            SIMPLE_DATE.containsMatchIn(normalized) && ("занятий" in normalized || "не будет" in normalized)
    }

    private fun valueAfterLabel(lines: List<String>, start: Int, end: Int, label: String): String? {
        val index = (start until end).firstOrNull { lines[it].lowercase().startsWith(label) } ?: return null
        val inline = lines[index].substringAfter(':', "").trim()
        if (inline.isNotBlank()) return inline
        return (index + 1 until end).firstOrNull { candidate ->
            lines[candidate].isNotBlank() && !isWarning(lines[candidate]) &&
                !lines[candidate].startsWith("Ауд.", ignoreCase = true) &&
                !lines[candidate].startsWith("(") && lines[candidate] != "/"
        }?.let { lines[it].trim() }
    }

    private fun roomFromLines(lines: List<String>, start: Int, end: Int): String {
        val index = (start until end).firstOrNull { lines[it].startsWith("Ауд.", ignoreCase = true) }
            ?: return ""
        val inline = lines[index].substringAfter("Ауд.", "").trim().trimStart(':')
        val inlineLocation = PARENTHESIZED_LOCATION.find(inline)?.groupValues?.getOrNull(1)?.trim()
        val inlineNumber = PARENTHESIZED_LOCATION.replace(inline, "").trim()
        val number = inlineNumber.takeIf(::isRoomNumber)
            ?: (index + 1 until end).firstOrNull { candidate ->
                lines[candidate].isNotBlank() && !isWarning(lines[candidate]) &&
                    !lines[candidate].startsWith("(") && lines[candidate] != "/" &&
                    !lines[candidate].startsWith("Преподаватель", ignoreCase = true)
            }?.let { lines[it].trim().takeIf(::isRoomNumber) }.orEmpty()
        val location = inlineLocation ?: (index + 1 until end).firstOrNull { lines[it].startsWith("(") }
            ?.let { PARENTHESIZED_LOCATION.find(lines[it])?.groupValues?.getOrNull(1)?.trim() }
        return listOfNotNull(
            number.takeIf(String::isNotBlank),
            location?.takeIf(String::isNotBlank),
        ).joinToString(" · ")
    }

    private fun isRoomNumber(value: String): Boolean = value.isNotBlank() && value != "—" && value != "-"

    private fun lessonType(text: String): LessonType {
        val normalized = text.lowercase().replace('ё', 'е')
        return when {
            "лекц" in normalized -> LessonType.LECTURE
            "лаборатор" in normalized || "лаб" in normalized -> LessonType.LABORATORY
            "практик" in normalized || "семинар" in normalized || "уср" in normalized -> LessonType.PRACTICE
            else -> LessonType.UNKNOWN
        }
    }

    private fun russianMonthNumber(text: String): Int? = when (text.lowercase().replace('ё', 'е')) {
        "января", "январь" -> 1
        "февраля", "февраль" -> 2
        "марта", "март" -> 3
        "апреля", "апрель" -> 4
        "мая", "май" -> 5
        "июня", "июнь" -> 6
        "июля", "июль" -> 7
        "августа", "август" -> 8
        "сентября", "сентябрь" -> 9
        "октября", "октябрь" -> 10
        "ноября", "ноябрь" -> 11
        "декабря", "декабрь" -> 12
        else -> null
    }

    private fun queryParameter(url: String, key: String): String? =
        url.substringAfter('?', "").substringBefore('#').split('&')
            .firstOrNull { it.substringBefore('=') == key }
            ?.substringAfter('=', "")
            ?.trim()

    private fun isGroupScheduleTitle(text: String): Boolean = GROUP_SCHEDULE_TITLE.containsMatchIn(text)

    private fun isServerRenderedGroupPage(html: String): Boolean {
        val text = Ksoup.parse(html).text()
        return isGroupScheduleTitle(text) && RUSSIAN_WEEKDAY.containsMatchIn(text) && TIME_RANGE.containsMatchIn(text)
    }

    private fun parseWeek(
        profile: ScheduleProfile,
        groupId: String,
        requestedWeek: LocalDate,
        html: String,
    ): List<Lesson> {
        val document = Ksoup.parse(html)
        val table = document.selectFirst("table.grid-table")
            ?: error("На странице Биофака не найдена таблица расписания")
        val weekMonday = weekStartFromDocument(document)
            ?: requestedWeek.minus(DatePeriod(days = requestedWeek.dayOfWeek.isoDayNumber - 1))
        val timesByDayAndSlot = buildMap {
            document.select("#schedule-body td.time-header[data-day][data-slot-id]").forEach { timeCell ->
                val day = timeCell.attr("data-day").toIntOrNull() ?: return@forEach
                val slot = timeCell.attr("data-slot-id").toIntOrNull() ?: return@forEach
                val time = TIME_RANGE.find(timeCell.text()) ?: return@forEach
                put(day to slot, normalizeTime(time.groupValues[1]) to normalizeTime(time.groupValues[2]))
            }
        }
        val groupCells = table.select("tbody#schedule-body td.cell[data-group-id]")
            .filter { it.attr("data-group-id").trim() == groupId }
        return groupCells.flatMapIndexed { ordinal, cell ->
            if ("empty-cell" in cell.attr("class").split(WHITESPACE)) return@flatMapIndexed emptyList()
            val dayIndex = cell.attr("data-day").toIntOrNull() ?: return@flatMapIndexed emptyList()
            val slot = cell.attr("data-slot").toIntOrNull() ?: return@flatMapIndexed emptyList()
            if (dayIndex !in 0..6 || slot <= 0) return@flatMapIndexed emptyList()
            val times = timesByDayAndSlot[dayIndex to slot]
                ?: timesByDayAndSlot[0 to slot]
                ?: return@flatMapIndexed emptyList()
            val date = runCatching { weekMonday.plus(DatePeriod(days = dayIndex)) }.getOrNull()
                ?: return@flatMapIndexed emptyList()
            val card = cell.selectFirst(".lesson-card") ?: return@flatMapIndexed emptyList()
            val title = card.selectFirst(".lesson-title")?.text()?.trim().orEmpty()
            if (title.isBlank()) return@flatMapIndexed emptyList()

            val subgroups = card.select(".subgroup-mini-slot").toList()
            val specializations = card.select(".spec-mini-slot").toList()
            val teachers: List<String>
            val rooms: List<String>
            val subject: String
            if (subgroups.isNotEmpty()) {
                teachers = subgroups.mapNotNull { subgroup ->
                    val teacher = subgroup.selectFirst(".lesson-teacher")?.text()?.trim().orEmpty()
                    teacher.takeIf(String::isNotBlank)?.let { "${subgroup.selectFirst(".subgroup-mini-slot-title")?.text()?.trim().orEmpty()}: $it" }
                }.distinct()
                rooms = subgroups.mapNotNull { subgroup ->
                    val room = subgroup.selectFirst(".lesson-room")?.text()?.trim().orEmpty()
                    room.takeIf(String::isNotBlank)?.let { "${subgroup.selectFirst(".subgroup-mini-slot-title")?.text()?.trim().orEmpty()}: $it" }
                }.distinct()
                subject = title
            } else if (specializations.isNotEmpty()) {
                val specLabels = specializations.map { specialization ->
                    specialization.text().trim().replace(WHITESPACE, " ")
                }.filter(String::isNotBlank).distinct()
                teachers = card.select(".lesson-teacher, .teacher-link")
                    .map { it.text().trim() }
                    .filter(String::isNotBlank)
                    .distinct()
                val specializationRooms = specLabels.flatMap { label ->
                    label.split('·').map(String::trim)
                        .filter { "ауд" in it.lowercase() || "каб" in it.lowercase() }
                }
                val directRooms = card.select(".lesson-room, .lesson-address")
                    .map { it.text().trim() }
                    .filter(String::isNotBlank)
                rooms = (specializationRooms + directRooms).distinct()
                subject = if (specLabels.isEmpty()) title else "$title · ${specLabels.joinToString("; ")}"
            } else {
                teachers = card.select(".lesson-teacher").map { it.text().trim() }
                    .filter(String::isNotBlank).distinct()
                rooms = card.select(".lesson-room, .lesson-address").map { it.text().trim() }
                    .filter(String::isNotBlank).distinct()
                subject = title
            }
            val type = lessonType(card, title)
            val comment = card.selectFirst(".lesson-comment")?.text()?.trim().orEmpty()
            val subjectWithComment = if (comment.isBlank()) subject else "$subject · $comment"
            val externalLessonIds = card.select("[data-lesson-id]")
                .map { it.attr("data-lesson-id").trim() }
                .filter(String::isNotBlank)
                .distinct()
                .joinToString("-")
            val identity = externalLessonIds.ifBlank { ordinal.toString() }
            val sourceKey = "${profile.id}|$date|$slot|${times.first}-${times.second}|bio:$groupId:$identity"
            listOf(
                Lesson(
                    id = "web:$sourceKey",
                    profileId = profile.id,
                    subjectName = subjectWithComment,
                    teacherName = teachers.joinToString(" · "),
                    classroom = rooms.joinToString(" · "),
                    startTime = times.first,
                    endTime = times.second,
                    type = type,
                    colorHex = LessonTypePalette.accent(type),
                    isCustom = false,
                    version = 1,
                    lessonDate = date.toString(),
                    dayOfWeek = date.dayOfWeek.isoDayNumber,
                    slotNumber = slot,
                    sourceKey = sourceKey,
                ),
            )
        }
    }

    private fun weekStartFromDocument(document: com.fleeksoft.ksoup.nodes.Document): LocalDate? {
        val text = document.selectFirst("#week-info")?.text().orEmpty()
        val match = EXPLICIT_DATE.find(text) ?: return null
        return parseDate(match.groupValues[1], match.groupValues[2], match.groupValues.getOrNull(3))
    }

    private fun splitWeeklyPages(html: String, referenceDate: LocalDate): List<Pair<LocalDate, String>> {
        val matches = WEEK_MARKER.findAll(html).toList()
        if (matches.isEmpty()) return listOf(referenceDate to html)
        return matches.mapIndexedNotNull { index, match ->
            val start = match.range.last + 1
            val end = matches.getOrNull(index + 1)?.range?.first ?: html.length
            val page = html.substring(start, end).trim()
            val weekDate = runCatching { LocalDate.parse(match.groupValues[1]) }.getOrNull() ?: return@mapIndexedNotNull null
            page.takeIf(String::isNotBlank)?.let { weekDate to it }
        }
    }

    private fun lessonType(card: Element, title: String): LessonType {
        val content = (card.attr("class") + " " + title + " " + card.text())
            .lowercase().replace('ё', 'е')
        return when {
            "lecture" in content || "лекц" in content || Regex("\\bлк\\b").containsMatchIn(content) -> LessonType.LECTURE
            "laboratory" in content || "lab" in content || "лаб" in content || Regex("\\bлр\\b").containsMatchIn(content) -> LessonType.LABORATORY
            "practice" in content || "pract" in content || "практ" in content || Regex("\\bпз\\b").containsMatchIn(content) -> LessonType.PRACTICE
            else -> LessonType.UNKNOWN
        }
    }

    private fun normalizeLine(value: String): String = value
        .replace('\u00a0', ' ')
        .replace(WHITESPACE, " ")
        .trim()

    private fun normalizeTime(value: String): String {
        val parts = value.replace('.', ':').split(':', limit = 2)
        return "${parts[0].padStart(2, '0')}:${parts.getOrElse(1) { "00" }}"
    }

    private fun parseDate(day: String, month: String, year: String?): LocalDate? {
        val reference = year?.toIntOrNull() ?: return null
        return runCatching { LocalDate(reference, month.toInt(), day.toInt()) }.getOrNull()
    }

    private fun hasWeeklyEnvelope(html: String): Boolean = WEEK_MARKER.containsMatchIn(html)

    companion object {
        const val BIOLOGY_FACULTY_SOURCE_KEY = "Биологический факультет"
        const val BIOLOGY_SCHEDULE_URL = "https://bsu.bio/schedule.php"
        const val BIOLOGY_HOST_PATH = "bio.bsu.by/schedule"
        const val BIOLOGY_GROUP_PAGE_PATH = "bsu.bio/schedule.php"
        const val BIO_GROUP_SOURCE_KEY_PREFIX = "bio-group-id:"
        const val BIO_GROUP_NUMBER_SOURCE_KEY_PREFIX = "bio-group-number:"
        private const val WEEK_MARKER_PREFIX = "<!--BSU-BIO-WEEK:"
        private const val TEACHER_PAGE_MARKER_PREFIX = "<!--BSU-BIO-TEACHER-PAGE:"
        private val WEEK_MARKER = Regex("<!--BSU-BIO-WEEK:(\\d{4}-\\d{2}-\\d{2})-->")
        private val TEACHER_PAGE_MARKER = Regex("<!--BSU-BIO-TEACHER-PAGE:([^:>]+):(\\d{4}-\\d{2}-\\d{2})-->")
        private val TIME_RANGE = Regex("(\\d{1,2}[:.]\\d{2})\\s*[-–—−]\\s*(\\d{1,2}[:.]\\d{2})")
        private val EXPLICIT_DATE = Regex("(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})")
        private val SIMPLE_DATE = Regex("\\b\\d{1,2}\\.\\d{1,2}\\b")
        private val PARENTHESIZED_LOCATION = Regex("\\(([^()]*)\\)")
        private val SLOT_LINE = Regex("(\\d+)\\s*пара", RegexOption.IGNORE_CASE)
        private val SUBGROUP_LINE = Regex("подгруппа\\s*(\\d+|[а-яёa-z])", RegexOption.IGNORE_CASE)
        private val GROUP_SCHEDULE_TITLE = Regex("группа\\s*(?:№|#)\\s*\\d+", RegexOption.IGNORE_CASE)
        private val RUSSIAN_WEEKDAY = Regex("понедельник|вторник|среда|четверг|пятница|суббота|воскресенье", RegexOption.IGNORE_CASE)
        private val WEEK_RANGE_MONTH_LEFT = Regex("(\\d{1,2})\\s+([а-яё]+)\\s*[–—-]\\s*\\d{1,2}\\s+([а-яё]+)\\s+(\\d{4})", RegexOption.IGNORE_CASE)
        private val WEEK_RANGE_ONE_MONTH = Regex("(\\d{1,2})\\s*[–—-]\\s*\\d{1,2}\\s+([а-яё]+)\\s+(\\d{4})", RegexOption.IGNORE_CASE)
        private val BR_TAG = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
        private val BLOCK_START_TAG = Regex("<(?:div|p|h[1-6]|li|tr|td|th|section|article|header|footer|ul|ol|table)\\b[^>]*>", RegexOption.IGNORE_CASE)
        private val BLOCK_END_TAG = Regex("</(?:div|p|h[1-6]|li|tr|td|th|section|article|header|footer|ul|ol|table)\\s*>", RegexOption.IGNORE_CASE)
        private val HTML_TAG = Regex("<[^>]+>")
        private val WHITESPACE = Regex("\\s+")
    }
}

data class BsuBiologyTeacherPage(
    val groupId: String,
    val weekDate: LocalDate,
    val html: String,
)

data class BsuBiologyGroup(
    val id: String,
    val number: String,
    val programme: String,
    val name: String,
)
