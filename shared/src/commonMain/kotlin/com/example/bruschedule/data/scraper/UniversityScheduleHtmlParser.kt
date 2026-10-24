package com.example.bruschedule.data.scraper

import com.example.bruschedule.domain.model.ScheduleProfile
import com.example.bruschedule.domain.model.UniversityIds
import com.example.bruschedule.domain.repository.ScheduleHtmlParser
import kotlinx.datetime.LocalDate

class UniversityScheduleHtmlParser(
    private val bruParser: ScheduleHtmlParser = BruScheduleScraper(),
    private val bsuParser: ScheduleHtmlParser = BsuScheduleHtmlParser(),
    private val genericParser: ScheduleHtmlParser = GenericHtmlScheduleParser(),
    private val githubCsvParser: ScheduleHtmlParser = GitHubCsvScheduleParser(),
) : ScheduleHtmlParser {
    override val formatVersion: Int = DISPATCHER_FORMAT_VERSION

    override fun parse(profile: ScheduleProfile, html: String, referenceDate: LocalDate) =
        if (profile.sourceUrl?.endsWith(".csv", ignoreCase = true) == true) {
            githubCsvParser.parse(profile, html, referenceDate)
        } else {
            parserFor(profile).parse(profile, html, referenceDate)
        }

    override fun hasAlternatingTwoWeekCycle(html: String): Boolean =
        bruParser.hasAlternatingTwoWeekCycle(html) || genericParser.hasAlternatingTwoWeekCycle(html)

    override fun hasAlternatingTwoWeekCycle(profile: ScheduleProfile, html: String): Boolean =
        if (profile.sourceUrl?.endsWith(".csv", ignoreCase = true) == true) false
        else parserFor(profile).hasAlternatingTwoWeekCycle(html)

    override fun hasWeeklyScheduleTemplate(profile: ScheduleProfile, html: String): Boolean =
        if (profile.sourceUrl?.endsWith(".csv", ignoreCase = true) == true) false
        else parserFor(profile).hasWeeklyScheduleTemplate(profile, html)

    private fun parserFor(profile: ScheduleProfile): ScheduleHtmlParser = when (profile.universityId) {
        UniversityIds.BRU -> bruParser
        UniversityIds.BSU -> bsuParser
        else -> genericParser
    }

    private companion object {
        const val DISPATCHER_FORMAT_VERSION = 9
    }
}
