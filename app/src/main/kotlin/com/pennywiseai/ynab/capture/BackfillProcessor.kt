package com.pennywiseai.ynab.capture

import com.pennywiseai.parser.core.ParsedTransaction
import com.pennywiseai.ynab.capture.notify.BackfillSummary
import com.pennywiseai.ynab.data.local.MessageStatus
import com.pennywiseai.ynab.data.local.dao.MappingRuleDao
import com.pennywiseai.ynab.data.local.dao.ProcessedMessageDao
import com.pennywiseai.ynab.data.local.entity.ProcessedMessageEntity
import com.pennywiseai.ynab.data.mapper.WILDCARD_LAST4
import com.pennywiseai.ynab.data.state.PostingStateStore
import com.pennywiseai.ynab.pipeline.Classification
import com.pennywiseai.ynab.pipeline.PostOutcome
import com.pennywiseai.ynab.pipeline.TransactionPipeline
import com.pennywiseai.ynab.pipeline.TransactionPoster
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The backfill engine (ADR-0004). Classifies a whole batch with the SHARED classify()
 * seam (so it agrees with real-time on what's postable), records the terminal outcomes,
 * then groups postables by budget and bulk-POSTs in chunks. A bulk POST is atomic, so a
 * chunk 400 (a genuine bad element) falls back to individual POSTs — good rows POSTED,
 * only the bad row(s) FAILED. Duplicates are POSTED (not errors). Idempotent via import_id.
 *
 * Classification runs against a rules SNAPSHOT taken once at run start (battery: an
 * N-message backfill must not re-read the rules table N times).
 */
@Singleton
class BackfillProcessor @Inject constructor(
    private val pipeline: TransactionPipeline,
    private val poster: TransactionPoster,
    private val processedMessageDao: ProcessedMessageDao,
    private val mappingRuleDao: MappingRuleDao,
    private val postingState: PostingStateStore,
) {

    private class Tally {
        var posted = 0
        var skipped = 0
        var failed = 0
        fun toSummary() = BackfillSummary(posted, skipped, failed)
    }

    suspend fun run(
        messages: List<RawSms>,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): BackfillSummary {
        val tally = Tally()
        val total = messages.size
        val postables = mutableListOf<Classification.Postable>()

        // One rules snapshot for the whole batch: the table is read once per run, not once
        // per message. Phase 2's setBroken() can't stale it — all classification is done by then.
        val rules = pipeline.currentRules()

        // Phase 1: classify + record terminal outcomes locally (no network).
        for ((index, message) in messages.withIndex()) {
            if (isCancelled()) return tally.toSummary()
            when (val c = pipeline.classify(message.body, message.sender, message.timestamp, rules)) {
                is Classification.Dropped -> {} // never logged
                is Classification.Skipped -> { record(c.parsed, c.importId, c.status); tally.skipped++ }
                is Classification.AlreadyPosted -> tally.posted++ // already POSTED; row stands
                is Classification.Paused -> {
                    record(c.parsed, c.importId, MessageStatus.FAILED, c.error); tally.failed++
                }
                is Classification.Postable -> postables += c
            }
            onProgress(index + 1, total)
        }

        // Phase 2: group by budget, bulk-POST in chunks.
        for ((budgetId, group) in postables.groupBy { it.rule.budgetId }) {
            for (chunk in group.chunked(CHUNK_SIZE)) {
                if (isCancelled()) return tally.toSummary()
                postChunk(budgetId, chunk, tally)
            }
        }
        return tally.toSummary()
    }

    private suspend fun postChunk(budgetId: String, chunk: List<Classification.Postable>, tally: Tally) {
        when (val outcome = poster.post(budgetId, chunk.map { it.transaction })) {
            is PostOutcome.Posted -> {
                chunk.forEach { record(it.parsed, it.importId, MessageStatus.POSTED) }
                tally.posted += chunk.size
            }
            is PostOutcome.Unauthorized -> {
                postingState.setPaused(true)
                chunk.forEach { record(it.parsed, it.importId, MessageStatus.FAILED, TransactionPipeline.ERROR_TOKEN_INVALID) }
                tally.failed += chunk.size
            }
            is PostOutcome.RouteBroken -> {
                chunk.map { it.rule }.distinctBy { it.bankName to it.last4 }
                    .forEach { mappingRuleDao.setBroken(it.bankName, it.last4 ?: WILDCARD_LAST4, true) }
                chunk.forEach { record(it.parsed, it.importId, MessageStatus.FAILED, TransactionPipeline.ERROR_ROUTE_BROKEN) }
                tally.failed += chunk.size
            }
            is PostOutcome.Failed -> {
                if (outcome.retryable) {
                    chunk.forEach { record(it.parsed, it.importId, MessageStatus.FAILED, outcome.error) }
                    tally.failed += chunk.size
                } else if (chunk.size == 1) {
                    // A single element that 400s is genuinely bad — record its error, no further split.
                    record(chunk.single().parsed, chunk.single().importId, MessageStatus.FAILED, outcome.error)
                    tally.failed++
                } else {
                    // Atomic chunk 400: isolate by re-posting each element on its own.
                    chunk.forEach { postChunk(budgetId, listOf(it), tally) }
                }
            }
        }
    }

    private suspend fun record(
        parsed: ParsedTransaction,
        importId: String,
        status: MessageStatus,
        error: String? = null,
    ) {
        processedMessageDao.upsert(
            ProcessedMessageEntity(
                importId = importId,
                sender = parsed.sender,
                bankName = parsed.bankName,
                last4 = parsed.accountLast4,
                amount = parsed.amount,
                currency = parsed.currency,
                status = status,
                error = error,
                timestamp = parsed.timestamp,
            ),
        )
    }

    companion object {
        /** ≤ 100 transactions per bulk POST (ADR-0004) — a 600-msg backfill = a handful of requests. */
        const val CHUNK_SIZE = 100
    }
}
