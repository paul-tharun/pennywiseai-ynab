package com.pennywiseai.ynab.pipeline

import com.pennywiseai.parser.core.ParsedTransaction
import com.pennywiseai.ynab.core.MappingResolver
import com.pennywiseai.ynab.core.TransactionMapper
import com.pennywiseai.ynab.core.isPostable
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
 * The single shared parse -> post path for both capture modes (design spec).
 * Processes ONE message: parse, skip non-transactions, resolve the route, fail fast
 * on a broken route, guard currency, map, dedup locally, then POST (unless paused /
 * no token) and record the outcome. Writes exactly one ProcessedMessageEntity per
 * handled message (keyed by import_id, upserted); an un-parseable message is dropped
 * and never logged.
 */
@Singleton
class TransactionPipeline @Inject constructor(
    private val smsParser: SmsParser,
    private val mapper: TransactionMapper,
    private val resolver: MappingResolver,
    private val poster: TransactionPoster,
    private val mappingRuleDao: MappingRuleDao,
    private val processedMessageDao: ProcessedMessageDao,
    private val tokenStore: TokenStore,
    private val postingState: PostingStateStore,
) {

    suspend fun process(body: String, sender: String, timestamp: Long): PipelineResult {
        // 1. Parse. No parser match -> no import_id exists; drop silently, do not log.
        val parsed = smsParser.parse(body, sender, timestamp) ?: return PipelineResult.Dropped
        val importId = mapper.importIdFor(parsed)

        // 2. Non-postable type (TRANSFER / BALANCE_UPDATE) -> skip before the mapper (ADR-0002).
        if (!parsed.type.isPostable()) {
            record(parsed, importId, MessageStatus.SKIPPED_NON_TRANSACTION)
            return PipelineResult.Skipped(MessageStatus.SKIPPED_NON_TRANSACTION)
        }

        // 3. Resolve the route (exact last4 beats bank wildcard). A missing OR broken
        //    route fails fast as SKIPPED_UNROUTED — a broken route never hits the network.
        val rules = mappingRuleDao.getAll().map { it.toDomain() }
        val rule = resolver.resolve(rules, parsed.bankName, parsed.accountLast4)
        if (rule == null || rule.broken) {
            record(parsed, importId, MessageStatus.SKIPPED_UNROUTED)
            return PipelineResult.Skipped(MessageStatus.SKIPPED_UNROUTED)
        }

        // 4. Currency guard -> never POST a wrong-currency amount (no FX).
        if (!parsed.currency.equals(rule.currencyCode, ignoreCase = true)) {
            record(parsed, importId, MessageStatus.SKIPPED_CURRENCY_MISMATCH)
            return PipelineResult.Skipped(MessageStatus.SKIPPED_CURRENCY_MISMATCH)
        }

        // 5. Local dedup (best-effort optimization only; YNAB import_id is the authority).
        if (processedMessageDao.getByImportId(importId)?.status == MessageStatus.POSTED) {
            return PipelineResult.Posted
        }

        // 6. Build the YNAB transaction.
        val transaction = mapper.map(parsed, rule)

        // 7. Pause / no-token short-circuit BEFORE the network (no 401 storm).
        val token = tokenStore.getToken()
        if (postingState.isPaused() || token.isNullOrBlank()) {
            if (token.isNullOrBlank()) postingState.setPaused(true)
            val error = if (token.isNullOrBlank()) ERROR_NO_TOKEN else ERROR_TOKEN_INVALID
            record(parsed, importId, MessageStatus.FAILED, error)
            return PipelineResult.Failed(retryable = false)
        }

        // 8. POST and classify.
        return when (val outcome = poster.post(rule.budgetId, listOf(transaction))) {
            is PostOutcome.Posted -> {
                record(parsed, importId, MessageStatus.POSTED)
                PipelineResult.Posted
            }
            is PostOutcome.Unauthorized -> {
                postingState.setPaused(true)
                record(parsed, importId, MessageStatus.FAILED, ERROR_TOKEN_INVALID)
                PipelineResult.Failed(retryable = false)
            }
            is PostOutcome.RouteBroken -> {
                // Fail fast for the next message too: mark the route broken durably.
                mappingRuleDao.setBroken(rule.bankName, rule.last4 ?: WILDCARD_LAST4, true)
                record(parsed, importId, MessageStatus.FAILED, ERROR_ROUTE_BROKEN)
                PipelineResult.Failed(retryable = false)
            }
            is PostOutcome.Failed -> {
                record(parsed, importId, MessageStatus.FAILED, outcome.error)
                PipelineResult.Failed(outcome.retryable)
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
        const val ERROR_NO_TOKEN = "no token - awaiting token"
        const val ERROR_TOKEN_INVALID = "token invalid - awaiting new token"
        const val ERROR_ROUTE_BROKEN = "route target missing - rule marked broken (remap needed)"
    }
}
