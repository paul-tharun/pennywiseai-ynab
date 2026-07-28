package com.pennywiseai.ynab.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pennywiseai.ynab.data.local.dao.ProcessedMessageDao
import com.pennywiseai.ynab.data.local.entity.ProcessedMessageEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProcessedMessageDaoReactiveTest {

    private lateinit var db: PennyWiseDatabase
    private lateinit var dao: ProcessedMessageDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PennyWiseDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.processedMessageDao()
    }

    @After
    fun tearDown() = db.close()

    private fun msg(
        importId: String,
        status: MessageStatus,
        bank: String = "HDFC Bank",
        last4: String? = "1234",
        ts: Long,
    ) = ProcessedMessageEntity(
        importId = importId, sender = "VM-HDFC", bankName = bank, last4 = last4,
        amount = BigDecimal("10.00"), currency = "INR", status = status, error = null, timestamp = ts,
    )

    @Test
    fun `observeAll emits newest-first`() = runTest {
        dao.upsert(msg("a", MessageStatus.POSTED, ts = 100))
        dao.upsert(msg("b", MessageStatus.FAILED, ts = 200))
        assertEquals(listOf("b", "a"), dao.observeAll().first().map { it.importId })
    }

    @Test
    fun `observeByStatus filters`() = runTest {
        dao.upsert(msg("a", MessageStatus.POSTED, ts = 100))
        dao.upsert(msg("b", MessageStatus.FAILED, ts = 200))
        assertEquals(listOf("b"), dao.observeByStatus(MessageStatus.FAILED).first().map { it.importId })
    }

    @Test
    fun `observeUnroutedSuggestions returns distinct uncovered combos`() = runTest {
        dao.upsert(msg("a", MessageStatus.SKIPPED_UNROUTED, last4 = "1234", ts = 100))
        dao.upsert(msg("b", MessageStatus.SKIPPED_UNROUTED, last4 = "1234", ts = 150))
        val suggestions = dao.observeUnroutedSuggestions(MessageStatus.SKIPPED_UNROUTED).first()
        assertEquals(1, suggestions.size)
        assertEquals("1234", suggestions.single().last4)
    }

    @Test
    fun `getEarliestTimestampByStatus returns the min or null`() = runTest {
        assertNull(dao.getEarliestTimestampByStatus(MessageStatus.FAILED))
        dao.upsert(msg("a", MessageStatus.FAILED, ts = 300))
        dao.upsert(msg("b", MessageStatus.FAILED, ts = 100))
        assertEquals(100L, dao.getEarliestTimestampByStatus(MessageStatus.FAILED))
    }

    @Test
    fun `getEarliestTimestampByStatusAndBank scopes to the bank`() = runTest {
        dao.upsert(msg("a", MessageStatus.SKIPPED_UNROUTED, bank = "HDFC Bank", ts = 500))
        dao.upsert(msg("b", MessageStatus.SKIPPED_UNROUTED, bank = "ICICI Bank", ts = 100))
        assertEquals(
            500L,
            dao.getEarliestTimestampByStatusAndBank(MessageStatus.SKIPPED_UNROUTED, "HDFC Bank"),
        )
    }
}
