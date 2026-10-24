package com.example.bruschedule.data.scraper

import com.example.bruschedule.domain.model.Lesson
import com.example.bruschedule.domain.model.LessonType
import com.example.bruschedule.domain.model.LessonTypePalette
import com.example.bruschedule.domain.model.ScheduleProfile
import com.example.bruschedule.domain.model.UniversityIds
import com.example.bruschedule.domain.repository.ScheduleHtmlParser
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus

class GenericHtmlScheduleParser : ScheduleHtmlParser {
    override val formatVersion: Int = 2

    override fun parse(
        profile: ScheduleProfile,
        html: String,
        referenceDate: LocalDate,
    ): List<Lesson> {
        require(html.isNotBlank()) { "Страница расписания пуста" }
        val document = Ksoup.parse(html)
        val pageText = "${document.selectFirst("title")?.text().orEmpty()} ${document.text()}"
            .lowercase().replace('ё', 'е')
        val hasTwoWeekLabels = WEEK_ONE.containsMatchIn(pageText) && WEEK_TWO.containsMatchIn(pageText)
        val datedCells = document.select("table th, table td")
            .mapNotNull { EXPLICIT_DATE.find(it.text())?.value }
            .distinct()
        require(!hasTwoWeekLabels || datedCells.size >= 7) {
            "Для двухнедельной таблицы нужны даты в заголовках; другой формат требует отдельной настройки"
        }
        val result = mutableListOf<Lesson>()
        val monday = referenceDate.minus(DatePeriod(days = referenceDate.dayOfWeek.isoDayNumber - 1))

        document.select("table").forEachIndexed { tableIndex, table ->
            val rows = table.select("tr").toList()
            if (rows.size < 2) return@forEachIndexed
            val headerIndex = rows.indexOfFirst { row ->
                val headers = row.select("th, td").map { normalize(it.text()) }
                headers.count { weekdayNumber(it) != null || explicitDate(it, referenceDate) != null } >= 2
            }
            if (headerIndex >= 0) {
                parseDayColumns(
                    profile = profile,
                    rows = rows,
                    headerIndex = headerIndex,
                    tableIndex = tableIndex,
                    monday = monday,
                    referenceDate = referenceDate,
                    target = result,
                )
            }
            parseDayRows(
                profile = profile,
                rows = rows,
                tableIndex = tableIndex,
                monday = monday,
                referenceDate = referenceDate,
                target = result,
            )
        }

        val parsed = result.distinctBy(Lesson::sourceKey)
            .sortedWith(compareBy<Lesson>({ it.lessonDate }, { it.startTime }, { it.slotNumber }, { it.id }))
        require(parsed.isNotEmpty()) {
            "Не удалось распознать пары в опубликованном файле расписания"
        }
        return parsed
    }

    fun discoverTeacherNames(html: String): List<String> {
        if (html.isBlank()) return emptyList()
        val document = Ksoup.parse(html)
        val names = linkedSetOf<String>()
        document.select("[class*=teacher], a[href*=teacher], a[href*=employee]").forEach { element ->
            collectTeacherValues(element.text(), names, allowPlainName = true)
        }
        document.select("div, p, li, span").forEach { element ->
            teacherFromLine(element.text())?.let { collectTeacherValues(it, names, allowPlainName = true) }
        }

        document.select("table").forEach { table ->
            val rows = table.select("tr").toList()
            val headerIndex = rows.indexOfFirst { row ->
                row.select("th, td").any { isTeacherHeader(it.text()) }
            }
            if (headerIndex >= 0) {
                val headers = rows[headerIndex].select("th, td").toList()
                val teacherColumns = headers.mapIndexedNotNull { index, cell ->
                    index.takeIf { isTeacherHeader(cell.text()) }
                }
                rows.drop(headerIndex + 1).forEach { row ->
                    val cells = row.select("th, td").toList()
                    teacherColumns.forEach { column ->
                        cells.getOrNull(column)?.text()?.let { collectTeacherValues(it, names, allowPlainName = true) }
                    }
                }
            }

            rows.forEach { row ->
                row.select("th, td").forEach { cell ->
                    contentLines(cell).forEach { line ->
                        teacherFromLine(line)?.let { collectTeacherValues(it, names, allowPlainName = true) }
                        if (splitInlineTeacher(line) != null) {
                            collectTeacherValues(line, names, allowPlainName = true)
                        }
                    }
                }
            }
        }
        return names.toList()
    }

