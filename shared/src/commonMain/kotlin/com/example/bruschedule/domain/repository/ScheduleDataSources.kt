package com.example.bruschedule.domain.repository

import com.example.bruschedule.domain.model.Lesson
import com.example.bruschedule.domain.model.ScheduleProfile
import com.example.bruschedule.domain.model.ScheduleWindow
import kotlinx.datetime.LocalDate

interface ScheduleRemoteDataSource {
    suspend fun fetchHtml(profile: ScheduleProfile, window: ScheduleWindow): String
}

interface ScheduleHtmlParser {
    val formatVersion: Int
    fun parse(profile: ScheduleProfile, html: String, referenceDate: LocalDate): List<Lesson>

    fun hasAlternatingTwoWeekCycle(html: String): Boolean = false

    fun hasAlternatingTwoWeekCycle(profile: ScheduleProfile, html: String): Boolean =
        hasAlternatingTwoWeekCycle(html)

    fun hasWeeklyScheduleTemplate(profile: ScheduleProfile, html: String): Boolean = false
}

interface ParserVersionRepository {
    fun parserVersion(profileId: String): Int
    fun markParserVersion(profileId: String, version: Int)
    fun databaseMarker(): Int
    fun setDatabaseMarker(version: Int)
}
