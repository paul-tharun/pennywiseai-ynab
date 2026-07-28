package com.pennywiseai.ynab.ui.settings

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pennywiseai.ynab.data.local.MessageStatus
import com.pennywiseai.ynab.data.local.PennyWiseDatabase
import com.pennywiseai.ynab.data.local.entity.ProcessedMessageEntity
import com.pennywiseai.ynab.data.remote.FakeYnabApi
import com.pennywiseai.ynab.data.repository.YnabRepository
import com.pennywiseai.ynab.data.state.FakePostingStateStore
import com.pennywiseai.ynab.data.token.FakeTokenStore
import com.pennywiseai.ynab.ui.rules.BackfillEnqueuer
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal

/**
 * Exercises the REAL SettingsViewModel.retryFailedFrom (internal), not a mirror of it:
 * seeds FAILED rows in a real in-memory Room DB and asserts the backfill seam is invoked
 * with the earliest-FAILED timestamp and now+1 (or not at all when there are no FAILED rows).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsViewModelRetryTest {

    private lateinit var db: PennyWiseDatabase

    private var capturedFrom: Long? = null
    private var capturedTo: Long? = null
    private var enqueueCalled = false

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PennyWiseDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun failed(id: String, ts: Long) = ProcessedMessageEntity(
        importId = id, sender = "s", bankName = "HDFC Bank", last4 = "1234",
        amount = BigDecimal.ONE, currency = "INR", status = MessageStatus.FAILED, error = "x", timestamp = ts,
    )

    /** Builds the ACTUAL SettingsViewModel wired to the real DB DAOs + a capturing enqueuer. */
    private fun buildViewModel(): SettingsViewModel {
        val tokenStore = FakeTokenStore()
        val postingState = FakePostingStateStore()
        // retryFailedFrom does not touch the repository; a real one over fakes satisfies the ctor.
        val repository = YnabRepository(
            FakeYnabApi(), db.snapshotDao(), db.mappingRuleDao(), tokenStore, postingState,
        )
        val enqueuer = BackfillEnqueuer { from, to ->
            capturedFrom = from
            capturedTo = to
            enqueueCalled = true
        }
        return SettingsViewModel(
            repository = repository,
            tokenStore = tokenStore,
            postingState = postingState,
            mappingRuleDao = db.mappingRuleDao(),
            processedMessageDao = db.processedMessageDao(),
            enqueuer = enqueuer,
            snapshotDao = db.snapshotDao(),
        )
    }

    @Test
    fun `retryFailedFrom enqueues from the earliest FAILED timestamp`() = runTest {
        val dao = db.processedMessageDao()
        dao.upsert(failed("a", ts = 500))
        dao.upsert(failed("b", ts = 200))

        buildViewModel().retryFailedFrom(1000L)

        assertEquals(200L, capturedFrom)
        assertEquals(1001L, capturedTo)
    }

    @Test
    fun `no FAILED rows means no enqueue`() = runTest {
        buildViewModel().retryFailedFrom(1000L)

        assertFalse(enqueueCalled)
    }
}