    override fun hasAlternatingTwoWeekCycle(html: String): Boolean {
        val document = Ksoup.parse(html)
        val pageText = "${document.selectFirst("title")?.text().orEmpty()} ${document.text()}"
            .lowercase().replace('ё', 'е')
        val datedHeaders = document.select("table th, table td")
            .mapNotNull { EXPLICIT_DATE.find(it.text())?.value }
            .distinct()
        return WEEK_ONE.containsMatchIn(pageText) && WEEK_TWO.containsMatchIn(pageText) && datedHeaders.size >= 7
    }

    override fun hasWeeklyScheduleTemplate(profile: ScheduleProfile, html: String): Boolean {
        if (profile.universityId == UniversityIds.BRU) return false
        val document = Ksoup.parse(html)
        val pageText = "${document.selectFirst("title")?.text().orEmpty()} ${document.text()}"
            .lowercase().replace('ё', 'е')
        if (WEEK_ONE.containsMatchIn(pageText) || WEEK_TWO.containsMatchIn(pageText) ||
            "нечетн" in pageText || "четн" in pageText
        ) return false
        return document.select("table").any { table ->
            val cells = table.select("th, td")
            val weekdays = cells.mapNotNull { weekdayNumber(it.text()) }.distinct()
            val dates = cells.mapNotNull { EXPLICIT_DATE.find(it.text())?.value }.distinct()
            weekdays.size in 2..7 && dates.size <= 7 && cells.any { TIME_RANGE.containsMatchIn(it.text()) }
        }
    }

    private fun parseDayColumns(
        profile: ScheduleProfile,
        rows: List<Element>,
        headerIndex: Int,
        tableIndex: Int,
        monday: LocalDate,
        referenceDate: LocalDate,
        target: MutableList<Lesson>,
    ) {
        val headerCells = rows[headerIndex].select("th, td").toList()
        val dayDates = headerCells.mapIndexedNotNull { index, cell ->
            dayDate(cell.text(), monday, referenceDate)?.let { index to it }
        }.toMap()
        if (dayDates.size < 2) return

        rows.drop(headerIndex + 1).forEachIndexed { rowOffset, row ->
            val cells = row.select("th, td").toList()
            val timeIndex = cells.indexOfFirst { TIME_RANGE.containsMatchIn(it.text()) }
            if (timeIndex < 0) return@forEachIndexed
            val timeText = cells[timeIndex].text()
            val time = TIME_RANGE.find(timeText) ?: return@forEachIndexed
            val start = normalizeTime(time.groupValues[1])
            val end = normalizeTime(time.groupValues[2])
            val slot = PAIR_NUMBER.find(timeText)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: rowOffset + 1

            dayDates.forEach { (cellIndex, date) ->
                if (cellIndex == timeIndex) return@forEach
                val cell = cells.getOrNull(cellIndex) ?: return@forEach
                appendLesson(
                    profile = profile,
                    date = date,
                    timeText = timeText,
                    content = cell,
                    start = start,
                    end = end,
                    slot = slot,
                    ordinal = tableIndex * 1000 + cellIndex,
                    target = target,
                )
            }
        }
    }

