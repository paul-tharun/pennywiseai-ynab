package com.pennywiseai.ynab.pipeline

import com.pennywiseai.parser.core.ParsedTransaction
import com.pennywiseai.ynab.core.MappingResolver
import com.pennywiseai.ynab.core.TransactionMapper
import com.pennywiseai.ynab.core.isPostable
import com.pennywiseai.ynab.core.model.MappingRule
import com.pennywiseai.ynab.data.local.MessageStatus
import com.pennywiseai.ynab.data.local.dao.MappingRuleDao
import com.pennywiseai.ynab.data.local.dao.ProcessedMessageDao
import com.pennywiseai.ynab.data.local.entity.ProcessedMessageEntity
import com.pennywiseai.ynab.data.mapper.WILDCARD_LAST4
import com.pennywiseai.ynab.data.mapper.toDomain
import com.pennywiseai.ynab.data.state.PostingStateStore
import com.pennywiseai.ynab.data.token.TokenStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single shared parse -> post path for both capture modes (design spec, ADR-0003).
 * classify() is the reusable decision (parse, skip, route, currency-guard, local dedup,
 * pause) that both process() (real-time single POST) and BackfillProcessor (bulk POST)
 * run. process() handles ONE message end-to-end: classify, record the terminal outcome,
 * and for a Postable, POST a one-element array and record. An un-parseable message is
 * dropped and never logged.
 */
@Singleton
open class TransactionPipeline @Inject constructor(
    private val smsParser: SmsParser,
    private val mapper: TransactionMapper,
    private val resolver: MappingResolver,
    private val poster: TransactionPoster,
    private val mappingRuleDao: MappingRuleDao,
    private val processedMessageDao: ProcessedMessageDao,
    private val tokenStore: TokenStore,
    private val postingState: PostingStateStore,
) {

    /**
     * The routing table as domain rules. Bulk callers snapshot this ONCE per run and pass
     * it to classify() so an N-message batch reads the table once, not N times.
     */
    suspend fun currentRules(): List<MappingRule> = mappingRuleDao.getAll().map { it.toDomain() }

    /**
     * The shared decision, up to (but not including) the POST. Records NOTHING — the
     * caller records skip/pause rows and POSTs Postables. The only state it mutates is
     * the existing no-token pause latch (so a bad/absent token can't trigger a 401 storm).
     *
     * [rules] is an optional preloaded snapshot: null (the real-time path) reads fresh
     * rules from the DAO; backfill passes one snapshot for the whole batch, so a mid-run
     * rule edit is deliberately not picked up by later messages of that run.
     */
    suspend fun classify(
        body: String,
        sender: String,
        timestamp: Long,
        rules: List<MappingRule>? = null,
    ): Classification {
        // 1. Parse. No parser match -> no import_id exists; drop silently.
        val parsed = smsParser.parse(body, sender, timestamp) ?: return Classification.Dropped
        val importId = mapper.importIdFor(parsed)

        // 2. Non-postable type (TRANSFER / BALANCE_UPDATE) -> skip before the mapper (ADR-0002).
        if (!parsed.type.isPostable()) {
            return Classification.Skipped(MessageStatus.SKIPPED_NON_TRANSACTION, parsed, importId)
        }

        // 3. Resolve the route (exact last4 beats bank wildcard). An ignore rule ("route
        //    to null") drops the message silently — never logged — before any other check.
        //    Missing OR broken -> fail fast as SKIPPED_UNROUTED; a broken route never hits
        //    the network.
        val rule = resolver.resolve(rules ?: currentRules(), parsed.bankName, parsed.accountLast4)
        if (rule != null && rule.ignored) {
            return Classification.Dropped
        }
        if (rule == null || rule.broken) {
            return Classification.Skipped(MessageStatus.SKIPPED_UNROUTED, parsed, importId)
        }

        // 4. Currency guard -> never POST a wrong-currency amount (no FX).
        if (!parsed.currency.equals(rule.currencyCode, ignoreCase = true)) {
            return Classification.Skipped(MessageStatus.SKIPPED_CURRENCY_MISMATCH, parsed, importId)
        }

        // 5. Local dedup (best-effort optimization only; YNAB import_id is the authority).
        if (processedMessageDao.getByImportId(importId)?.status == MessageStatus.POSTED) {
            return Classification.AlreadyPosted(parsed, importId)
        }

        // 6. Build the YNAB transaction.
        val transaction = mapper.map(parsed, rule)

        // 7. Pause / no-token short-circuit BEFORE the network (no 401 storm).
        val token = tokenStore.getToken()
        if (postingState.isPaused() || token.isNullOrBlank()) {
            if (token.isNullOrBlank()) postingState.setPaused(true)
            val error = if (token.isNullOrBlank()) ERROR_NO_TOKEN else ERROR_TOKEN_INVALID
            return Classification.Paused(parsed, importId, error)
        }

        return Classification.Postable(parsed, importId, rule, transaction)
    }

    /** Process ONE message end-to-end (real-time path): classify, record, POST if Postable. */
    open suspend fun process(body: String, sender: String, timestamp: Long): PipelineResult =
        when (val c = classify(body, sender, timestamp)) {
            is Classification.Dropped -> PipelineResult.Dropped
            is Classification.Skipped -> {
                record(c.parsed, c.importId, c.status)
                PipelineResult.Skipped(c.status)
            }
            is Classification.AlreadyPosted -> PipelineResult.Posted
            is Classification.Paused -> {
                record(c.parsed, c.importId, MessageStatus.FAILED, c.error)
                PipelineResult.Failed(retryable = false)
            }
            is Classification.Postable -> postSingle(c)
        }

    private suspend fun postSingle(c: Classification.Postable): PipelineResult =
        when (val outcome = poster.post(c.rule.budgetId, listOf(c.transaction))) {
            is PostOutcome.Posted -> {
                record(c.parsed, c.importId, MessageStatus.POSTED)
                PipelineResult.Posted
            }
            is PostOutcome.Unauthorized -> {
                postingState.setPaused(true)
                record(c.parsed, c.importId, MessageStatus.FAILED, ERROR_TOKEN_INVALID)
                PipelineResult.Failed(retryable = false)
            }
            is PostOutcome.RouteBroken -> {
                mappingRuleDao.setBroken(c.rule.bankName, c.rule.last4 ?: WILDCARD_LAST4, true)
                record(c.parsed, c.importId, MessageStatus.FAILED, ERROR_ROUTE_BROKEN)
                PipelineResult.Failed(retryable = false)
            }
            is PostOutcome.Failed -> {
                record(c.parsed, c.importId, MessageStatus.FAILED, outcome.error)
                PipelineResult.Failed(outcome.retryable)
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
        const val ERROR_NO_TOKEN = "no token - awaiting token"
        const val ERROR_TOKEN_INVALID = "token invalid - awaiting new token"
        const val ERROR_ROUTE_BROKEN = "route target missing - rule marked broken (remap needed)"
    }
}
