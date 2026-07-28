package com.pennywiseai.ynab.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * DTOs for `GET /budgets/{id}/accounts`. `closed`/`deleted` mirror YNAB and are
 * carried into the snapshot so the picker can filter them out (Plan 2's
 * getOpenAccounts). Balance, type, etc. are unmodeled and ignored.
 */
@Serializable
data class AccountsResponse(val data: AccountsData)

@Serializable
data class AccountsData(val accounts: List<AccountDto>)

@Serializable
data class AccountDto(
    val id: String,
    val name: String,
    val closed: Boolean,
    val deleted: Boolean,
)
