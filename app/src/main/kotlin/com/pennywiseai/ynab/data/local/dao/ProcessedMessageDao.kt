package com.pennywiseai.ynab.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.pennywiseai.ynab.data.local.MessageStatus
import com.pennywiseai.ynab.data.local.UnroutedSuggestion
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

    /**
     * Distinct (bankName, last4) combos from unrouted log rows that no rule would
     * route. A rule covers a row when it is for the same bank AND its last4 either
     * equals the row's last4 or is the wildcard "". A null message last4 can only
     * be covered by the wildcard (the `r.last4 = p.last4` comparison is NULL/unknown
     * for it, so only `r.last4 = ''` matches). Callers pass MessageStatus.SKIPPED_UNROUTED.
     */
    @Query(
        """
        SELECT DISTINCT p.bankName AS bankName, p.last4 AS last4
        FROM processed_messages p
        WHERE p.status = :status
          AND NOT EXISTS (
            SELECT 1 FROM mapping_rules r
            WHERE r.bankName = p.bankName
              AND (r.last4 = p.last4 OR r.last4 = '')
          )
        ORDER BY p.bankName, p.last4
        """,
    )
    suspend fun getUnroutedSuggestions(status: MessageStatus): List<UnroutedSuggestion>
}
