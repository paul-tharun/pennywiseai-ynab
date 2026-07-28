package com.pennywiseai.ynab.data.mapper

import com.pennywiseai.ynab.core.model.MappingRule
import com.pennywiseai.ynab.data.local.entity.MappingRuleEntity

/** Storage sentinel for the bank wildcard (domain uses last4 == null). */
const val WILDCARD_LAST4 = ""

fun MappingRuleEntity.toDomain(): MappingRule = MappingRule(
    bankName = bankName,
    last4 = last4.ifEmpty { null },
    budgetId = budgetId,
    accountId = accountId,
    currencyCode = currencyCode,
    broken = broken,
)

fun MappingRule.toEntity(id: Long = 0): MappingRuleEntity = MappingRuleEntity(
    id = id,
    bankName = bankName,
    last4 = last4 ?: WILDCARD_LAST4,
    budgetId = budgetId,
    accountId = accountId,
    currencyCode = currencyCode,
    broken = broken,
)
