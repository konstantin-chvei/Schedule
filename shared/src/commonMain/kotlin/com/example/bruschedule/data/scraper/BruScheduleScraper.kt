package com.example.bruschedule.data.scraper

import com.example.bruschedule.domain.model.Lesson
import com.example.bruschedule.domain.model.LessonType
import com.example.bruschedule.domain.model.LessonTypePalette
import com.example.bruschedule.domain.model.ScheduleProfile
import com.example.bruschedule.domain.model.ScheduleProfileType
import com.example.bruschedule.domain.repository.ScheduleHtmlParser
import com.fleeksoft.ksoup.Ksoup
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus

class BruScheduleScraper : ScheduleHtmlParser {
    override val formatVersion: Int = PARSER_FORMAT_VERSION

    override fun parse(
        profile: ScheduleProfile,
        html: String,
        referenceDate: LocalDate,
    ): List<Lesson> {
        require(html.isNotBlank()) { "Пустой HTML-ответ расписания" }
        val document = Ksoup.parse(html)
        val tables = document.select("table.schedule-table").toList()
        require(tables.isNotEmpty()) {
            "На странице не найдена таблица .schedule-table (возможно, сайт изменил разметку)"
        }

        val pageText = document.text()
        val explicitRangeYear = EXPLICIT_DATE.find(pageText)?.groupValues?.getOrNull(3)?.toIntOrNull()
        val pageYear = explicitRangeYear
            ?: YEAR.find(pageText)?.value?.toIntOrNull()
            ?: referenceDate.year
        val weekHeaders = document.select(".week-header").map { it.text() }
        val styleText = document.select("style").joinToString("\n") { it.html() }
        val result = mutableListOf<Lesson>()

        tables.forEachIndexed tableLoop@{ tableIndex, table ->
            val weekHeader = weekHeaders.getOrNull(tableIndex).orEmpty()
            val weekYear = weekStartYear(weekHeader, pageYear)
            val dayHeaders = table.select("thead tr").firstOrNull()?.select("th")?.toList().orEmpty()
            require(dayHeaders.size >= 2) { "В таблице расписания отсутствуют заголовки дней" }
            val dayColumns = parseDayColumns(dayHeaders.drop(1), weekYear, referenceDate)
            val rows = table.select("tbody tr").toList()

            rows.forEachIndexed rowLoop@{ rowIndex, row ->
                val cells = row.select("td").toList()
                val timeCellIndex = cells.indexOfFirst { it.attr("class").splitWhitespace().contains("time-col") }
                if (timeCellIndex < 0) return@rowLoop
                val timeText = cells[timeCellIndex].text()
                val timeMatch = TIME_RANGE.find(timeText) ?: return@rowLoop
                val startTime = normalizeTime(timeMatch.groupValues[1])
                val endTime = normalizeTime(timeMatch.groupValues[2])
                val slotNumber = PAIR_NUMBER.find(timeText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: rowIndex + 1

                cells.forEachIndexed cellLoop@{ cellIndex, cell ->
                    if (cellIndex <= timeCellIndex) return@cellLoop
                    val dayColumnIndex = cellIndex - timeCellIndex - 1
                    val dayColumn = dayColumns.getOrNull(dayColumnIndex) ?: return@cellLoop
                    val cards = cell.select(".lesson-card, .occ-card").toList()
                    cards.forEachIndexed cardLoop@{ cardIndex, card ->
                        val subject = card.select(".title, .occ-line2").firstOrNull()?.text()
                            ?.replace(WHITESPACE, " ")?.trim().orEmpty()
                        if (subject.isBlank()) return@cardLoop

                        val infoElement = card.select(".info").firstOrNull()
                        val infoLines = infoElement?.let(::elementTextLines).orEmpty()
                        val occurrenceTypeLine = card.select(".occ-line1").firstOrNull()?.text().orEmpty()
                        val typeContext = listOf(occurrenceTypeLine, infoLines.lastOrNull().orEmpty())
                            .filter(String::isNotBlank).joinToString(" ")
                        val sourceColor = inlineBackground(card.attr("style"))
                            ?: cssBackground(card.attr("class"), styleText)
                        val lessonType = classifyLessonType(
                            classNames = card.attr("class").splitWhitespace(),
                            visibleType = typeContext,
                            sourceColor = sourceColor,
                        )

                        val room = card.select(".occ-line4").firstOrNull()?.text()
                            ?.let(::cleanField)
                            ?.takeIf(String::isNotBlank)
                            ?: infoLines.firstOrNull { it.startsWith("📍") || it.contains("ауд.", ignoreCase = true) }
                                ?.let(::cleanField)
                                .orEmpty()
                        val teacher = if (profile.type == ScheduleProfileType.TEACHER) {
                            teacherHeading(document, profile)
                        } else {
                            infoLines.firstOrNull { it.startsWith("👤") || it.startsWith("Преподаватель", ignoreCase = true) }
                                ?.let(::cleanField).orEmpty()
                        }
                        val lessonDate = explicitCardDate(card) ?: dayColumn.date
                        val sourceKey = buildString {
                            append(profile.id)
                            append('|').append(lessonDate)
                            append('|').append(slotNumber)
                            append('|').append(startTime).append('-').append(endTime)
                            append('|').append(cardIndex)
                        }

                        result += Lesson(
                            id = "web:$sourceKey",
                            profileId = profile.id,
                            subjectName = subject,
                            teacherName = teacher,
                            classroom = room,
                            startTime = startTime,
                            endTime = endTime,
                            type = lessonType,
                            colorHex = LessonTypePalette.accent(lessonType),
                            isCustom = false,
                            version = 1,
                            lessonDate = lessonDate.toString(),
                            dayOfWeek = lessonDate.dayOfWeek.isoDayNumber,
                            slotNumber = slotNumber,
                            sourceKey = sourceKey,
                            sourceColorHex = sourceColor,
                        )
                    }
                }
            }
        }

        return result.distinctBy { it.sourceKey }
            .sortedWith(compareBy<Lesson>({ it.lessonDate }, { it.startTime }, { it.slotNumber }, { it.id }))
    }

    override fun hasAlternatingTwoWeekCycle(html: String): Boolean {
        val pageText = Ksoup.parse(html).text().lowercase().replace('ё', 'е')
        return WEEK_ONE_HEADER.containsMatchIn(pageText) && WEEK_TWO_HEADER.containsMatchIn(pageText)
    }

    private fun parseDayColumns(
        headers: List<com.fleeksoft.ksoup.nodes.Element>,
        initialYear: Int,
        referenceDate: LocalDate,
    ): List<DayColumn> {
        var year = initialYear
        var previousMonth: Int? = null
        return headers.mapIndexed { index, header ->
            val text = header.text()
            val dateText = header.select("small").firstOrNull()?.text().orEmpty().ifBlank { text }
            val match = HEADER_DATE.find(dateText)
            val weekday = weekdayNumber(text) ?: ((index % 7) + 1)
            val date = if (match != null) {
                val day = match.groupValues[1].toInt()
                val month = match.groupValues[2].toInt()
                val headerYear = match.groupValues.getOrNull(3)?.toIntOrNull()
                if (headerYear != null) {
                    year = headerYear
                } else if (previousMonth != null && month < previousMonth!!) {
                    year += 1
                }
                previousMonth = month
                runCatching { LocalDate(year, month, day) }.getOrNull()
            } else {
                val monday = referenceDate.minus(kotlinx.datetime.DatePeriod(days = referenceDate.dayOfWeek.isoDayNumber - 1))
                runCatching { monday.plus(kotlinx.datetime.DatePeriod(days = index)) }.getOrNull()
            }
            DayColumn(date = date ?: referenceDate, weekday = weekday)
        }
    }

    private fun explicitCardDate(card: com.fleeksoft.ksoup.nodes.Element): LocalDate? {
        val text = card.select(".occ-line5").firstOrNull()?.text().orEmpty()
        val iso = ISO_DATE.find(text)
        if (iso != null) {
            val year = iso.groupValues[1].toIntOrNull() ?: return null
            val month = iso.groupValues[2].toIntOrNull() ?: return null
            val day = iso.groupValues[3].toIntOrNull() ?: return null
            return runCatching { LocalDate(year, month, day) }.getOrNull()
        }
        val dmy = EXPLICIT_DATE.find(text) ?: return null
        return runCatching {
            LocalDate(
                dmy.groupValues[3].toInt(),
                dmy.groupValues[2].toInt(),
                dmy.groupValues[1].toInt(),
            )
        }.getOrNull()
    }

    private fun teacherHeading(
        document: com.fleeksoft.ksoup.nodes.Document,
        profile: ScheduleProfile,
    ): String {
        val heading = document.select(".hero h2, h2").firstOrNull()?.text()
            ?.replace("👨‍🏫", "")?.replace("👩‍🏫", "")?.trim()
        return heading?.takeIf(String::isNotBlank) ?: profile.name
    }

    private fun elementTextLines(element: com.fleeksoft.ksoup.nodes.Element): List<String> {
        val html = element.html()
            .replace(BR_TAG, "\n")
            .replace(BLOCK_END_TAG, "\n")
            .replace(HTML_TAG, " ")
            .replace(Regex("&nbsp;", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("&#160;", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("&amp;", RegexOption.IGNORE_CASE), "&")
        return html.split('\n')
            .map { it.replace(WHITESPACE, " ").trim() }
            .filter(String::isNotBlank)
    }

    private fun cleanField(value: String): String = value
        .replace("👤", "")
        .replace("📍", "")
        .replace("🏛️", "")
        .replace("🏛", "")
        .replace(Regex("^(Учитель|Преподаватель|Аудитория)\\s*:?\\s*", RegexOption.IGNORE_CASE), "")
        .replace(WHITESPACE, " ")
        .trim()

    companion object {
        const val PARSER_FORMAT_VERSION = 3

        private val WEEK_ONE_HEADER = Regex("недел[ья]\\s*1", RegexOption.IGNORE_CASE)
        private val WEEK_TWO_HEADER = Regex("недел[ья]\\s*2", RegexOption.IGNORE_CASE)
        private val TIME_RANGE = Regex("(\\d{1,2}:\\d{2})\\s*[-–—−]\\s*(\\d{1,2}:\\d{2})")
        private val PAIR_NUMBER = Regex("(\\d+)\\s*(?:пара|занятие)", RegexOption.IGNORE_CASE)
        private val HEADER_DATE = Regex("(\\d{1,2})\\.(\\d{1,2})(?:\\.(\\d{4}))?")
        private val EXPLICIT_DATE = Regex("(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})")
        private val ISO_DATE = Regex("(20\\d{2})-(\\d{1,2})-(\\d{1,2})")
        private val YEAR = Regex("20\\d{2}")
        private val WHITESPACE = Regex("\\s+")
        private val BR_TAG = Regex("(?i)<br\\s*/?>")
        private val BLOCK_END_TAG = Regex("(?i)</(?:div|p|li)\\s*>")
        private val HTML_TAG = Regex("<[^>]+>")

        private fun weekStartYear(header: String, fallbackYear: Int): Int {
            val displayedYear = YEAR.find(header)?.value?.toIntOrNull() ?: return fallbackYear
            val dates = HEADER_DATE.findAll(header).toList()
            if (dates.size >= 2) {
                val firstMonth = dates.first().groupValues[2].toIntOrNull()
                val lastMonth = dates[1].groupValues[2].toIntOrNull()
                if (firstMonth != null && lastMonth != null && firstMonth > lastMonth) {
                    return displayedYear - 1
                }
            }
            return displayedYear
        }

        private fun normalizeTime(value: String): String {
            val parts = value.split(':', limit = 2)
            return "${parts[0].padStart(2, '0')}:${parts.getOrElse(1) { "00" }}"
        }

        private fun weekdayNumber(text: String): Int? {
            val normalized = text.lowercase()
            return when {
                Regex("\\bпн\\b|понедельник").containsMatchIn(normalized) -> 1
                Regex("\\bвт\\b|вторник").containsMatchIn(normalized) -> 2
                Regex("\\bср\\b|среда").containsMatchIn(normalized) -> 3
                Regex("\\bчт\\b|четверг").containsMatchIn(normalized) -> 4
                Regex("\\bпт\\b|пятниц").containsMatchIn(normalized) -> 5
                Regex("\\bсб\\b|суббот").containsMatchIn(normalized) -> 6
                Regex("\\bвс\\b|воскресень").containsMatchIn(normalized) -> 7
                else -> null
            }
        }

        private fun classifyLessonType(
            classNames: List<String>,
            visibleType: String,
            sourceColor: String?,
        ): LessonType {
            val classes = classNames.joinToString(" ").lowercase()
            val label = visibleType.lowercase().replace('ё', 'е')

            if (classes.contains("lesson-lecture") || classes.contains("occ-lecture") ||
                label.contains("лекци") || label.contains("lecture")
            ) return LessonType.LECTURE
            if (classes.contains("lesson-lab") || classes.contains("occ-lab") ||
                label.contains("лаборатор") || label.contains("лаб.") || label.contains("laboratory")
            ) return LessonType.LABORATORY
            if (classes.contains("lesson-practice") || classes.contains("occ-practice") ||
                label.contains("практик") || label.contains("practice")
            ) return LessonType.PRACTICE

            val explicitlyUnsupportedClass = listOf(
                "lesson-pe", "occ-pe", "lesson-exam", "occ-exam", "lesson-credit", "occ-credit",
                "consultation", "coursework", "in_class_control", "curator_hour", "parttime",
            ).any(classes::contains)
            val explicitlyUnsupportedLabel = listOf(
                "физкультур", "физ-ра", "экзамен", "зачет", "зачёт", "консультац",
                "курсов", "контроль", "информационный час", "инф. час",
            ).any(label::contains)
            if (explicitlyUnsupportedClass || explicitlyUnsupportedLabel) return LessonType.UNKNOWN

            return when (colorFamily(sourceColor)) {
                ColorFamily.BLUE -> LessonType.LECTURE
                ColorFamily.RED -> LessonType.LABORATORY
                ColorFamily.WARM_OR_GREEN -> LessonType.PRACTICE
                ColorFamily.NONE -> LessonType.UNKNOWN
            }
        }

        private enum class ColorFamily { BLUE, RED, WARM_OR_GREEN, NONE }

        private fun colorFamily(hex: String?): ColorFamily {
            if (hex == null || !hex.startsWith('#') || hex.length != 7) return ColorFamily.NONE
            val red = hex.substring(1, 3).toIntOrNull(16) ?: return ColorFamily.NONE
            val green = hex.substring(3, 5).toIntOrNull(16) ?: return ColorFamily.NONE
            val blue = hex.substring(5, 7).toIntOrNull(16) ?: return ColorFamily.NONE
            return when {
                blue > red * 1.15 && blue > green * 1.05 -> ColorFamily.BLUE
                red > green * 1.25 && red > blue * 1.15 -> ColorFamily.RED
                red > 170 && green > 100 && blue < 150 -> ColorFamily.WARM_OR_GREEN
                green > red * 1.15 && green > blue * 1.05 -> ColorFamily.WARM_OR_GREEN
                else -> ColorFamily.NONE
            }
        }

        private fun inlineBackground(style: String): String? = cssBackgroundValue(style)

        private fun cssBackground(classes: String, styles: String): String? {
            val classNames = classes.splitWhitespace()
            if (classNames.isEmpty()) return null
            val rules = CSS_RULE.findAll(styles)
            for (rule in rules) {
                val selector = rule.groupValues[1]
                val declaration = rule.groupValues[2]
                val matchesClass = classNames.any { className ->
                    Regex("(?:^|[\\s>+~,.])\\.${Regex.escape(className)}(?:$|[\\s>+~,:.#\\[])" )
                        .containsMatchIn(selector)
                }
                if (matchesClass) {
                    cssBackgroundValue(declaration)?.let { return it }
                }
            }
            return null
        }

        private fun cssBackgroundValue(styleOrDeclaration: String): String? {
            val declarations = styleOrDeclaration.split(';')
            val background = declarations.firstNotNullOfOrNull { declaration ->
                val colon = declaration.indexOf(':')
                if (colon < 0) null else {
                    val property = declaration.substring(0, colon).trim().lowercase()
                    val value = declaration.substring(colon + 1).trim()
                    if (property == "background-color" || property == "background") value else null
                }
            } ?: return null
            val rgb = RGB_COLOR.find(background)
            if (rgb != null) {
                val channels = rgb.groupValues.drop(1).take(3).map { it.toIntOrNull()?.coerceIn(0, 255) ?: return null }
                return channels.joinToString(prefix = "#", separator = "") { it.toString(16).padStart(2, '0') }
                    .uppercase()
            }
            val hash = HEX_COLOR.find(background)?.value ?: NAMED_COLORS[background.lowercase()] ?: return null
            return normalizeHex(hash)
        }

        private fun normalizeHex(value: String): String? {
            val raw = value.removePrefix("#")
            return when (raw.length) {
                3 -> "#${raw.map { "$it$it" }.joinToString("")}".uppercase()
                6 -> "#$raw".uppercase()
                8 -> "#${raw.take(6)}".uppercase()
                else -> null
            }
        }

        private val CSS_RULE = Regex("""([^{}]+)\{([^{}]*)\}""", RegexOption.DOT_MATCHES_ALL)
        private val RGB_COLOR = Regex("rgba?\\(\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})", RegexOption.IGNORE_CASE)
        private val HEX_COLOR = Regex("#[0-9a-fA-F]{3}(?:[0-9a-fA-F]{3,5})?")
        private val NAMED_COLORS = mapOf(
            "blue" to "#0000FF", "cyan" to "#00FFFF", "lightblue" to "#ADD8E6",
            "red" to "#FF0000", "pink" to "#FFC0CB", "tomato" to "#FF6347",
            "yellow" to "#FFFF00", "green" to "#008000", "orange" to "#FFA500",
        )
    }

    private data class DayColumn(val date: LocalDate, val weekday: Int)
}

private fun String.splitWhitespace(): List<String> = trim().split(Regex("\\s+")).filter(String::isNotBlank)
