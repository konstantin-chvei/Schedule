package com.example.bruschedule.data.scraper

import com.example.bruschedule.data.remote.ScheduleCsvTable
import com.example.bruschedule.domain.model.Lesson
import com.example.bruschedule.domain.model.LessonType
import com.example.bruschedule.domain.model.LessonTypePalette
import com.example.bruschedule.domain.model.ScheduleProfile
import com.example.bruschedule.domain.repository.ScheduleHtmlParser
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber

class GitHubCsvScheduleParser : ScheduleHtmlParser {
    override val formatVersion: Int = FORMAT_VERSION

    override fun parse(profile: ScheduleProfile, html: String, referenceDate: LocalDate): List<Lesson> {
        val rows = ScheduleCsvTable.parse(html)
        require(rows.size >= 2) { "В GitHub-файле расписания нет строк занятий" }
        val header = rows.first().map(::normalizeHeader)
        val columns = CsvColumns(header)
        val dateColumn = columns.required("date", "lesson_date", "дата")
        val startColumn = columns.find("start_time", "start", "начало")
        val endColumn = columns.find("end_time", "end", "окончание")
        val combinedTimeColumn = columns.find("time", "время", "interval")
        require((startColumn != null && endColumn != null) || combinedTimeColumn != null) {
            "В GitHub-файле нужны столбцы времени начала и конца пары"
        }
        val subjectColumn = columns.required("subject", "subject_name", "предмет", "дисциплина")
        val teacherColumn = columns.find("teacher", "teacher_name", "преподаватель")
        val roomColumn = columns.find("classroom", "room", "аудитория", "кабинет")
        val typeColumn = columns.find("type", "lesson_type", "тип")
        val slotColumn = columns.find("slot", "slot_number", "пара", "номер пары")
        val sourceKeyColumn = columns.find("source_key", "key", "идентификатор")

        val lessons = rows.drop(1).mapIndexedNotNull { rowIndex, row ->
            val subject = row.valueAt(subjectColumn).trim()
            if (subject.isBlank()) return@mapIndexedNotNull null
            val rawDate = row.valueAt(dateColumn)
            val date = parseDate(rawDate)
                ?: error("Некорректная дата «$rawDate» в строке ${rowIndex + 2}; используйте ГГГГ-ММ-ДД")
            val combined = row.valueAt(combinedTimeColumn)
            val combinedMatch = combined.takeIf(String::isNotBlank)?.let { TIME_RANGE.find(it) }
            val start = startColumn?.let { row.valueAt(it) }?.takeIf(String::isNotBlank)
                ?: combinedMatch?.groupValues?.getOrNull(1)
                ?: error("В строке ${rowIndex + 2} отсутствует время начала")
            val end = endColumn?.let { row.valueAt(it) }?.takeIf(String::isNotBlank)
                ?: combinedMatch?.groupValues?.getOrNull(2)
                ?: error("В строке ${rowIndex + 2} отсутствует время окончания")
            val normalizedStart = normalizeTime(start)
            val normalizedEnd = normalizeTime(end)
            val slot = slotColumn?.let { row.valueAt(it) }?.toIntOrNull() ?: rowIndex + 1
            val teacher = teacherColumn?.let { row.valueAt(it) }.orEmpty().trim()
            val classroom = roomColumn?.let { row.valueAt(it) }.orEmpty().trim()
            val type = classify(row.valueAt(typeColumn))
            val sourceKey = sourceKeyColumn?.let { row.valueAt(it) }?.trim().orEmpty().ifBlank {
                listOf(date, normalizedStart, normalizedEnd, slot, subject, teacher, classroom)
                    .joinToString("|") { it.toString().trim().lowercase().replace('ё', 'е') }
            }
            val stableKey = "github:${profile.id}:$sourceKey"

            Lesson(
                id = "web:$stableKey",
                profileId = profile.id,
                subjectName = subject,
                teacherName = teacher,
                classroom = classroom,
                startTime = normalizedStart,
                endTime = normalizedEnd,
                type = type,
                colorHex = LessonTypePalette.accent(type),
                isCustom = false,
                version = 1,
                lessonDate = date.toString(),
                dayOfWeek = date.dayOfWeek.isoDayNumber,
                slotNumber = slot,
                sourceKey = stableKey,
            )
        }

        val parsed = lessons.distinctBy(Lesson::sourceKey)
            .sortedWith(compareBy<Lesson>({ it.lessonDate }, { it.startTime }, { it.slotNumber }, { it.id }))
        require(parsed.isNotEmpty()) {
            "В GitHub-файле нет распознаваемых пар (проверена неделя от $referenceDate)"
        }
        return parsed
    }

    private fun classify(value: String): LessonType {
        val normalized = value.lowercase().replace('ё', 'е')
        return when {
            "лекц" in normalized -> LessonType.LECTURE
            "лаборатор" in normalized || "лаб." in normalized -> LessonType.LABORATORY
            "практ" in normalized || "семинар" in normalized -> LessonType.PRACTICE
            else -> LessonType.UNKNOWN
        }
    }

    private fun normalizeTime(value: String): String {
        val match = TIME_POINT.matchEntire(value.trim())
            ?: error("Некорректное время «$value» в CSV расписания")
        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].toInt()
        require(hour in 0..23 && minute in 0..59) { "Время «$value» вне диапазона" }
        return "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
    }

    private fun parseDate(value: String): LocalDate? {
        val raw = value.trim()
        runCatching { LocalDate.parse(raw) }.getOrNull()?.let { return it }
        val match = DATE_DMY.matchEntire(raw) ?: return null
        val day = match.groupValues[1].toIntOrNull() ?: return null
        val month = match.groupValues[2].toIntOrNull() ?: return null
        val year = match.groupValues[3].toIntOrNull() ?: return null
        return runCatching { LocalDate(year, month, day) }.getOrNull()
    }

    private fun normalizeHeader(value: String): String = value
        .trim()
        .lowercase()
        .replace('ё', 'е')
        .replace(Regex("[\\s-]+"), "_")

    private fun List<String>.valueAt(index: Int?): String = index?.let { getOrNull(it) }.orEmpty()

    private class CsvColumns(private val header: List<String>) {
        fun find(vararg names: String): Int? {
            val aliases = names.map { name ->
                name.trim().lowercase().replace('ё', 'е').replace(Regex("""[\s-]+"""), "_")
            }.toSet()
            return header.indexOfFirst { it in aliases }.takeIf { it >= 0 }
        }

        fun required(vararg names: String): Int = find(*names)
            ?: error("В GitHub CSV отсутствует столбец ${names.first()}")
    }

    private companion object {
        const val FORMAT_VERSION = 1
        val TIME_POINT = Regex("(\\d{1,2})[.:](\\d{2})")
        val TIME_RANGE = Regex("(\\d{1,2}[.:]\\d{2})\\s*[-–—]\\s*(\\d{1,2}[.:]\\d{2})")
        val DATE_DMY = Regex("(\\d{1,2})[./](\\d{1,2})[./](\\d{4})")
    }
}
