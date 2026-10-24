package com.example.bruschedule.data.remote

object GoogleSheetsScheduleUrl {
    private val SHEET_URL = Regex(
        "^https://docs\\.google\\.com/spreadsheets/d/(e/)?([^/?#]+)(?:/([^?#]*))?(?:\\?([^#]*))?(?:#(.*))?$",
        RegexOption.IGNORE_CASE,
    )
    private val GID = Regex("(?:^|[&#])gid=([0-9]+)(?:$|[&#])", RegexOption.IGNORE_CASE)

    fun toHtmlTableUrl(url: String): String? {
        val match = SHEET_URL.matchEntire(url.trim()) ?: return null
        val publishedPrefix = match.groupValues[1]
        val spreadsheetId = match.groupValues[2]
        val query = match.groupValues[4]
        val fragment = match.groupValues[5]
        val gid = sequenceOf(query, fragment)
            .mapNotNull { GID.find(it)?.groupValues?.getOrNull(1) }
            .firstOrNull()

        return buildString {
            append("https://docs.google.com/spreadsheets/d/")
            append(publishedPrefix)
            append(spreadsheetId)
            append("/gviz/tq?tqx=out:html")
            if (gid != null) append("&gid=").append(gid)
        }
    }
}
