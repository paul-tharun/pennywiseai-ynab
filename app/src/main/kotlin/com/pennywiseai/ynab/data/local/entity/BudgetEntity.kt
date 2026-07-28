package com.pennywiseai.ynab.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A YNAB budget in the local snapshot. `id` is the YNAB budget id; currencyCode is its currency_format.iso_code. */
@Entity(tableName = "budgets")
data class BudgetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val currencyCode: String,
)
