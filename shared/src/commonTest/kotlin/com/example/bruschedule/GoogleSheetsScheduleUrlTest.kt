package com.example.bruschedule

import com.example.bruschedule.data.remote.GoogleSheetsScheduleUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GoogleSheetsScheduleUrlTest {
    @Test
    fun convertsEditableSheetLinkAndPreservesWorksheetId() {
        val url = GoogleSheetsScheduleUrl.toHtmlTableUrl(
            "https://docs.google.com/spreadsheets/d/1qVNep2w2koRmEC_AKu4fVrXDNLUPoZij/edit?usp=sharing#gid=795046734",
        )

        assertEquals(
            "https://docs.google.com/spreadsheets/d/1qVNep2w2koRmEC_AKu4fVrXDNLUPoZij/gviz/tq?tqx=out:html&gid=795046734",
            url,
        )
    }

    @Test
    fun convertsPublishedSheetLinksAndReadsGidFromQuery() {
        val url = GoogleSheetsScheduleUrl.toHtmlTableUrl(
            "https://docs.google.com/spreadsheets/d/e/2PACX-example/pubhtml?gid=12345&single=true",
        )

        assertEquals(
            "https://docs.google.com/spreadsheets/d/e/2PACX-example/gviz/tq?tqx=out:html&gid=12345",
            url,
        )
    }

    @Test
    fun usesFirstWorksheetWhenShareLinkDoesNotSpecifyGid() {
        val url = GoogleSheetsScheduleUrl.toHtmlTableUrl(
            "https://docs.google.com/spreadsheets/d/1U5RoI4zetBPjz-klxfYlxbJM3oaUIykf/edit?usp=drive_link",
        )

        assertEquals(
            "https://docs.google.com/spreadsheets/d/1U5RoI4zetBPjz-klxfYlxbJM3oaUIykf/gviz/tq?tqx=out:html",
            url,
        )
    }

    @Test
    fun leavesRegularWebsiteUrlsToTheirExistingTransportPath() {
        assertNull(GoogleSheetsScheduleUrl.toHtmlTableUrl("https://mmf.bsu.by/ru/raspisanie-zanyatij/"))
    }
}
