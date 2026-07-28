package com.pennywiseai.ynab.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A YNAB account in the local snapshot, owned by a budget. The foreign key
 * cascades on delete so clearing budgets clears their accounts in one step
 * (Room enables PRAGMA foreign_keys by default). `closed`/`deleted` mirror YNAB;
 * closed/deleted accounts are filtered out of the picker.
 */
@Entity(
    tableName = "accounts",
    foreignKeys = [
        ForeignKey(
            entity = BudgetEntity::class,
            parentColumns = ["id"],
            childColumns = ["budgetId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("budgetId")],
)
data class AccountEntity(
    @PrimaryKey val id: String,
    val budgetId: String,
    val name: String,
    val closed: Boolean,
    val deleted: Boolean,
)
