package com.example.bruschedule.domain.repository

import kotlinx.coroutines.flow.Flow

interface DailyMessagesRepository {
    fun observeMessages(): Flow<List<String>>
    suspend fun refresh()
}

interface DailyMessagesRemoteDataSource {
    suspend fun fetchMessages(): List<String>
}
