package com.pennywiseai.ynab.pipeline

import com.pennywiseai.ynab.core.model.SaveTransaction

/** Configurable TransactionPoster double. Set `outcome` for a fixed reply, or `responder`
 *  to vary the reply per call (budgetId, transactions) — the backfill needs both. */
class FakeTransactionPoster(var outcome: PostOutcome = PostOutcome.Posted) : TransactionPoster {
    var calls = 0
    var lastBudgetId: String? = null
    var lastTransactions: List<SaveTransaction> = emptyList()
    val allCalls = mutableListOf<Pair<String, List<SaveTransaction>>>()
    var responder: ((String, List<SaveTransaction>) -> PostOutcome)? = null

    override suspend fun post(budgetId: String, transactions: List<SaveTransaction>): PostOutcome {
        calls++
        lastBudgetId = budgetId
        lastTransactions = transactions
        allCalls += budgetId to transactions
        return responder?.invoke(budgetId, transactions) ?: outcome
    }
}
