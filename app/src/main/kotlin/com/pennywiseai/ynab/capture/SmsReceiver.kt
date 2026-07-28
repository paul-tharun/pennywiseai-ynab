package com.pennywiseai.ynab.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * A cheap, sender-only gate on the real-time capture path, behind a seam. The production
 * binding delegates to parser-core's BankParserFactory (PipelineModule), which only runs
 * each parser's canHandle(sender) — in-memory string/regex matches, no message parsing —
 * so it fits onReceive's main-thread budget. Tests supply a lambda.
 */
fun interface SenderFilter {
    /**
     * True when some bank parser claims this sender, i.e. the message could still parse.
     * False only when it provably can't: BankParserFactory.parse dispatches exclusively to
     * canHandle-matching parsers, so a sender no parser claims can never yield a
     * ParsedTransaction. Answering true is always safe (the worker just re-drops it).
     */
    fun mightBeBank(sender: String): Boolean
}

/**
 * Real-time capture (ADR-0003): onReceive runs on the main thread under a ~10s ANR
 * ceiling, so it only reassembles the multipart PDUs and enqueues an expedited worker —
 * it never parses or posts inline. Guarded by BROADCAST_SMS in the manifest so only the
 * system can deliver the broadcast.
 *
 * Battery constraint: a [SenderFilter] pre-filters on the reassembled sender so promos,
 * OTPs and personal texts never cost a WorkManager enqueue (DB write + JobScheduler
 * round-trip + worker spin-up) just to be dropped by the parser. The filter is sender-only
 * and deliberately over-inclusive; the full parse still happens off-main-thread in the
 * worker, which remains the only place a message is actually accepted or dropped.
 */
@AndroidEntryPoint
class SmsReceiver : BroadcastReceiver() {

    @Inject
    lateinit var scheduler: CaptureScheduler

    @Inject
    lateinit var senderFilter: SenderFilter

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return

        val raw = reassembleSms(
            bodies = parts.map { it.messageBody.orEmpty() },
            sender = parts.first().originatingAddress,
            timestamp = parts.first().timestampMillis,
        ) ?: return

        if (!senderFilter.mightBeBank(raw.sender)) return

        scheduler.enqueueRealtime(raw.body, raw.sender, raw.timestamp)
    }
}
