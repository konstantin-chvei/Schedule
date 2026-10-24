package com.example.bruschedule.data.settings

import com.example.bruschedule.domain.repository.ParserVersionRepository
import com.russhwolf.settings.Settings

class KmpSettingsParserVersionFlags(
    private val settings: Settings,
) : ParserVersionRepository {
    override fun parserVersion(profileId: String): Int =
        settings.getInt("parser.version.$profileId", 0)

    override fun markParserVersion(profileId: String, version: Int) {
        settings.putInt("parser.version.$profileId", version)
    }

    override fun databaseMarker(): Int = settings.getInt(DB_MARKER_KEY, 0)

    override fun setDatabaseMarker(version: Int) {
        settings.putInt(DB_MARKER_KEY, version)
    }

    private companion object {
        const val DB_MARKER_KEY = "schedule.database.marker"
    }
}
