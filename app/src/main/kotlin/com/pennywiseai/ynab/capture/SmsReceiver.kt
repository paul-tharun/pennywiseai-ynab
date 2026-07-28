package com.pennywiseai.ynab.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Real-time capture (ADR-0003): onReceive runs on the main thread under a ~10s ANR
 * ceiling, so it only reassembles the multipart PDUs and enqueues an expedited worker —
 * it never parses or posts inline. Guarded by BROADCAST_SMS in the manifest so only the
 * system can deliver the broadcast.
 */
@AndroidEntryPoint
class SmsReceiver : BroadcastReceiver() {

    @Inject
    lateinit var scheduler: CaptureScheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return

        val raw = reassembleSms(
            bodies = parts.map { it.messageBody.orEmpty() },
            sender = parts.first().originatingAddress,
            timestamp = parts.first().timestampMillis,
        ) ?: return

        scheduler.enqueueRealtime(raw.body, raw.sender, raw.timestamp)
    }
}
