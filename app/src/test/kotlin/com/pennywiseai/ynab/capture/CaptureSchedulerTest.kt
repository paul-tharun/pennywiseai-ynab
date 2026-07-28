package com.pennywiseai.ynab.capture

import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureSchedulerTest {

    private lateinit var scheduler: CaptureScheduler

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        scheduler = CaptureScheduler(context)
    }

    @Test
    fun `backfillStatus is false with no work`() = runTest {
        assertFalse(scheduler.backfillStatus().first())
    }

    @Test
    fun `backfillStatus is true while a backfill is enqueued`() = runTest {
        // The request carries a CONNECTED constraint; the test WorkManager leaves it
        // ENQUEUED (constraints unmet) rather than finishing it, so status is true.
        scheduler.enqueueBackfill(0L, 100L)
        assertTrue(scheduler.backfillStatus().first())
    }

    @Test
    fun `retryMessage enqueues the backfill work`() = runTest {
        scheduler.retryMessage(1234L)
        assertTrue(scheduler.backfillStatus().first())
    }
}
