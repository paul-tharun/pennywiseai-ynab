package com.pennywiseai.ynab.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A persisted route. `last4` is NON-null here: the empty string "" encodes the
 * bank wildcard. This matters for the UNIQUE(bankName, last4) index — SQLite
 * treats NULLs as DISTINCT, so a nullable unique index would still admit two
 * wildcard rules for one bank. Encoding the wildcard as "" makes any duplicate
 * (bankName, last4) pair — exact OR wildcard — unrepresentable (Plan 1
 * carry-forward). Convert to/from the domain MappingRule (wildcard == null) via
 * toDomain()/toEntity() in data/mapper.
 */
@Entity(
    tableName = "mapping_rules",
    indices = [Index(value = ["bankName", "last4"], unique = true)],
)
data class MappingRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bankName: String,
    val last4: String,
    val budgetId: String,
    val accountId: String,
    val currencyCode: String,
)
