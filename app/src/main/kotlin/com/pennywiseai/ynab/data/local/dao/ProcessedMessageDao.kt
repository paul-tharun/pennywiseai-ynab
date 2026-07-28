package com.pennywiseai.ynab.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.pennywiseai.ynab.data.local.MessageStatus
import com.pennywiseai.ynab.data.local.entity.ProcessedMessageEntity

@Dao
interface ProcessedMessageDao {

    /**
     * Upsert by import_id. A re-processed message (e.g. SKIPPED_UNROUTED -> POSTED
     * after a route is added) overwrites its existing row on the same PK (ADR-0005).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: ProcessedMessageEntity)

    @Query("SELECT * FROM processed_messages ORDER BY timestamp DESC")
    suspend fun getAll(): List<ProcessedMessageEntity>

    @Query("SELECT * FROM processed_messages WHERE status = :status ORDER BY timestamp DESC")
    suspend fun getByStatus(status: MessageStatus): List<ProcessedMessageEntity>

    @Query("SELECT * FROM processed_messages WHERE importId = :importId")
    suspend fun getByImportId(importId: String): ProcessedMessageEntity?
}
