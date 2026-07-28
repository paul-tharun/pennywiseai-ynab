package com.pennywiseai.ynab.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pennywiseai.ynab.data.local.dao.SnapshotDao
import com.pennywiseai.ynab.data.local.entity.AccountEntity
import com.pennywiseai.ynab.data.local.entity.BudgetEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SnapshotDaoTest {

    private lateinit var db: PennyWiseDatabase
    private lateinit var dao: SnapshotDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PennyWiseDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.snapshotDao()
    }

    @After
    fun tearDown() = db.close()

    private val usd = BudgetEntity("bud-usd", "Personal (USD)", "USD")
    private val inr = BudgetEntity("bud-inr", "Family (INR)", "INR")
    private fun acct(id: String, budgetId: String, closed: Boolean = false, deleted: Boolean = false) =
        AccountEntity(id = id, budgetId = budgetId, name = "Acct $id", closed = closed, deleted = deleted)

    @Test
    fun `replaceSnapshot populates budgets and accounts`() = runTest {
        dao.replaceSnapshot(
            budgets = listOf(usd, inr),
            accounts = listOf(acct("a1", "bud-usd"), acct("a2", "bud-inr")),
        )
        assertEquals(listOf("Family (INR)", "Personal (USD)"), dao.getBudgets().map { it.name }) // sorted by name
        assertEquals(listOf("a1"), dao.getOpenAccounts("bud-usd").map { it.id })
    }

    @Test
    fun `getOpenAccounts excludes closed and deleted`() = runTest {
        dao.replaceSnapshot(
            budgets = listOf(usd),
            accounts = listOf(
                acct("open", "bud-usd"),
                acct("closed", "bud-usd", closed = true),
                acct("deleted", "bud-usd", deleted = true),
            ),
        )
        assertEquals(listOf("open"), dao.getOpenAccounts("bud-usd").map { it.id })
    }

    @Test
    fun `getBudgetCurrency returns the iso code, or null when unknown`() = runTest {
        dao.replaceSnapshot(listOf(usd), listOf(acct("a1", "bud-usd")))
        assertEquals("USD", dao.getBudgetCurrency("bud-usd"))
        assertEquals(null, dao.getBudgetCurrency("bud-missing"))
    }

    @Test
    fun `accountExists is true only for a matching budget and account`() = runTest {
        dao.replaceSnapshot(listOf(usd, inr), listOf(acct("a1", "bud-usd")))
        assertTrue(dao.accountExists("bud-usd", "a1"))
        assertFalse(dao.accountExists("bud-inr", "a1"))   // right account id, wrong budget
        assertFalse(dao.accountExists("bud-usd", "gone")) // unknown account
    }

    @Test
    fun `replaceSnapshot cascade-clears the previous tree`() = runTest {
        dao.replaceSnapshot(listOf(usd), listOf(acct("old", "bud-usd")))
        dao.replaceSnapshot(listOf(inr), listOf(acct("new", "bud-inr")))
        assertEquals(listOf("bud-inr"), dao.getBudgets().map { it.id })
        assertEquals(emptyList<String>(), dao.getOpenAccounts("bud-usd").map { it.id }) // old accounts cascaded out
        assertEquals(listOf("new"), dao.getOpenAccounts("bud-inr").map { it.id })
    }
}
