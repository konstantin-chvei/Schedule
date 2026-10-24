package com.example.bruschedule.data.remote

internal object ScheduleCsvTable {
    fun parse(content: String): List<List<String>> {
        if (content.isBlank()) return emptyList()
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0

        fun finishField() {
            row += field.toString()
            field.clear()
        }

        fun finishRow() {
            finishField()
            if (row.any(String::isNotBlank)) rows += row.toList()
            row.clear()
        }

        while (index < content.length) {
            val char = content[index]
            when {
                char == '"' && quoted && content.getOrNull(index + 1) == '"' -> {
                    field.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> finishField()
                (char == '\n' || char == '\r') && !quoted -> {
                    finishRow()
                    if (char == '\r' && content.getOrNull(index + 1) == '\n') index++
                }
                else -> field.append(char)
            }
            index++
        }
        require(!quoted) { "CSV завершился внутри кавычек" }
        if (field.isNotEmpty() || row.isNotEmpty()) finishRow()

        return rows.mapIndexed { rowIndex, values ->
            if (rowIndex == 0) values.map { it.removePrefix("\uFEFF").trim() } else values.map(String::trim)
        }
    }
}
