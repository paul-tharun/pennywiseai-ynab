package com.pennywiseai.ynab.capture

import android.content.Context
import android.provider.Telephony
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads content://sms/inbox over a date range (READ_SMS). Each row's BODY is the full
 * message (the provider concatenates multipart PDUs on store), so no reassembly is done
 * here — unlike the live receiver path. DATE is epoch millis.
 */
@Singleton
class ContentResolverSmsInboxReader @Inject constructor(
    @ApplicationContext private val context: Context,
) : SmsInboxReader {

    override suspend fun read(fromMillis: Long, toMillis: Long): List<RawSms> {
        val projection = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
        val selection = "${Telephony.Sms.DATE} >= ? AND ${Telephony.Sms.DATE} < ?"
        val args = arrayOf(fromMillis.toString(), toMillis.toString())

        val result = mutableListOf<RawSms>()
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            projection,
            selection,
            args,
            "${Telephony.Sms.DATE} DESC",
        )?.use { cursor ->
            val addressIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            while (cursor.moveToNext()) {
                val sender = cursor.getString(addressIdx) ?: continue
                val body = cursor.getString(bodyIdx) ?: continue
                result += RawSms(sender = sender, body = body, timestamp = cursor.getLong(dateIdx))
            }
        }
        return result
    }
}
