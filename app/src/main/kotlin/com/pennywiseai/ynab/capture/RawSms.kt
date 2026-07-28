package com.pennywiseai.ynab.capture

/** A raw SMS ready for the pipeline: full (reassembled) text + its sender + receipt time. */
data class RawSms(val sender: String, val body: String, val timestamp: Long)

/**
 * Reassemble a multipart SMS by concatenating each PDU part's body in order (design spec,
 * SMS capture). Returns null when there are no parts or the sender is blank (nothing the
 * pipeline could route). Kept a pure function so it's testable without the final
 * framework SmsMessage class — the receiver maps SmsMessage[] -> bodies/sender/timestamp.
 */
fun reassembleSms(bodies: List<String>, sender: String?, timestamp: Long): RawSms? {
    if (bodies.isEmpty()) return null
    if (sender.isNullOrBlank()) return null
    return RawSms(sender = sender, body = bodies.joinToString(separator = ""), timestamp = timestamp)
}
