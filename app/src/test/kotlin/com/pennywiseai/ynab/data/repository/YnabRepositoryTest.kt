package com.pennywiseai.ynab.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pennywiseai.ynab.data.local.PennyWiseDatabase
import com.pennywiseai.ynab.data.remote.FakeYnabApi
import com.pennywiseai.ynab.data.remote.dto.AccountDto
import com.pennywiseai.ynab.data.remote.dto.AccountsData
import com.pennywiseai.ynab.data.remote.dto.AccountsResponse
import com.pennywiseai.ynab.data.remote.dto.BudgetDto
import com.pennywiseai.ynab.data.remote.dto.BudgetsData
import com.pennywiseai.ynab.data.remote.dto.BudgetsResponse
import com.pennywiseai.ynab.data.remote.dto.CurrencyFormatDto
import com.pennywiseai.ynab.data.token.FakeTokenStore
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class YnabRepositoryTest {

    private lateinit var db: PennyWiseDatabase
    private lateinit var api: FakeYnabApi
    private lateinit var tokenStore: FakeTokenStore
    private lateinit var repository: YnabRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PennyWiseDatabase::class.java,
        ).allowMainThreadQueries().build()
        api = FakeYnabApi()
        tokenStore = FakeTokenStore()
        repository = YnabRepository(api, db.snapshotDao(), tokenStore)
    }

    @After
    fun tearDown() = db.close()

    private fun budget(id: String, iso: String?) =
        BudgetDto(id = id, name = "Budget $id", currencyFormat = iso?.let { CurrencyFormatDto(it) })

    private fun account(id: String) =
        AccountDto(id = id, name = "Acct $id", closed = false, deleted = false)

    private fun budgetsOk(vararg b: BudgetDto) =
        Response.success(BudgetsResponse(BudgetsData(b.toList())))

    private fun accountsOk(vararg a: AccountDto) =
        Response.success(AccountsResponse(AccountsData(a.toList())))

    @Test
    fun `saveTokenAndRefresh stores the token and persists the snapshot`() = runTest {
        api.budgets = { budgetsOk(budget("b1", "USD")) }
        api.accountsByBudget = { accountsOk(account("a1"), account("a2")) }

        val result = repository.saveTokenAndRefresh("my-pat")

        assertTrue(result is SnapshotResult.Success)
        result as SnapshotResult.Success
        assertEquals(1, result.budgetCount)
        assertEquals(2, result.accountCount)
        assertEquals("my-pat", tokenStore.getToken())
        assertEquals("USD", db.snapshotDao().getBudgetCurrency("b1"))
        assertEquals(listOf("a1", "a2"), db.snapshotDao().getOpenAccounts("b1").map { it.id })
    }

    @Test
    fun `accounts are fetched per budget and associated with the right budget`() = runTest {
        api.budgets = { budgetsOk(budget("b1", "USD"), budget("b2", "INR")) }
        api.accountsByBudget = { budgetId ->
            if (budgetId == "b1") accountsOk(account("a1")) else accountsOk(account("a2"))
        }

        repository.refreshSnapshot()

        assertEquals(listOf("a1"), db.snapshotDao().getOpenAccounts("b1").map { it.id })
        assertEquals(listOf("a2"), db.snapshotDao().getOpenAccounts("b2").map { it.id })
    }

    @Test
    fun `a currency-less budget is dropped from the snapshot`() = runTest {
        api.budgets = { budgetsOk(budget("b1", "USD"), budget("b2", null)) }
        api.accountsByBudget = { accountsOk(account("a1")) }

        val result = repository.refreshSnapshot() as SnapshotResult.Success

        assertEquals(1, result.budgetCount)
        assertEquals(listOf("b1"), db.snapshotDao().getBudgets().map { it.id })
    }

    @Test
    fun `a 401 on budgets returns Unauthorized and does not touch the snapshot`() = runTest {
        api.budgets = { Response.error(401, "{}".toResponseBody("application/json".toMediaType())) }

        val result = repository.saveTokenAndRefresh("bad-pat")

        assertEquals(SnapshotResult.Unauthorized, result)
        assertEquals(emptyList<String>(), db.snapshotDao().getBudgets().map { it.id })
    }

    @Test
    fun `an IOException on budgets returns Error`() = runTest {
        api.budgets = { throw IOException("offline") }

        val result = repository.refreshSnapshot()

        assertTrue(result is SnapshotResult.Error)
        assertEquals("offline", (result as SnapshotResult.Error).message)
    }

    @Test
    fun `refresh replaces the previous snapshot`() = runTest {
        api.budgets = { budgetsOk(budget("b1", "USD")) }
        api.accountsByBudget = { accountsOk(account("a1")) }
        repository.refreshSnapshot()

        api.budgets = { budgetsOk(budget("b2", "INR")) }
        api.accountsByBudget = { accountsOk(account("a2")) }
        repository.refreshSnapshot()

        assertEquals(listOf("b2"), db.snapshotDao().getBudgets().map { it.id })
        assertEquals(emptyList<String>(), db.snapshotDao().getOpenAccounts("b1").map { it.id })
    }
}