    private fun parseDayRows(
        profile: ScheduleProfile,
        rows: List<Element>,
        tableIndex: Int,
        monday: LocalDate,
        referenceDate: LocalDate,
        target: MutableList<Lesson>,
    ) {
        val headerIndex = rows.indexOfFirst { row ->
            val labels = row.select("th, td").map { normalize(it.text()) }
            labels.any(::isTimeHeader) && labels.any { isWeekdayHeader(it) || isDateHeader(it) }
        }
        if (headerIndex < 0) return

        val headers = rows[headerIndex].select("th, td").map { normalize(it.text()) }
        val timeColumn = headers.indexOfFirst(::isTimeHeader)
        val dayColumn = headers.indexOfFirst(::isWeekdayHeader)
            .takeIf { it >= 0 }
            ?: headers.indexOfFirst(::isDateHeader)
        val dateColumn = headers.indices.firstOrNull { it != dayColumn && isDateHeader(headers[it]) } ?: -1
        val subjectColumn = headers.indexOfFirst(::isSubjectHeader)
        val teacherColumn = headers.indexOfFirst(::isTeacherHeader)
        val roomColumn = headers.indexOfFirst(::isRoomHeader)
        val typeColumn = headers.indexOfFirst(::isTypeHeader)
        val groupColumn = headers.indexOfFirst(::isGroupHeader)
        val metadataColumns = setOf(timeColumn, dayColumn, dateColumn, teacherColumn, roomColumn, typeColumn, groupColumn)
        val subjectColumns = if (subjectColumn >= 0) {
            listOf(subjectColumn)
        } else {
            headers.indices.filter { it !in metadataColumns }
        }
        if (timeColumn < 0 || dayColumn < 0 || subjectColumns.isEmpty()) return

        var currentDate: LocalDate? = null
        rows.drop(headerIndex + 1).forEachIndexed { rowOffset, row ->
            val cells = row.select("th, td").toList()
            val dayText = cells.getOrNull(dayColumn)?.text().orEmpty()
            val dateText = cells.getOrNull(dateColumn)?.text().orEmpty()
            val explicitRowDate = explicitDate("$dayText $dateText", referenceDate)
            val weekday = weekdayNumber(dayText)
            when {
                explicitRowDate != null -> currentDate = explicitRowDate
                weekday != null -> currentDate = dayDate(dayText, monday, referenceDate)
                currentDate == null -> currentDate = dayDate(dateText, monday, referenceDate)
            }
            val date = currentDate ?: return@forEachIndexed
            val timeText = cells.getOrNull(timeColumn)?.text().orEmpty()
            val time = TIME_RANGE.find(timeText) ?: return@forEachIndexed
            val start = normalizeTime(time.groupValues[1])
            val end = normalizeTime(time.groupValues[2])
            val slot = PAIR_NUMBER.find(timeText)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: rowOffset + 1
            val content = subjectColumns.mapNotNull(cells::getOrNull)
                .firstOrNull { it.text().isNotBlank() } ?: return@forEachIndexed
            appendLesson(
                profile = profile,
                date = date,
                timeText = timeText,
                content = content,
                start = start,
                end = end,
                slot = slot,
                ordinal = tableIndex * 1000 + rowOffset,
                target = target,
                teacherText = if (teacherColumn >= 0 && teacherColumn != subjectColumn) {
                    cells.getOrNull(teacherColumn)?.text().orEmpty()
                } else {
                    ""
                },
                roomText = cells.getOrNull(roomColumn)?.text().orEmpty(),
                typeText = cells.getOrNull(typeColumn)?.text().orEmpty(),
            )
        }
    }

    private fun appendLesson(
        profile: ScheduleProfile,
        date: LocalDate,
        timeText: String,
        content: Element,
        start: String,
        end: String,
        slot: Int,
        ordinal: Int,
        target: MutableList<Lesson>,
        teacherText: String = "",
        roomText: String = "",
        typeText: String = "",
    ) {
        val lines = contentLines(content)
        if (lines.isEmpty()) return
        val subjectLine = lines.firstOrNull { line ->
            !TIME_RANGE.containsMatchIn(line) &&
                !EXPLICIT_DATE.containsMatchIn(line) &&
                weekdayNumber(line) == null &&
                teacherFromLine(line) == null &&
                roomFromLine(line) == null &&
                !TYPE_ONLY_LINE.matches(line)
        } ?: return
        val inlineTeacher = splitInlineTeacher(subjectLine)
        val subject = (inlineTeacher?.first ?: subjectLine)
            .replaceFirst(SUBJECT_PREFIX, "")
            .trim(' ', ':', '–', '-')
            .takeIf(String::isNotBlank)
            ?: return
        val teacher = lines.firstNotNullOfOrNull(::teacherFromLine)
            ?: teacherFromLine(teacherText)
            ?: teacherText.trim().takeIf(String::isNotBlank)
            ?: inlineTeacher?.second
            ?: ""
        val room = lines.firstNotNullOfOrNull(::roomFromLine)
            ?: roomFromLine(roomText)
            ?: roomText.trim().takeIf(String::isNotBlank)
            ?: ""
        val allTypeText = (lines + timeText + typeText + content.attr("class")).joinToString(" ").lowercase()
        val type = when {
            "лекц" in allTypeText || "lecture" in allTypeText || LECTURE_CODE.containsMatchIn(allTypeText) -> LessonType.LECTURE
            "лаб" in allTypeText || "laboratory" in allTypeText -> LessonType.LABORATORY
            "практ" in allTypeText || "practice" in allTypeText || PRACTICE_CODE.containsMatchIn(allTypeText) -> LessonType.PRACTICE
            else -> LessonType.UNKNOWN
        }
        val sourceKey = "${profile.id}|$date|$slot|$start-$end|$ordinal"
        target += Lesson(
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
        )
    }

