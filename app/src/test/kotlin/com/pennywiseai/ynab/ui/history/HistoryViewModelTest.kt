package com.pennywiseai.ynab.ui.history

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pennywiseai.ynab.data.local.MessageStatus
import com.pennywiseai.ynab.data.local.PennyWiseDatabase
import com.pennywiseai.ynab.data.local.entity.ProcessedMessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/**
 * Exercises the REAL HistoryViewModel against a real in-memory Room DB and a capturing
 * MessageRetrier seam (never a mirror of the VM). `items` is a WhileSubscribed stateIn
 * whose initial value is emptyList(); the filter test installs a StandardTestDispatcher
 * as Main and collects the first NON-EMPTY emission so it observes the real filtered
 * Room stream rather than racing the initial value.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {

    private lateinit var db: PennyWiseDatabase

    // Shared across Main and runTest so viewModelScope/stateIn advance with advanceUntilIdle().
    private val dispatcher = StandardTestDispatcher()

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

    @Test
    fun `retry re-drives via the retrier with the row timestamp`() = runTest {
        var retriedTs: Long? = null
        val vm = HistoryViewModel(db.processedMessageDao(), MessageRetrier { retriedTs = it })
        vm.retry(row("a", MessageStatus.FAILED, ts = 424242L))
        assertEquals(424242L, retriedTs)
    }

    @Test
    fun `filter switches to the status stream`() = runTest {
        // Seeded: one POSTED "a", one FAILED "b". Filtering to FAILED must yield only "b",
        // so this assertion fails if setFilter were a no-op (observeAll would return both).
        db.processedMessageDao().upsert(row("a", MessageStatus.POSTED, ts = 1))
        db.processedMessageDao().upsert(row("b", MessageStatus.FAILED, ts = 2))
        val vm = HistoryViewModel(db.processedMessageDao(), MessageRetrier {})

        vm.setFilter(MessageStatus.FAILED)
        advanceUntilIdle()

        // Collect the first REAL (non-empty) emission from the FAILED stream, not the
        // emptyList() initial value of the WhileSubscribed stateIn.
        val result = vm.items.first { it.isNotEmpty() }
        assertEquals(listOf("b"), result.map { it.importId })
    }
}
