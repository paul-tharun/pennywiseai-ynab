package com.pennywiseai.ynab.pipeline

import com.pennywiseai.ynab.core.model.SaveTransaction

/** Configurable TransactionPoster double that records whether/how it was called. */
class FakeTransactionPoster(var outcome: PostOutcome = PostOutcome.Posted) : TransactionPoster {
    var calls = 0
    var lastBudgetId: String? = null
    var lastTransactions: List<SaveTransaction> = emptyList()

    override suspend fun post(budgetId: String, transactions: List<SaveTransaction>): PostOutcome {
        calls++
        lastBudgetId = budgetId
        lastTransactions = transactions
        return outcome
    }
}
