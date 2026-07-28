package com.pennywiseai.ynab.data.remote

import com.pennywiseai.ynab.data.local.entity.AccountEntity
import com.pennywiseai.ynab.data.local.entity.BudgetEntity
import com.pennywiseai.ynab.data.remote.dto.AccountDto
import com.pennywiseai.ynab.data.remote.dto.BudgetDto

/**
 * A budget maps to a snapshot row only if it carries a currency: the currency is
 * required for the offline mismatch guard and to store on rules, so a
 * currency-less budget is unroutable and dropped (returns null).
 */
fun BudgetDto.toEntity(): BudgetEntity? = currencyFormat?.let {
    BudgetEntity(id = id, name = name, currencyCode = it.isoCode)
}

fun AccountDto.toEntity(budgetId: String): AccountEntity = AccountEntity(
    id = id,
    budgetId = budgetId,
    name = name,
    closed = closed,
    deleted = deleted,
)
