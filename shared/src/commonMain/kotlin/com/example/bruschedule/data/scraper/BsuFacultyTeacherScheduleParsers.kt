package com.example.bruschedule.data.scraper

import com.example.bruschedule.domain.model.Lesson
import com.example.bruschedule.domain.model.ScheduleProfile
import com.example.bruschedule.domain.model.ScheduleProfileType
import com.fleeksoft.ksoup.Ksoup
import kotlinx.datetime.LocalDate

internal interface BsuFacultyTeacherScheduleParser {
    fun discoverTeacherNames(
        html: String,
        genericParser: GenericHtmlScheduleParser,
    ): List<String> = genericParser.discoverTeacherNames(html)

    fun parse(
        profile: ScheduleProfile,
        html: String,
        referenceDate: LocalDate,
        scheduleParser: BsuScheduleHtmlParser,
        genericParser: GenericHtmlScheduleParser,
    ): List<Lesson>
}

internal open class BsuGoogleSheetsTeacherScheduleParser : BsuFacultyTeacherScheduleParser {
    override fun parse(
        profile: ScheduleProfile,
        html: String,
        referenceDate: LocalDate,
        scheduleParser: BsuScheduleHtmlParser,
        genericParser: GenericHtmlScheduleParser,
    ): List<Lesson> = parseFacultyTeacherSchedule(
        profile = profile,
        html = html,
        referenceDate = referenceDate,
        scheduleParser = scheduleParser,
        genericParser = genericParser,
        preferGeneric = false,
    )
}

internal class BsuEconomyFacultyTeacherScheduleParser : BsuGoogleSheetsTeacherScheduleParser()

internal class BsuFpmiFacultyTeacherScheduleParser : BsuGoogleSheetsTeacherScheduleParser()

internal class BsuRctFacultyTeacherScheduleParser : BsuGoogleSheetsTeacherScheduleParser()

internal class BsuMmfFacultyTeacherScheduleParser : BsuFacultyTeacherScheduleParser {
    override fun parse(
        profile: ScheduleProfile,
        html: String,
        referenceDate: LocalDate,
        scheduleParser: BsuScheduleHtmlParser,
        genericParser: GenericHtmlScheduleParser,
    ): List<Lesson> = parseFacultyTeacherSchedule(
        profile = profile,
        html = html,
        referenceDate = referenceDate,
        scheduleParser = scheduleParser,
        genericParser = genericParser,
        preferGeneric = true,
    )
}

internal open class BsuDocumentFacultyTeacherScheduleParser : BsuFacultyTeacherScheduleParser {
    override fun parse(
        profile: ScheduleProfile,
        html: String,
        referenceDate: LocalDate,
        scheduleParser: BsuScheduleHtmlParser,
        genericParser: GenericHtmlScheduleParser,
    ): List<Lesson> = parseFacultyTeacherSchedule(
        profile = profile,
        html = html,
        referenceDate = referenceDate,
        scheduleParser = scheduleParser,
        genericParser = genericParser,
        preferGeneric = true,
    )
}

internal class BsuGeographyFacultyTeacherScheduleParser : BsuDocumentFacultyTeacherScheduleParser()

internal class BsuFirFacultyTeacherScheduleParser : BsuDocumentFacultyTeacherScheduleParser()

internal class BsuFscFacultyTeacherScheduleParser : BsuDocumentFacultyTeacherScheduleParser()

internal class BsuBusinessInstituteTeacherScheduleParser : BsuDocumentFacultyTeacherScheduleParser()

internal class BsuHistoryFacultyTeacherScheduleParser : BsuDocumentFacultyTeacherScheduleParser()

internal class BsuLawFacultyTeacherScheduleParser : BsuDocumentFacultyTeacherScheduleParser()

internal class BsuChemistryFacultyTeacherScheduleParser : BsuDocumentFacultyTeacherScheduleParser()

internal class BsuPhysicsFacultyTeacherScheduleParser : BsuDocumentFacultyTeacherScheduleParser()

internal class BsuJournalismFacultyTeacherScheduleParser : BsuDocumentFacultyTeacherScheduleParser()

internal class BsuPhilologyFacultyTeacherScheduleParser : BsuDocumentFacultyTeacherScheduleParser()

internal class BsuFfsnFacultyTeacherScheduleParser : BsuDocumentFacultyTeacherScheduleParser()

internal class BsuBiologyFacultyTeacherScheduleParser : BsuDocumentFacultyTeacherScheduleParser()

internal class BsuGenericFacultyTeacherScheduleParser : BsuFacultyTeacherScheduleParser {
    override fun parse(
        profile: ScheduleProfile,
        html: String,
        referenceDate: LocalDate,
        scheduleParser: BsuScheduleHtmlParser,
        genericParser: GenericHtmlScheduleParser,
    ): List<Lesson> = parseFacultyTeacherSchedule(
        profile = profile,
        html = html,
        referenceDate = referenceDate,
        scheduleParser = scheduleParser,
        genericParser = genericParser,
        preferGeneric = false,
    )
}

