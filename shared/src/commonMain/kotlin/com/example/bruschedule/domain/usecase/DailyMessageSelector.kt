package com.example.bruschedule.domain.usecase

import com.example.bruschedule.domain.model.DEFAULT_DAILY_MESSAGES
import kotlinx.datetime.LocalDate

object DailyMessageSelector {
    fun forDate(date: LocalDate, messages: List<String>): String {
        val usable = messages.map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { DEFAULT_DAILY_MESSAGES }
        val index = (date.dayOfYear - 1) % usable.size
        return usable[index]
    }
}
