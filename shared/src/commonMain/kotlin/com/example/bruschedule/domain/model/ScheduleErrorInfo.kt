package com.example.bruschedule.domain.model

data class ScheduleErrorInfo(
    val message: String,
    val code: String,
) {
    fun encodeForStorage(): String = "$STORAGE_PREFIX$code|$message"

    companion object {
        private const val STORAGE_PREFIX = "@BRU_ERROR_V1:"
        private val HTTP_STATUS = Regex("(?:кодом\\s*|HTTP(?:/\\d(?:\\.\\d)?)?\\s*)(\\d{3})", RegexOption.IGNORE_CASE)
        private val DARWIN_TLS_STATUS = Regex("nsurlerrordomain.*code\\s*=\\s*-120[0-6]\\b")

        fun fromThrowable(failure: Throwable, operation: String = "SYNC"): ScheduleErrorInfo {
            val messages = mutableListOf<String>()
            var cause: Throwable? = failure
            repeat(5) {
                val current = cause ?: return@repeat
                val currentMessage = current.message?.takeIf(String::isNotBlank)
                currentMessage?.take(MAX_CAUSE_MESSAGE_LENGTH)?.let(messages::add)
                if (currentMessage == null) {
                    current.toString().take(MAX_CAUSE_MESSAGE_LENGTH).takeIf(String::isNotBlank)?.let(messages::add)
                }
                cause = current.cause
            }
            return fromDiagnostic(messages.joinToString(" | ").ifBlank { "unknown failure" }, operation)
        }

        fun decodeStored(value: String): ScheduleErrorInfo {
            if (value.startsWith(STORAGE_PREFIX)) {
                val payload = value.removePrefix(STORAGE_PREFIX)
                val separator = payload.indexOf('|')
                if (separator in 1 until payload.lastIndex) {
                    val code = payload.substring(0, separator)
                    val message = payload.substring(separator + 1).take(MAX_MESSAGE_LENGTH)
                    if (code.length <= MAX_CODE_LENGTH && code.all(::isCodeCharacter) && message.isNotBlank()) {
                        return ScheduleErrorInfo(message, code)
                    }
                }
            }
            return fromDiagnostic(value, "SYNC")
        }

        fun fromDiagnostic(diagnostic: String, operation: String = "SYNC"): ScheduleErrorInfo {
            val source = diagnostic.take(MAX_DIAGNOSTIC_LENGTH).ifBlank { "unknown failure" }
            val normalized = source.lowercase()
            val status = HTTP_STATUS.find(source)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val certificateFailure = DARWIN_TLS_STATUS.containsMatchIn(normalized) || listOf(
                "certificate", "certpath", "sslpeertrust", "sslpeerunverified", "сертификат",
                "trust evaluation", "server identity could not be verified", "secure connection could not be established",
            ).any(normalized::contains)
            val networkFailure = listOf(
                "timeout", "timed out", "timedout", "unknownhost", "unable to resolve", "could not resolve host",
                "failed host lookup", "failed to connect", "connection refused", "connection reset", "no route to host",
                "network is unreachable", "internet connection appears", "no internet connection", "socketexception",
                "sslhandshakeexception", "nsurlerrordomain", "darwinhttprequestexception", "dns",
                "ошибка сети", "сеть недоступна", "нет подключения", "нет сети",
                "ответ отсутствует", "не удалось открыть каталог", "не удалось загрузить каталог",
                "не удалось загрузить расписание биофака",
            ).any(normalized::contains)
            val category: String
            val message: String

            when {
                status != null -> {
                    category = "HTTP$status"
                    message = when (status) {
                        404 -> "Расписание не найдено. Возможно, оно было удалено или изменено."
                        406 -> "Не удалось загрузить расписание. Попробуйте позже."
                        429 -> "Слишком много запросов. Попробуйте позже."
                        in 500..599 -> "Сервис расписаний временно недоступен. Попробуйте позже."
                        else -> "Не удалось загрузить расписание. Попробуйте позже."
                    }
                }
                certificateFailure -> {
                    category = "TLS"
                    message = "Не удалось безопасно подключиться. Попробуйте позже; локальные данные не изменены."
                }
                networkFailure -> {
                    category = if (listOf("timeout", "timed out", "timedout").any(normalized::contains)) "TIMEOUT" else "NETWORK"
                    message = if (category == "TIMEOUT") {
                        "Сервер не ответил вовремя. Проверьте подключение и попробуйте ещё раз."
                    } else {
                        "Не удалось связаться с сервером. Проверьте подключение к интернету."
                    }
                }
                listOf("профиль расписания не найден", "не найден в каталоге", "не найдена в каталогах", "страница расписания не найдена", "преподаватель", "группа").any(normalized::contains) &&
                    listOf("не найден", "не найдена", "не найдено", "не существует", "удален", "удалён").any(normalized::contains) -> {
                    category = "SOURCE"
                    message = "Расписание больше недоступно. Выберите другое расписание."
                }
                listOf(".schedule-table", "пустой html", "пустое расписание", "не найдено ни одной пары", "изменил разметку", "не удалось развернуть пары").any(normalized::contains) -> {
                    category = "PARSE"
                    message = "Не удалось показать расписание. Попробуйте обновить его позже."
                }
                listOf("sqlite", "database", "constraint", "sql exception", "локальн", "хранилищ").any(normalized::contains) -> {
                    category = "LOCAL"
                    message = "Не удалось сохранить или прочитать данные на устройстве. Повторите попытку."
                }
                operation == "SEARCH" -> {
                    category = "SEARCH"
                    message = "Поиск расписаний сейчас недоступен. Проверьте подключение и повторите попытку."
                }
                operation == "TASK" -> {
                    category = "TASK"
                    message = "Не удалось сохранить изменения дедлайна. Проверьте данные и повторите попытку."
                }
                operation == "PROFILE" -> {
                    category = "PROFILE"
                    message = "Не удалось сохранить расписание на устройстве. Повторите попытку."
                }
                operation == "DATABASE" -> {
                    category = "LOCAL"
                    message = "Не удалось прочитать или сохранить данные на устройстве. Повторите попытку."
                }
                operation == "PREFERENCES" -> {
                    category = "SETTINGS"
                    message = "Не удалось сохранить настройки. Повторите попытку."
                }
                else -> {
                    category = "UNKNOWN"
                    message = "Не удалось выполнить операцию. Сохранённые данные не изменены."
                }
            }

            val operationCode = operation.uppercase()
                .filter { it in 'A'..'Z' || it in '0'..'9' }
                .take(8)
                .ifBlank { "APP" }
            val fingerprint = source.hashCode().toUInt().toString(16).uppercase().padStart(8, '0')
            val code = "$operationCode-$category-$fingerprint".take(MAX_CODE_LENGTH)
            return ScheduleErrorInfo(message, code)
        }

        private fun isCodeCharacter(character: Char): Boolean =
            character in 'A'..'Z' || character in '0'..'9' || character == '-'

        private const val MAX_MESSAGE_LENGTH = 180
        private const val MAX_CAUSE_MESSAGE_LENGTH = 512
        private const val MAX_CODE_LENGTH = 40
        private const val MAX_DIAGNOSTIC_LENGTH = 2_048
    }
}