internal object BsuFacultyTeacherScheduleParserRegistry {
    private val genericParser = BsuGenericFacultyTeacherScheduleParser()
    private val facultyParsers = mapOf(
        "Экономический факультет" to BsuEconomyFacultyTeacherScheduleParser(),
        "ФПМИ" to BsuFpmiFacultyTeacherScheduleParser(),
        "Механико-математический факультет" to BsuMmfFacultyTeacherScheduleParser(),
        "Географический факультет" to BsuGeographyFacultyTeacherScheduleParser(),
        "ФМО" to BsuFirFacultyTeacherScheduleParser(),
        "ФСК" to BsuFscFacultyTeacherScheduleParser(),
        "Биологический факультет" to BsuBiologyFacultyTeacherScheduleParser(),
        "Институт бизнеса" to BsuBusinessInstituteTeacherScheduleParser(),
        "Исторический факультет" to BsuHistoryFacultyTeacherScheduleParser(),
        "Юридический факультет" to BsuLawFacultyTeacherScheduleParser(),
        "Химический факультет" to BsuChemistryFacultyTeacherScheduleParser(),
        "Физический факультет" to BsuPhysicsFacultyTeacherScheduleParser(),
        "Факультет журналистики" to BsuJournalismFacultyTeacherScheduleParser(),
        "Филологический факультет" to BsuPhilologyFacultyTeacherScheduleParser(),
        "Факультет философии и социальных наук" to BsuFfsnFacultyTeacherScheduleParser(),
        "Факультет радиофизики и компьютерных технологий" to BsuRctFacultyTeacherScheduleParser(),
    )

    val registeredFacultyNames: Set<String> get() = facultyParsers.keys

    fun parserFor(facultyName: String?): BsuFacultyTeacherScheduleParser? {
        val name = facultyName?.trim()?.takeIf(String::isNotBlank) ?: return null
        return facultyParsers[name] ?: genericParser
    }
}

private fun parseFacultyTeacherSchedule(
    profile: ScheduleProfile,
    html: String,
    referenceDate: LocalDate,
    scheduleParser: BsuScheduleHtmlParser,
    genericParser: GenericHtmlScheduleParser,
    preferGeneric: Boolean,
): List<Lesson> {
    val rawSourceKey = profile.sourceKey?.takeIf(String::isNotBlank)
    val teacherName = BsuTeacherProfileSourceKey.teacherName(rawSourceKey)
        ?: rawSourceKey?.takeIf { it !in BsuFacultyTeacherScheduleParserRegistry.registeredFacultyNames }
        ?: profile.name

    fun matching(lessons: List<Lesson>): List<Lesson> = lessons
        .filter { BsuTeacherNameMatcher.matches(it.teacherName, teacherName) }
        .distinctBy(Lesson::sourceKey)
        .sortedWith(compareBy({ it.lessonDate }, { it.startTime }, { it.slotNumber }, { it.id }))

    fun genericLessons(): List<Lesson> = runCatching {
        genericParser.parse(profile, html, referenceDate)
    }.getOrNull()?.let(::matching).orEmpty()

    fun matrixLessons(): List<Lesson> = Ksoup.parse(html).select("table").toList().flatMapIndexed { tableIndex, table ->
        val tableHtml = table.outerHtml()
        scheduleParser.discoverTableGroupLabels(tableHtml).flatMap { group ->
            val groupProfile = profile.copy(
                type = ScheduleProfileType.GROUP,
                name = group,
                sourceKey = group,
            )
            runCatching { scheduleParser.parse(groupProfile, tableHtml, referenceDate) }
                .getOrNull()
                .orEmpty()
                .map { lesson ->
                    val scope = "|faculty-table:$tableIndex|group:${BsuTeacherNameMatcher.normalize(group)}"
                    lesson.copy(id = "web:${lesson.sourceKey}$scope", sourceKey = lesson.sourceKey + scope)
                }
        }
    }.let(::matching)

    fun documentRowLessons(): List<Lesson> = runCatching {
        scheduleParser.parseDayFirstDocumentTables(profile, html, referenceDate)
    }.getOrNull()?.let(::matching).orEmpty()

    fun numberedLessons(): List<Lesson> = runCatching {
        scheduleParser.parseAllNumberedGroupBlocks(profile, html, referenceDate)
    }.getOrNull()?.let(::matching).orEmpty()

    val orderedAttempts = if (preferGeneric) {
        listOf(::genericLessons, ::documentRowLessons, ::matrixLessons, ::numberedLessons)
    } else {
        listOf(::matrixLessons, ::numberedLessons, ::documentRowLessons, ::genericLessons)
    }
    orderedAttempts.forEach { attempt ->
        attempt().takeIf { lessons -> lessons.isNotEmpty() }?.let { return it }
    }
    error("В расписании не найдены занятия преподавателя «$teacherName». Проверьте источник факультета")
}
