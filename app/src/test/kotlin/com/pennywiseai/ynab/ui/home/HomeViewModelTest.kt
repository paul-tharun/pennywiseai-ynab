package com.pennywiseai.ynab.ui.home

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pennywiseai.ynab.capture.BackfillRun
import com.pennywiseai.ynab.data.local.MessageStatus
import com.pennywiseai.ynab.data.local.PennyWiseDatabase
import com.pennywiseai.ynab.data.local.entity.ProcessedMessageEntity
import com.pennywiseai.ynab.ui.backfill.BackfillObserver
import com.pennywiseai.ynab.ui.rules.BackfillEnqueuer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private lateinit var db: PennyWiseDatabase
    private val dispatcher = StandardTestDispatcher()

    private var capturedFrom: Long? = null
    private var capturedTo: Long? = null
    private val runFlow = MutableStateFlow<BackfillRun>(BackfillRun.Idle)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PennyWiseDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun row(id: String, status: MessageStatus, ts: Long) = ProcessedMessageEntity(
        importId = id, sender = "s", bankName = "HDFC Bank", last4 = "1234",
        amount = BigDecimal.ONE, currency = "INR", status = status, error = null, timestamp = ts,
    )

    private fun vm(now: Long = 10_000_000L) = HomeViewModel(
        dao = db.processedMessageDao(),
        retrier = MessageRetrier {},
        enqueuer = BackfillEnqueuer { from, to -> capturedFrom = from; capturedTo = to },
        observer = BackfillObserver { runFlow },
        now = { now },
    )

    @Test
    fun `stats count each status and report the newest timestamp`() = runTest {
        val dao = db.processedMessageDao()
        dao.upsert(row("a", MessageStatus.POSTED, ts = 100))
        dao.upsert(row("b", MessageStatus.POSTED, ts = 300))
        dao.upsert(row("c", MessageStatus.FAILED, ts = 200))
        dao.upsert(row("d", MessageStatus.SKIPPED_UNROUTED, ts = 150))
        dao.upsert(row("e", MessageStatus.SKIPPED_NON_TRANSACTION, ts = 250))
        val vm = vm()
        advanceUntilIdle()

        val stats = vm.stats.first { it.lastActivityMillis != null }
        assertEquals(2, stats.posted)
        assertEquals(1, stats.failed)
        assertEquals(1, stats.unrouted) // non-transaction noise is NOT counted here
        assertEquals(300L, stats.lastActivityMillis)
    }

    @Test
    fun `rescan enqueues the last 24h window`() = runTest {
        val now = 100_000_000L
        vm(now = now).rescan()
        advanceUntilIdle()
        assertEquals(now - 24L * 60 * 60 * 1000, capturedFrom)
        assertEquals(now, capturedTo)
    }

    @Test
    fun `rescan surfaces the imported count once the run finishes`() = runTest {
        val vm = vm()
        vm.rescan()
        advanceUntilIdle()
        assertEquals(RescanState.Running, vm.rescanState.value)

        runFlow.value = BackfillRun.Running(done = 1, total = 3)
        advanceUntilIdle()
        runFlow.value = BackfillRun.Done(posted = 3, skipped = 0, failed = 0)
        advanceUntilIdle()

        assertEquals(RescanState.Result(imported = 3), vm.rescanState.value)
    }

    @Test
    fun `rescan settles back to Idle when the run is cancelled or fails`() = runTest {
        val vm = vm()
        vm.rescan()
        advanceUntilIdle()
        assertEquals(RescanState.Running, vm.rescanState.value)

        runFlow.value = BackfillRun.Running(done = 1, total = 3)
        advanceUntilIdle()
        runFlow.value = BackfillRun.Idle // CaptureScheduler maps a CANCELLED/FAILED run to Idle
        advanceUntilIdle()

        assertEquals(RescanState.Idle, vm.rescanState.value)
    }
}
