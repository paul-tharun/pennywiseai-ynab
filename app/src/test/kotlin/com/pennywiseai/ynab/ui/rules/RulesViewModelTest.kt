package com.pennywiseai.ynab.ui.rules

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pennywiseai.ynab.data.local.MessageStatus
import com.pennywiseai.ynab.data.local.PennyWiseDatabase
import com.pennywiseai.ynab.data.local.entity.ProcessedMessageEntity
import com.pennywiseai.ynab.data.mapper.WILDCARD_LAST4
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal

/**
 * Exercises the REAL RulesViewModel against a real in-memory Room DB and a capturing
 * BackfillEnqueuer (the same functional seam Task 7 introduced) — never a mirror of the VM.
 * retroImport() launches on viewModelScope, so a StandardTestDispatcher is installed as Main
 * and advanceUntilIdle() drains the launched coroutine before assertions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class RulesViewModelTest {

    private lateinit var db: PennyWiseDatabase

    // Shared across Main and runTest so viewModelScope launches advance with advanceUntilIdle().
    private val dispatcher = StandardTestDispatcher()

    private var capturedFrom: Long? = null
    private var capturedTo: Long? = null
    private var enqueueCalled = false

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

    private fun draft(bank: String = "HDFC Bank", last4: String? = "1234") = RuleDraft(
        bankName = bank, last4 = last4, budgetId = "b1", accountId = "a1", currencyCode = "INR", editRuleId = null,
    )

    /** Builds the ACTUAL RulesViewModel wired to the real DB DAOs + a capturing enqueuer. */
    private fun vm(): RulesViewModel = RulesViewModel(
        mappingRuleDao = db.mappingRuleDao(),
        snapshotDao = db.snapshotDao(),
        processedMessageDao = db.processedMessageDao(),
        enqueuer = BackfillEnqueuer { from, to ->
            capturedFrom = from
            capturedTo = to
            enqueueCalled = true
        },
    )

    private fun unrouted(id: String, bank: String, ts: Long) = ProcessedMessageEntity(
        importId = id, sender = "s", bankName = bank, last4 = "1234",
        amount = BigDecimal.ONE, currency = "INR",
        status = MessageStatus.SKIPPED_UNROUTED, error = null, timestamp = ts,
    )

    @Test
    fun `saveRule inserts and shows in the reactive list`() = runTest(dispatcher) {
        val vm = vm()
        assertEquals(SaveRuleResult.Saved, vm.saveRule(draft()))
        // rules is a WhileSubscribed stateIn seeded with emptyList; await the Room emission
        // instead of reading the seed value.
        assertEquals(listOf("1234"), vm.rules.first { it.isNotEmpty() }.map { it.last4 })
    }

    @Test
    fun `saveRule rejects a duplicate route`() = runTest(dispatcher) {
        val vm = vm()
        vm.saveRule(draft())
        assertEquals(SaveRuleResult.DuplicateRoute, vm.saveRule(draft()))
    }

    @Test
    fun `wildcard draft stores empty-string last4`() = runTest(dispatcher) {
        val vm = vm()
        vm.saveRule(draft(last4 = null))
        assertEquals(WILDCARD_LAST4, db.mappingRuleDao().getAll().single().last4)
    }

    @Test
    fun `retroImport uses the earliest unrouted timestamp for the bank`() = runTest(dispatcher) {
        val dao = db.processedMessageDao()
        // Earliest SKIPPED_UNROUTED for HDFC Bank is ts=777; a later HDFC row and a
        // different bank at a different ts prove per-bank, earliest-first scoping.
        dao.upsert(unrouted("h1", "HDFC Bank", ts = 777L))
        dao.upsert(unrouted("h2", "HDFC Bank", ts = 999L))
        dao.upsert(unrouted("i1", "ICICI Bank", ts = 100L))

        // join() the launched Job: retroImport's DAO read hops to Room's real query executor,
        // so advanceUntilIdle() alone can return before the coroutine resumes and enqueues.
        vm().retroImport("HDFC Bank").join()
        advanceUntilIdle()

        assertTrue(enqueueCalled)
        assertEquals(777L, capturedFrom)
        // to == System.currentTimeMillis()+1 (non-deterministic); assert it bounds a real window.
        assertTrue(capturedTo!! > 777L)
    }

    @Test
    fun `retroImport is a no-op when the bank has no unrouted rows`() = runTest(dispatcher) {
        db.processedMessageDao().upsert(unrouted("h1", "HDFC Bank", ts = 777L))

        vm().retroImport("Nonexistent Bank").join()
        advanceUntilIdle()

        assertFalse(enqueueCalled)
    }
}
