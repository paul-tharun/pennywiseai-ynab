package com.pennywiseai.ynab.pipeline

import com.pennywiseai.parser.core.ParsedTransaction
import com.pennywiseai.ynab.core.model.MappingRule
import com.pennywiseai.ynab.core.model.SaveTransaction
import com.pennywiseai.ynab.data.local.MessageStatus

/**
 * The outcome of the shared parse→route decision (ADR-0003's "parse→route mapping
 * function"), before any POST. TransactionPipeline.process() (real-time, single POST)
 * and BackfillProcessor (bulk POST) both branch on this so they can never disagree on
 * which SMS is postable. `classify` carries the parsed message + its import_id on every
 * non-Dropped variant so the caller can record the log row itself (skips/pause) or POST
 * (Postable). It writes no log row — recording is the caller's job.
 */
sealed interface Classification {
    /** Parser returned null — no import_id exists; drop silently, never log. */
    data object Dropped : Classification

    /** A SKIPPED_* outcome (non-transaction / unrouted-or-broken / currency mismatch). */
    data class Skipped(
        val status: MessageStatus,
        val parsed: ParsedTransaction,
        val importId: String,
    ) : Classification

    /** Local log already has this import_id POSTED (best-effort dedup; ADR-0005). */
    data class AlreadyPosted(
        val parsed: ParsedTransaction,
        val importId: String,
    ) : Classification

    /** Posting is paused / no token — record FAILED without touching the network. */
    data class Paused(
        val parsed: ParsedTransaction,
        val importId: String,
        val error: String,
    ) : Classification

    /** Ready to POST: the mapped transaction + the resolved (non-broken) rule. */
    data class Postable(
        val parsed: ParsedTransaction,
        val importId: String,
        val rule: MappingRule,
        val transaction: SaveTransaction,
    ) : Classification
}
