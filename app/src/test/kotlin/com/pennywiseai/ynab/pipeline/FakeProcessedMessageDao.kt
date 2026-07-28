package com.pennywiseai.ynab.pipeline

import com.pennywiseai.ynab.data.local.MessageStatus
import com.pennywiseai.ynab.data.local.UnroutedSuggestion
import com.pennywiseai.ynab.data.local.dao.ProcessedMessageDao
import com.pennywiseai.ynab.data.local.entity.ProcessedMessageEntity

/** In-memory ProcessedMessageDao — upsert by importId, newest-first reads. */
class FakeProcessedMessageDao : ProcessedMessageDao {
    val rows = LinkedHashMap<String, ProcessedMessageEntity>()

    override suspend fun upsert(message: ProcessedMessageEntity) {
        rows[message.importId] = message
    }

    override suspend fun getAll(): List<ProcessedMessageEntity> =
        rows.values.sortedByDescending { it.timestamp }

    override suspend fun getByStatus(status: MessageStatus): List<ProcessedMessageEntity> =
        getAll().filter { it.status == status }

    override suspend fun getByImportId(importId: String): ProcessedMessageEntity? = rows[importId]

    override suspend fun getUnroutedSuggestions(status: MessageStatus): List<UnroutedSuggestion> =
        getByStatus(status).map { UnroutedSuggestion(it.bankName, it.last4) }.distinct()
}
