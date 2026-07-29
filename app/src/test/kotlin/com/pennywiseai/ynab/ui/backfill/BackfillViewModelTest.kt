package com.pennywiseai.ynab.ui.backfill

import com.pennywiseai.ynab.capture.BackfillRun
import com.pennywiseai.ynab.ui.rules.BackfillEnqueuer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BackfillViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private var capturedFrom: Long? = null
    private var capturedTo: Long? = null
    private var cancelled = false
    private val runFlow = MutableStateFlow<BackfillRun>(BackfillRun.Idle)

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm(now: Long = 1_000_000_000L) = BackfillViewModel(
        enqueuer = BackfillEnqueuer { f, t -> capturedFrom = f; capturedTo = t },
        canceller = BackfillCanceller { cancelled = true },
        observer = BackfillObserver { runFlow },
        now = { now },
    )

    @Test
    fun `startQuickRange enqueues the last N days`() {
        val now = 5_000_000_000L
        vm(now = now).startQuickRange(30)
        assertEquals(now - 30L * 24 * 60 * 60 * 1000, capturedFrom)
        assertEquals(now, capturedTo)
    }

    @Test
    fun `startCustom adds a day to the end date`() {
        vm().startCustom(fromMillis = 100L, toDateMillis = 200L)
        assertEquals(100L, capturedFrom)
        assertEquals(200L + 24L * 60 * 60 * 1000, capturedTo)
    }

    @Test
    fun `cancel forwards to the canceller`() {
        vm().cancel()
        assertTrue(cancelled)
    }

    @Test
    fun `run reflects observer emissions`() = runTest(dispatcher) {
        val vm = vm()
        runFlow.value = BackfillRun.Running(done = 5, total = 10)
        assertEquals(BackfillRun.Running(5, 10), vm.run.first { it is BackfillRun.Running })
    }
}
