package com.pennywiseai.ynab

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * On-demand WorkManager initialization (design spec / ADR-0003): WorkManager is
 * configured from Hilt so @HiltWorkers can inject app dependencies. Implementing
 * Configuration.Provider (a `val` since WorkManager 2.6) requires removing the
 * default WorkManagerInitializer from the manifest — see AndroidManifest.xml.
 */
@HiltAndroidApp
class PennyWiseYnabApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
