package com.pennywiseai.ynab.capture

/** In-memory SmsInboxReader: returns seeded messages whose timestamp is in [from, to). */
class FakeSmsInboxReader(private val messages: List<RawSms> = emptyList()) : SmsInboxReader {
    override suspend fun read(fromMillis: Long, toMillis: Long): List<RawSms> =
        messages.filter { it.timestamp in fromMillis until toMillis }
            .sortedByDescending { it.timestamp }
}
