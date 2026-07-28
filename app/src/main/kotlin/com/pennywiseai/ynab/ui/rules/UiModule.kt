package com.pennywiseai.ynab.ui.rules

import com.pennywiseai.ynab.capture.CaptureScheduler
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Wires the UI-facing [BackfillEnqueuer] seam to the real CaptureScheduler. */
@Module
@InstallIn(SingletonComponent::class)
object UiModule {
    @Provides
    @Singleton
    fun provideBackfillEnqueuer(scheduler: CaptureScheduler): BackfillEnqueuer =
        BackfillEnqueuer { from, to -> scheduler.enqueueBackfill(from, to) }

    @Provides
    @Singleton
    fun provideMessageRetrier(scheduler: CaptureScheduler): com.pennywiseai.ynab.ui.history.MessageRetrier =
        com.pennywiseai.ynab.ui.history.MessageRetrier { ts -> scheduler.retryMessage(ts) }
}
