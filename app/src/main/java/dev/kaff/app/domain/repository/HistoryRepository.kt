package dev.kaff.app.domain.repository

import dev.kaff.app.domain.model.TransferRecord
import kotlinx.coroutines.flow.Flow

interface HistoryRepository {
    val records: Flow<List<TransferRecord>>
    suspend fun add(record: TransferRecord)
    suspend fun clear()
}
