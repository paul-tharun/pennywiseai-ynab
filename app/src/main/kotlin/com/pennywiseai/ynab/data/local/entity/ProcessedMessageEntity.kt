package com.pennywiseai.ynab.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.pennywiseai.ynab.data.local.MessageStatus
import java.math.BigDecimal

/**
 * One processed message — a single SMS the pipeline handled, keyed by import_id
 * and carrying its terminal status. Stores only display + routing fields
 * (never the full parsed transaction): retroactive posting re-derives from the
 * inbox (design spec, Local persistence). `last4` is nullable because a parsed
 * message may carry no account tail.
 */
@Entity(tableName = "processed_messages")
data class ProcessedMessageEntity(
    @PrimaryKey val importId: String,
    val sender: String,
    val bankName: String,
    val last4: String?,
    val amount: BigDecimal,
    val currency: String,
    val status: MessageStatus,
    val error: String? = null,
    val timestamp: Long,
)
