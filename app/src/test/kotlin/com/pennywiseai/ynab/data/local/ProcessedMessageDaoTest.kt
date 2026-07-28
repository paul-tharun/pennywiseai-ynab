package com.pennywiseai.ynab.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pennywiseai.ynab.data.local.dao.ProcessedMessageDao
import com.pennywiseai.ynab.data.local.entity.ProcessedMessageEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProcessedMessageDaoTest {

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
        timestamp: Long,
        last4: String? = "1234",
    ) = ProcessedMessageEntity(
        importId = importId,
        sender = "VM-HDFCBK",
        bankName = "HDFC Bank",
        last4 = last4,
        amount = BigDecimal("100.00"),
        currency = "INR",
        status = status,
        error = null,
        timestamp = timestamp,
    )

    @Test
    fun `upsert then read back by import id preserves fields`() = runTest {
        dao.upsert(msg("PW:a", MessageStatus.POSTED, 1L))
        val row = dao.getByImportId("PW:a")
        assertNotNull(row)
        assertEquals(MessageStatus.POSTED, row!!.status)
        assertEquals(BigDecimal("100.00"), row.amount) // BigDecimal survives the converter
    }

    @Test
    fun `upsert replaces the row with the same import id`() = runTest {
        dao.upsert(msg("PW:a", MessageStatus.SKIPPED_UNROUTED, 1L))
        dao.upsert(msg("PW:a", MessageStatus.POSTED, 2L))
        assertEquals(1, dao.getAll().size)
        assertEquals(MessageStatus.POSTED, dao.getByImportId("PW:a")!!.status)
    }

    @Test
    fun `getAll is reverse chronological`() = runTest {
        dao.upsert(msg("PW:old", MessageStatus.POSTED, 1L))
        dao.upsert(msg("PW:new", MessageStatus.POSTED, 5L))
        assertEquals(listOf("PW:new", "PW:old"), dao.getAll().map { it.importId })
    }

    @Test
    fun `getByStatus filters to one status`() = runTest {
        dao.upsert(msg("PW:a", MessageStatus.POSTED, 1L))
        dao.upsert(msg("PW:b", MessageStatus.FAILED, 2L))
        assertEquals(listOf("PW:b"), dao.getByStatus(MessageStatus.FAILED).map { it.importId })
    }

    @Test
    fun `null last4 round-trips`() = runTest {
        dao.upsert(msg("PW:n", MessageStatus.SKIPPED_UNROUTED, 1L, last4 = null))
        assertEquals(null, dao.getByImportId("PW:n")!!.last4)
    }
}