    private fun dayDate(
        text: String,
        monday: LocalDate,
        referenceDate: LocalDate,
    ): LocalDate? {
        explicitDate(text, referenceDate)?.let { return it }
        val weekday = weekdayNumber(text) ?: return null
        return runCatching { monday.plus(DatePeriod(days = weekday - 1)) }.getOrNull()
    }

    private fun explicitDate(text: String, referenceDate: LocalDate): LocalDate? {
        val match = EXPLICIT_DATE.find(text) ?: return null
        val day = match.groupValues[1].toIntOrNull() ?: return null
        val month = match.groupValues[2].toIntOrNull() ?: return null
        val year = match.groupValues.getOrNull(3)?.toIntOrNull() ?: when {
            referenceDate.monthNumber == 12 && month == 1 -> referenceDate.year + 1
            referenceDate.monthNumber == 1 && month == 12 -> referenceDate.year - 1
            else -> referenceDate.year
        }
        return runCatching { LocalDate(year, month, day) }.getOrNull()
    }

    private fun collectTeacherValues(value: String, target: MutableSet<String>, allowPlainName: Boolean) {
        val candidates = value
            .replace(BR_TAG, "\n")
            .replace(BLOCK_START_TAG, "\n")
            .replace(BLOCK_END_TAG, "\n")
            .replace(HTML_TAG, " ")
            .replace("&nbsp;", " ", ignoreCase = true)
            .split(Regex("[\\n;/,|]+"))
            .map(::normalize)
            .filter(String::isNotBlank)
        candidates.forEach { candidate ->
            TEACHER_INITIALS.findAll(candidate).forEach { match -> target += normalize(match.value) }
            val labelled = teacherFromLine(candidate)
            val plainName = TEACHER_TITLE_PREFIX.replace(candidate, "").trim()
            when {
                labelled != null && isLikelyTeacherName(labelled) -> target += normalize(labelled)
                allowPlainName && isLikelyTeacherName(plainName) -> target += plainName
            }
        }
    }

    private fun isLikelyTeacherName(value: String): Boolean {
        val candidate = normalize(value).trim('.', ':', ' ', '–', '-')
        if (candidate.length !in 4..90 || candidate.any(Char::isDigit)) return false
        val words = TEACHER_NAME_WORD.findAll(candidate).map { it.value }.toList()
        if (words.size !in 2..4 || words.any { it.firstOrNull()?.isUpperCase() != true }) return false
        val normalized = candidate.lowercase().replace('ё', 'е')
        return TEACHER_NAME_EXCLUSIONS.none { exclusion -> normalized.contains(exclusion) }
    }

    private fun contentLines(element: Element): List<String> {
        val html = element.html()
            .replace(BR_TAG, "\n")
            .replace(BLOCK_START_TAG, "\n")
            .replace(BLOCK_END_TAG, "\n")
            .replace(HTML_TAG, " ")
            .replace("&nbsp;", " ", ignoreCase = true)
            .replace("&#160;", " ", ignoreCase = true)
        val rawLines = html.split('\n')
            .map { normalize(it) }
            .filter(String::isNotBlank)
        return rawLines.ifEmpty { listOf(normalize(element.text())).filter(String::isNotBlank) }
    }

    private fun normalize(value: String): String = value
        .replace('\u00a0', ' ')
        .replace(WHITESPACE, " ")
        .trim()

    private fun isTimeHeader(text: String): Boolean {
        val normalized = normalize(text).lowercase().replace('ё', 'е')
        return normalized.contains("время") || normalized.contains("час") || normalized.contains("time")
    }

