package com.example.bruschedule.data.local

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.example.bruschedule.data.local.db.ScheduleDatabase
import com.example.bruschedule.domain.model.DefaultUniversities
import com.example.bruschedule.domain.model.University
import com.example.bruschedule.domain.model.UniversitySourceType
import com.example.bruschedule.domain.repository.UniversityRepository
import com.russhwolf.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

class SqlDelightUniversityRepository(
    private val database: ScheduleDatabase,
    private val settings: Settings,
) : UniversityRepository {
    private val queries get() = database.scheduleQueries
    private val mutableSelectedUniversityId = MutableStateFlow(
        settings.getString(SELECTED_UNIVERSITY_KEY, DefaultUniversities.BRU.id),
    )

    init {
        val bru = DefaultUniversities.BRU
        val bsu = DefaultUniversities.BSU
        queries.insertUniversity(bru.id, bru.name, bru.websiteUrl, bru.sourceType.name)
        queries.insertUniversity(bsu.id, bsu.name, bsu.websiteUrl, bsu.sourceType.name)
        if (queries.getUniversity(mutableSelectedUniversityId.value).executeAsOneOrNull() == null) {
            settings.putString(SELECTED_UNIVERSITY_KEY, bru.id)
            mutableSelectedUniversityId.value = bru.id
        }
    }

    override fun observeUniversities(): Flow<List<University>> =
        queries.selectUniversities().asFlow().mapToList(Dispatchers.Default).map { rows ->
            rows.map { row ->
                University(
                    id = row.id,
                    name = row.name,
                    websiteUrl = row.websiteUrl,
                    sourceType = runCatching { UniversitySourceType.valueOf(row.sourceType) }
                        .getOrDefault(UniversitySourceType.DIRECT_HTML),
                    isBuiltIn = row.id == DefaultUniversities.BRU.id || row.id == DefaultUniversities.BSU.id,
                )
            }
        }

    override fun observeSelectedUniversityId(): Flow<String> = mutableSelectedUniversityId.asStateFlow()

    override suspend fun saveUniversity(university: University) {
        require(university.id.isNotBlank()) { "У вуза отсутствует идентификатор" }
        require(university.name.isNotBlank()) { "Укажите название вуза" }
        require(
            university.sourceType !in setOf(
                UniversitySourceType.BRU_CATALOG,
                UniversitySourceType.BSU_HTML_AND_SHEETS,
            ) || university.isBuiltIn,
        ) {
            "Встроенные адаптеры БРУ и БГУ нельзя подключить как пользовательские источники"
        }
        database.transaction {
            queries.insertUniversity(
                university.id,
                university.name.trim(),
                university.websiteUrl.trim(),
                university.sourceType.name,
            )
            queries.updateUniversity(
                university.name.trim(),
                university.websiteUrl.trim(),
                university.sourceType.name,
                university.id,
            )
        }
    }

    override suspend fun selectUniversity(universityId: String) {
        require(queries.getUniversity(universityId).executeAsOneOrNull() != null) {
            "Выбранный вуз не найден"
        }
        settings.putString(SELECTED_UNIVERSITY_KEY, universityId)
        mutableSelectedUniversityId.value = universityId
    }

    private companion object {
        const val SELECTED_UNIVERSITY_KEY = "university.selected_id"
    }
}
