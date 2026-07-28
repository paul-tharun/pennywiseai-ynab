package com.pennywiseai.ynab.ui.rules

import com.pennywiseai.ynab.capture.CaptureScheduler
import com.pennywiseai.ynab.ui.backfill.BackfillCanceller
import com.pennywiseai.ynab.ui.backfill.BackfillObserver
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
    fun provideMessageRetrier(scheduler: CaptureScheduler): com.pennywiseai.ynab.ui.home.MessageRetrier =
        com.pennywiseai.ynab.ui.home.MessageRetrier { ts -> scheduler.retryMessage(ts) }

    @Provides
    @Singleton
    fun provideBackfillObserver(scheduler: CaptureScheduler): BackfillObserver =
        BackfillObserver { scheduler.backfillRun() }

    @Provides
    @Singleton
    fun provideBackfillCanceller(scheduler: CaptureScheduler): BackfillCanceller =
        BackfillCanceller { scheduler.cancelBackfill() }

    /**
     * Dagger ignores Kotlin default parameter values on @Inject constructors, so
     * HomeViewModel's `now: () -> Long = System::currentTimeMillis` still needs an explicit
     * binding here — this just wires it to the same default the constructor declares.
     */
    @Provides
    @Singleton
    fun provideNowMillis(): () -> Long = System::currentTimeMillis
}