    private fun isWeekdayHeader(text: String): Boolean {
        val normalized = normalize(text).lowercase().replace('ё', 'е')
        return normalized.contains("день") || normalized.contains("weekday") || weekdayNumber(normalized) != null
    }

    private fun isDateHeader(text: String): Boolean {
        val normalized = normalize(text).lowercase().replace('ё', 'е')
        return normalized.contains("дата") || normalized.contains("число") || normalized.contains("date")
    }

    private fun isSubjectHeader(text: String): Boolean {
        val normalized = normalize(text).lowercase().replace('ё', 'е')
        return listOf("предмет", "дисциплин", "наимен", "subject", "lesson").any { normalized.contains(it) }
    }

    private fun isTeacherHeader(text: String): Boolean {
        val normalized = normalize(text).lowercase().replace('ё', 'е')
        return listOf("преподав", "teacher", "лектор", "педагог").any { normalized.contains(it) }
    }

    private fun isRoomHeader(text: String): Boolean {
        val normalized = normalize(text).lowercase().replace('ё', 'е')
        return listOf("ауд", "каб", "room", "корпус").any { normalized.contains(it) }
    }

    private fun isTypeHeader(text: String): Boolean {
        val normalized = normalize(text).lowercase().replace('ё', 'е')
        return listOf("тип", "вид", "форма", "type").any { normalized.contains(it) }
    }

    private fun isGroupHeader(text: String): Boolean {
        val normalized = normalize(text).lowercase().replace('ё', 'е')
        return normalized.contains("групп") || normalized.contains("group")
    }

    private fun splitInlineTeacher(text: String): Pair<String, String>? {
        val match = INLINE_TEACHER_LINE.matchEntire(normalize(text)) ?: return null
        val subject = match.groupValues.getOrNull(1)?.trim().orEmpty()
        val teacher = match.groupValues.getOrNull(2)?.trim().orEmpty()
        return if (subject.isNotBlank() && teacher.isNotBlank()) subject to teacher else null
    }

