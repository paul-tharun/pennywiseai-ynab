package com.pennywiseai.ynab.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pennywiseai.ynab.data.local.dao.MappingRuleDao
import com.pennywiseai.ynab.data.local.dao.ProcessedMessageDao
import com.pennywiseai.ynab.data.local.entity.MappingRuleEntity
import com.pennywiseai.ynab.data.local.entity.ProcessedMessageEntity
import kotlinx.coroutines.test.runTest
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
class UnroutedSuggestionsTest {

    private lateinit var db: PennyWiseDatabase
    private lateinit var messages: ProcessedMessageDao
    private lateinit var rules: MappingRuleDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PennyWiseDatabase::class.java,
        ).allowMainThreadQueries().build()
        messages = db.processedMessageDao()
        rules = db.mappingRuleDao()
    }

    @After
    fun tearDown() = db.close()

    private var seq = 0L
    private suspend fun logged(bank: String, last4: String?, status: MessageStatus) {
        messages.upsert(
            ProcessedMessageEntity(
                importId = "PW:${seq++}",
                sender = "S", bankName = bank, last4 = last4,
                amount = BigDecimal("1.00"), currency = "INR",
                status = status, error = null, timestamp = seq,
            ),
        )
    }

    private suspend fun unrouted() =
        messages.getUnroutedSuggestions(MessageStatus.SKIPPED_UNROUTED)

    @Test
    fun `an unrouted combo with no covering rule is suggested`() = runTest {
        logged("HDFC Bank", "1234", MessageStatus.SKIPPED_UNROUTED)
        assertEquals(listOf(UnroutedSuggestion("HDFC Bank", "1234")), unrouted())
    }

    @Test
    fun `a combo covered by an exact rule is not suggested`() = runTest {
        logged("HDFC Bank", "1234", MessageStatus.SKIPPED_UNROUTED)
        rules.insert(MappingRuleEntity(bankName = "HDFC Bank", last4 = "1234", budgetId = "b", accountId = "a", currencyCode = "INR"))
        assertEquals(emptyList<UnroutedSuggestion>(), unrouted())
    }

    @Test
    fun `a combo covered by a bank wildcard is not suggested`() = runTest {
        logged("HDFC Bank", "1234", MessageStatus.SKIPPED_UNROUTED)
        rules.insert(MappingRuleEntity(bankName = "HDFC Bank", last4 = "", budgetId = "b", accountId = "a", currencyCode = "INR"))
        assertEquals(emptyList<UnroutedSuggestion>(), unrouted())
    }

    @Test
    fun `a null-last4 message is only covered by a wildcard`() = runTest {
        logged("HDFC Bank", null, MessageStatus.SKIPPED_UNROUTED)
        rules.insert(MappingRuleEntity(bankName = "HDFC Bank", last4 = "1234", budgetId = "b", accountId = "a", currencyCode = "INR"))
        // exact rule does not cover a null-last4 message -> still suggested
        assertEquals(listOf(UnroutedSuggestion("HDFC Bank", null)), unrouted())
        rules.insert(MappingRuleEntity(bankName = "HDFC Bank", last4 = "", budgetId = "b2", accountId = "a2", currencyCode = "INR"))
        // adding the wildcard now covers it
        assertEquals(emptyList<UnroutedSuggestion>(), unrouted())
    }

    @Test
    fun `non-unrouted statuses are ignored`() = runTest {
        logged("HDFC Bank", "1234", MessageStatus.POSTED)
        logged("HDFC Bank", "5678", MessageStatus.FAILED)
        assertEquals(emptyList<UnroutedSuggestion>(), unrouted())
    }

    @Test
    fun `duplicate unrouted rows collapse to one suggestion`() = runTest {
        logged("HDFC Bank", "1234", MessageStatus.SKIPPED_UNROUTED)
        logged("HDFC Bank", "1234", MessageStatus.SKIPPED_UNROUTED)
        assertEquals(1, unrouted().size)
    }
}
