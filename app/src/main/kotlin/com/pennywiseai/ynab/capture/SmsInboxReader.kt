package com.pennywiseai.ynab.capture

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The backfill's SMS source (design spec: on-demand date-range backfill reads
 * content://sms/inbox). A seam so BackfillProcessor is tested with a fake, and the real
 * ContentResolver query (framework glue) is covered by assembleDebug + the smoke check.
 */
interface SmsInboxReader {
    /** Inbox messages with DATE in [fromMillis, toMillis), newest first. Rows are already
     *  fully reassembled by the telephony provider (no multipart concatenation needed). */
    suspend fun read(fromMillis: Long, toMillis: Long): List<RawSms>
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SmsInboxReaderModule {

    @Binds
    @Singleton
    abstract fun bindSmsInboxReader(impl: ContentResolverSmsInboxReader): SmsInboxReader
}
