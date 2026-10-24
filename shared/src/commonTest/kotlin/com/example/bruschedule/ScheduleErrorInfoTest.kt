package com.example.bruschedule

import com.example.bruschedule.domain.model.ScheduleErrorInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScheduleErrorInfoTest {
    @Test
    fun httpStatusIsSummarizedWithoutExposingTheRawUrl() {
        val raw = "rasp.bru.by ответил кодом 406 при запросе https://rasp.bru.by/private/path"
        val error = ScheduleErrorInfo.fromThrowable(IllegalStateException(raw))

        assertTrue(error.message.contains("Не удалось загрузить расписание"))
        assertTrue(error.code.contains("HTTP406"))
        assertTrue(error.code.length < 50)
        assertFalse(error.message.contains("private/path"))
        assertFalse(error.encodeForStorage().contains("https://"))
    }

    @Test
    fun savedErrorsRoundTripAndLegacyVerboseErrorsAreSanitized() {
        val current = ScheduleErrorInfo.fromThrowable(IllegalStateException("HTTP 503"))
        assertEquals(current, ScheduleErrorInfo.decodeStored(current.encodeForStorage()))

        val legacy = ScheduleErrorInfo.decodeStored(
            "rasp.bru.by ответил кодом 404 при запросе https://rasp.bru.by/removed.html",
        )
        assertTrue(legacy.message.contains("Расписание не найдено"))
        assertFalse(legacy.message.contains("removed.html"))
        assertTrue(legacy.code.length < 50)
    }

    @Test
    fun invalidTlsCertificatesGetASafeActionableSummary() {
        val error = ScheduleErrorInfo.fromThrowable(
            IllegalStateException(
                "DarwinHttpRequestException: NSURLErrorDomain Code=-1202 " +
                    "The certificate for this server is invalid for https://bsu.bio/schedule.php",
            ),
        )

        assertTrue(error.message.contains("безопасно подключиться"))
        assertTrue(error.message.contains("данные не изменены"))
        assertFalse(error.message.contains("https://"))
        assertTrue(error.code.contains("TLS"))
        assertTrue(error.code.length < 50)
    }

    @Test
    fun bareDarwinCertificateErrorCodeIsClassifiedAsTls() {
        val error = ScheduleErrorInfo.fromThrowable(
            IllegalStateException("Error Domain=NSURLErrorDomain Code=-1202"),
        )

        assertTrue(error.message.contains("безопасно подключиться"))
        assertTrue(error.code.contains("TLS"))
    }

    @Test
    fun networkAndMissingSourceFailuresHaveUsefulShortMessages() {
        val network = ScheduleErrorInfo.fromThrowable(IllegalStateException("ConnectTimeoutException: request timed out"))
        val unavailableCatalog = ScheduleErrorInfo.fromThrowable(
            IllegalStateException("Не удалось найти расписание группы МПР-261. Последняя ошибка: Failed to connect"),
        )
        val missing = ScheduleErrorInfo.fromThrowable(IllegalStateException("Группа МПР-261 не найдена в каталогах"))

        assertTrue(network.message.contains("не ответил вовремя"))
        assertTrue(unavailableCatalog.message.contains("связаться с сервером"))
        assertTrue(missing.message.contains("больше недоступно"))
    }
}
