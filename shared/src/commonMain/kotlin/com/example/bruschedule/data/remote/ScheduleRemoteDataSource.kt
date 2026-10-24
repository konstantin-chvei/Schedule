package com.example.bruschedule.data.remote

import com.example.bruschedule.data.scraper.BsuBiologyScheduleHtmlParser
import kotlinx.coroutines.CancellationException

interface HtmlTransport {
    suspend fun get(url: String): String
}

suspend fun HtmlTransport.primeBiologySession() {
    try {
        get(BsuBiologyScheduleHtmlParser.BIOLOGY_SCHEDULE_URL)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
    }
}

interface BinaryResourceTransport {
    suspend fun getBytes(url: String): ByteArray
}

expect fun createPlatformHtmlTransport(): HtmlTransport
