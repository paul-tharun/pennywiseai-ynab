package com.pennywiseai.ynab.core

import com.pennywiseai.parser.core.ParsedTransaction
import com.pennywiseai.ynab.core.model.MappingRule
import com.pennywiseai.ynab.core.model.SaveTransaction
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * Maps a routed, postable ParsedTransaction to a YNAB SaveTransaction.
 * `zoneId` controls date derivation — the device zone in production, a fixed
 * zone in tests.
 */
class TransactionMapper(private val zoneId: ZoneId = ZoneId.systemDefault()) {

    fun map(parsed: ParsedTransaction, rule: MappingRule): SaveTransaction {
        require(parsed.type.isPostable()) {
            "Non-postable type ${parsed.type} must be skipped upstream, not mapped"
        }

        val magnitude = parsed.amount
            .movePointRight(3)
            .setScale(0, RoundingMode.HALF_UP)
            .toLong()
        val amount = abs(magnitude) * parsed.type.ynabSign()

        val date = Instant.ofEpochMilli(parsed.timestamp)
            .atZone(zoneId)
            .toLocalDate()
            .format(DateTimeFormatter.ISO_LOCAL_DATE) // yyyy-MM-dd

        return SaveTransaction(
            accountId = rule.accountId,
            date = date,
            amount = amount,
            payeeName = parsed.merchant?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_PAYEE),
            memo = parsed.reference?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_MEMO),
            importId = importIdFor(parsed),
        )
    }

    /**
     * The YNAB import_id for a parsed message. Exposed so the pipeline can compute the
     * log's primary key on the skip paths before it has a rule to map; map() uses it too,
     * so a skipped row's id matches a later successful post (ADR-0005). From the cutoff on,
     * the id also carries the SMS send time so byte-identical SMS stay distinct (ADR-0006).
     */
    fun importIdFor(parsed: ParsedTransaction): String {
        val contentId = parsed.generateTransactionId()
        if (parsed.timestamp < DATED_IMPORT_ID_SINCE_MILLIS) return IMPORT_ID_PREFIX + contentId
        val sentAt = Instant.ofEpochMilli(parsed.timestamp).atOffset(ZoneOffset.UTC).format(SENT_AT_FORMAT)
        return IMPORT_ID_PREFIX + contentId.take(DATED_HASH_LENGTH) + ":" + sentAt
    }

    companion object {
        const val IMPORT_ID_PREFIX = "PW:"
        const val MAX_PAYEE = 50
        const val MAX_MEMO = 200
        const val DATED_HASH_LENGTH = 18
        val DATED_IMPORT_ID_SINCE_MILLIS: Long = Instant.parse("2026-11-16T18:30:00Z").toEpochMilli()
        private val SENT_AT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    }
}
