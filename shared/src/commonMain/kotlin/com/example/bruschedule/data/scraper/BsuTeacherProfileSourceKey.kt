package com.example.bruschedule.data.scraper

data class BsuTeacherScheduleSources(
    val teacherName: String,
    val urls: List<String>,
    val facultyName: String? = null,
)

object BsuTeacherProfileSourceKey {
    private const val V1_PREFIX = "bsu-teacher-sources-v1\n"
    private const val V2_PREFIX = "bsu-teacher-sources-v2\n"

    fun encode(teacherName: String, urls: List<String>, facultyName: String? = null): String {
        val cleanName = teacherName.trim()
        val cleanFaculty = facultyName?.trim()?.takeIf(String::isNotBlank)
        val cleanUrls = urls.map(String::trim).filter(String::isNotBlank).distinct()
        require(cleanName.isNotBlank()) { "Для профиля преподавателя не указано ФИО" }
        require(cleanUrls.isNotEmpty()) { "Для профиля преподавателя не указаны источники расписания" }
        require(cleanName.none { it == '\n' || it == '\r' }) { "Некорректное ФИО преподавателя" }
        require(cleanFaculty == null || cleanFaculty.none { it == '\n' || it == '\r' }) {
            "Некорректный факультет преподавателя"
        }
        require(cleanUrls.none { '\n' in it || '\r' in it }) { "Некорректная ссылка на расписание преподавателя" }
        return buildString {
            if (cleanFaculty == null) {
                append(V1_PREFIX)
                append(cleanName)
            } else {
                append(V2_PREFIX)
                append(cleanFaculty).append('\n').append(cleanName)
            }
            cleanUrls.forEach { url -> append('\n').append(url) }
        }
    }

    fun decode(value: String?): BsuTeacherScheduleSources? {
        if (value == null) return null
        val facultyName: String?
        val lines: List<String>
        when {
            value.startsWith(V2_PREFIX) -> {
                val entries = value.removePrefix(V2_PREFIX).split('\n')
                facultyName = entries.firstOrNull()?.trim()?.takeIf(String::isNotBlank)
                if (facultyName == null) return null
                lines = entries.drop(1)
            }
            value.startsWith(V1_PREFIX) -> {
                facultyName = null
                lines = value.removePrefix(V1_PREFIX).split('\n')
            }
            else -> return null
        }
        val teacherName = lines.firstOrNull()?.trim().orEmpty()
        val urls = lines.drop(1).map(String::trim).filter(String::isNotBlank).distinct()
        if (teacherName.isBlank() || urls.isEmpty()) return null
        return BsuTeacherScheduleSources(teacherName, urls, facultyName)
    }

    fun teacherName(value: String?): String? = decode(value)?.teacherName
}

object BsuTeacherNameMatcher {
    private val word = Regex("[A-Za-zА-Яа-яЁё-]+")
    private val titles = setOf(
        "проф", "профессор", "доц", "доцент", "асс", "ассистент", "преп", "преподаватель",
        "ст", "старший", "старшая", "младший", "младшая", "ведущий", "ведущая",
    )

    fun normalize(value: String): String = value.lowercase()
        .replace('ё', 'е')
        .filter(Char::isLetterOrDigit)

    fun matches(first: String, second: String): Boolean {
        val left = identity(first) ?: return normalize(first) == normalize(second)
        val right = identity(second) ?: return normalize(first) == normalize(second)
        return left == right
    }

    private fun identity(value: String): String? {
        val parts = word.findAll(value)
            .map { normalize(it.value) }
            .filter { it.isNotBlank() && it !in titles }
            .toList()
        if (parts.size < 2) return null
        val surname = parts.first()
        val initials = parts.drop(1).joinToString(separator = "") { it.first().toString() }
        return "$surname:$initials"
    }
}