    private fun teacherFromLine(text: String): String? {
        val normalized = normalize(text)
        return TEACHER_LINE.find(normalized)?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotBlank)
            ?: TEACHER_TITLE_LINE.matchEntire(normalized)?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotBlank)
            ?: PLAIN_TEACHER_LINE.matchEntire(normalized)?.value?.takeIf(String::isNotBlank)
    }

    private fun roomFromLine(text: String): String? {
        val normalized = normalize(text)
        return ROOM_LINE.find(normalized)?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotBlank)
            ?: NUMERIC_ROOM_LINE.matchEntire(normalized)?.value?.takeIf(String::isNotBlank)
    }

    private fun weekdayNumber(text: String): Int? {
        val normalized = normalize(text).lowercase().replace('ё', 'е')
        return when {
            Regex("\\bпн\\b|понедельник|панядзел").containsMatchIn(normalized) -> 1
            Regex("\\bвт\\b|вторник|аўторак|ауторок").containsMatchIn(normalized) -> 2
            Regex("\\bср\\b|среда|серада").containsMatchIn(normalized) -> 3
            Regex("\\bчт\\b|четверг|чацвер").containsMatchIn(normalized) -> 4
            Regex("\\bпт\\b|пятниц|пятніца|пятница").containsMatchIn(normalized) -> 5
            Regex("\\bсб\\b|суббот|субота").containsMatchIn(normalized) -> 6
            Regex("\\bвс\\b|воскресень|нядзел").containsMatchIn(normalized) -> 7
            else -> null
        }
    }

    private fun normalizeTime(value: String): String {
        val parts = value.replace('.', ':').split(':', limit = 2)
        return "${parts[0].padStart(2, '0')}:${parts.getOrElse(1) { "00" }}"
    }

    private companion object {
        val TIME_RANGE = Regex("(\\d{1,2}[:.]\\d{2})\\s*[-–—−]\\s*(\\d{1,2}[:.]\\d{2})")
        val PAIR_NUMBER = Regex("(\\d+)\\s*(?:пара|занятие|pair)", RegexOption.IGNORE_CASE)
        val EXPLICIT_DATE = Regex("(\\d{1,2})\\.(\\d{1,2})(?:\\.(\\d{4}))?")
        val WEEK_ONE = Regex("недел[ья]\\s*1|week\\s*1", RegexOption.IGNORE_CASE)
        val WEEK_TWO = Regex("недел[ья]\\s*2|week\\s*2", RegexOption.IGNORE_CASE)
        val TEACHER_LINE = Regex("(?:преподаватель|преп\\.?|teacher|викладчык)\\s*[:–-]?\\s*(.+)", RegexOption.IGNORE_CASE)
        val TEACHER_NAME_WORD = Regex("[A-Za-zА-Яа-яЁё-]+")
        val TEACHER_INITIALS = Regex("[А-ЯЁ][а-яё-]+\\s+[А-ЯЁ]\\.\\s*[А-ЯЁ]\\.?", RegexOption.IGNORE_CASE)
        val TEACHER_TITLE_PREFIX = Regex("^(?:проф(?:ессор)?|доц(?:ент)?|ассистент|ст\\.?\\s*преп\\.?|старший\\s+преподаватель)\\.?\\s+", RegexOption.IGNORE_CASE)
        val TEACHER_NAME_EXCLUSIONS = listOf(
            "понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье",
            "лекция", "практика", "лабораторная", "расписание", "преподав", "введите", "фио",
            "группа", "групп", "время", "дата", "занят", "курс", "недел", "четн", "нечетн", "teacher", "name",
        )
        val LECTURE_CODE = Regex("(^|\\s)лк\\.?($|\\s)", RegexOption.IGNORE_CASE)
        val PRACTICE_CODE = Regex("(^|\\s)пз\\.?($|\\s)", RegexOption.IGNORE_CASE)
        val INLINE_TEACHER_LINE = Regex(
            "^(.+?)\\s+((?:(?:проф(?:ессор)?|доц(?:ент)?|ст\\.?\\s*преп\\.?|ассистент)\\s+)?[А-ЯЁ][а-яё-]+\\s+[А-ЯЁ]\\.\\s*[А-ЯЁ]\\.?)$",
            RegexOption.IGNORE_CASE,
        )
        val TEACHER_TITLE_LINE = Regex(
            "^(?:(?:проф(?:ессор)?\\.?|доц(?:ент)?\\.?|ст\\.?\\s*преп\\.?|ассистент|старший\\s+преподаватель)\\s+)?([А-ЯЁ][а-яё-]+\\s+[А-ЯЁ]\\.\\s*[А-ЯЁ]\\.?)$",
            RegexOption.IGNORE_CASE,
        )
        val PLAIN_TEACHER_LINE = Regex("^[А-ЯЁ][а-яё-]+\\s+[А-ЯЁ]\\.\\s*[А-ЯЁ]\\.?$", RegexOption.IGNORE_CASE)
        val ROOM_LINE = Regex("(?:ауд(?:итория)?\\.?|каб(?:инет)?\\.?|room|корпус)\\s*[:№–-]?\\s*(.+)", RegexOption.IGNORE_CASE)
        val NUMERIC_ROOM_LINE = Regex("^\\d{1,4}[a-zа-яё]?(?:\\s+[a-zа-яё-]+)?$", RegexOption.IGNORE_CASE)
        val TYPE_ONLY_LINE = Regex("^(?:лекц(?:ия)?\\.?|лаб(?:ораторн(?:ая)?)?\\.?|практ(?:ика)?\\.?|лк\\.?|пз\\.?|семинар|зачет|экзамен|lecture|laboratory|practice)$", RegexOption.IGNORE_CASE)
        val SUBJECT_PREFIX = Regex("^(?:лекция|лекц\\.?|лабораторная|лаб\\.?|практика|практ\\.?|лк\\.?|пз\\.?)\\s*[:–-]?\\s*", RegexOption.IGNORE_CASE)
        val WHITESPACE = Regex("\\s+")
        val BR_TAG = Regex("(?i)<br\\s*/?>")
        val BLOCK_START_TAG = Regex("(?i)<(?:div|p|li)[^>]*>")
        val BLOCK_END_TAG = Regex("(?i)</(?:div|p|li)\\s*>")
        val HTML_TAG = Regex("<[^>]+>")
    }
}
