package com.example.bruschedule.domain.model

enum class UniversitySourceType {
    BRU_CATALOG,
    BSU_HTML_AND_SHEETS,
    DIRECT_HTML,
}

data class University(
    val id: String,
    val name: String,
    val websiteUrl: String,
    val sourceType: UniversitySourceType,
    val isBuiltIn: Boolean = false,
)

object UniversityIds {
    const val BRU = "bru"
    const val BSU = "bsu"
}

object DefaultUniversities {
    val BRU = University(
        id = UniversityIds.BRU,
        name = "Белорусско-Российский университет",
        websiteUrl = "https://rasp.bru.by",
        sourceType = UniversitySourceType.BRU_CATALOG,
        isBuiltIn = true,
    )

    val BSU = University(
        id = UniversityIds.BSU,
        name = "Белорусский государственный университет",
        websiteUrl = "https://bsu.by",
        sourceType = UniversitySourceType.BSU_HTML_AND_SHEETS,
        isBuiltIn = true,
    )
}
