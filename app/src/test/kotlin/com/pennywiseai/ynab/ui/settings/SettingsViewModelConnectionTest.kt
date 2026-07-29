package com.pennywiseai.ynab.ui.settings

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pennywiseai.ynab.data.local.PennyWiseDatabase
import com.pennywiseai.ynab.data.local.entity.AccountEntity
import com.pennywiseai.ynab.data.local.entity.BudgetEntity
import com.pennywiseai.ynab.data.remote.FakeYnabApi
import com.pennywiseai.ynab.data.remote.dto.AccountDto
import com.pennywiseai.ynab.data.remote.dto.AccountsData
import com.pennywiseai.ynab.data.remote.dto.AccountsResponse
import com.pennywiseai.ynab.data.remote.dto.BudgetDto
import com.pennywiseai.ynab.data.remote.dto.BudgetsData
import com.pennywiseai.ynab.data.remote.dto.BudgetsResponse
import com.pennywiseai.ynab.data.remote.dto.CurrencyFormatDto
import com.pennywiseai.ynab.data.repository.YnabRepository
import com.pennywiseai.ynab.data.state.FakePostingStateStore
import com.pennywiseai.ynab.data.token.FakeTokenStore
import com.pennywiseai.ynab.ui.rules.BackfillEnqueuer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
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
import retrofit2.Response

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelConnectionTest {

    private lateinit var db: PennyWiseDatabase
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), PennyWiseDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() { db.close(); Dispatchers.resetMain() }

    private fun vm() = SettingsViewModel(
        repository = YnabRepository(
            FakeYnabApi(), db.snapshotDao(), db.mappingRuleDao(), FakeTokenStore(), FakePostingStateStore(),
        ),
        tokenStore = FakeTokenStore(),
        postingState = FakePostingStateStore(),
        mappingRuleDao = db.mappingRuleDao(),
        processedMessageDao = db.processedMessageDao(),
        enqueuer = BackfillEnqueuer { _, _ -> },
        snapshotDao = db.snapshotDao(),
    )

    @Test
    fun `loadConnection reports snapshot budget and account counts`() = runTest(dispatcher) {
        db.snapshotDao().replaceSnapshot(
            budgets = listOf(
                BudgetEntity(id = "b1", name = "Personal", currencyCode = "INR"),
                BudgetEntity(id = "b2", name = "Business", currencyCode = "USD"),
            ),
            accounts = listOf(
                AccountEntity(id = "a1", budgetId = "b1", name = "Everyday", closed = false, deleted = false),
                AccountEntity(id = "a2", budgetId = "b1", name = "Savings", closed = false, deleted = false),
                AccountEntity(id = "a3", budgetId = "b2", name = "Checking", closed = false, deleted = false),
            ),
        )
        val vm = vm()
        vm.loadConnection()

        val info = vm.connection.first { it != null }!!
        assertEquals(2, info.budgetCount)
        assertEquals(3, info.accountCount)
    }

    @Test
    fun `refresh reloads connection counts from the newly-pulled snapshot`() = runTest(dispatcher) {
        // Seed a small initial snapshot directly (simulating a prior connect) and confirm the
        // connected row reads it.
        db.snapshotDao().replaceSnapshot(
            budgets = listOf(BudgetEntity(id = "b1", name = "Personal", currencyCode = "INR")),
            accounts = listOf(
                AccountEntity(id = "a1", budgetId = "b1", name = "Everyday", closed = false, deleted = false),
            ),
        )

        val api = FakeYnabApi()
        val repository = YnabRepository(
            api, db.snapshotDao(), db.mappingRuleDao(), FakeTokenStore(), FakePostingStateStore(),
        )
        val vm = SettingsViewModel(
            repository = repository,
            tokenStore = FakeTokenStore(),
            postingState = FakePostingStateStore(),
            mappingRuleDao = db.mappingRuleDao(),
            processedMessageDao = db.processedMessageDao(),
            enqueuer = BackfillEnqueuer { _, _ -> },
            snapshotDao = db.snapshotDao(),
        )
        vm.loadConnection()
        assertEquals(1, vm.connection.first { it != null }!!.budgetCount)

        // The API now reports a bigger snapshot, as if a second budget/accounts were added on
        // YNAB's side since the last connect.
        api.budgets = {
            Response.success(
                BudgetsResponse(
                    BudgetsData(
                        listOf(
                            BudgetDto(id = "b1", name = "Personal", currencyFormat = CurrencyFormatDto("INR")),
                            BudgetDto(id = "b2", name = "Business", currencyFormat = CurrencyFormatDto("USD")),
                        ),
                    ),
                ),
            )
        }
        api.accountsByBudget = { budgetId ->
            val accounts = if (budgetId == "b1") {
                listOf(AccountDto(id = "a1", name = "Everyday", closed = false, deleted = false))
            } else {
                listOf(
                    AccountDto(id = "a2", name = "Checking", closed = false, deleted = false),
                    AccountDto(id = "a3", name = "Savings", closed = false, deleted = false),
                )
            }
            Response.success(AccountsResponse(AccountsData(accounts)))
        }

        vm.refresh()

        // refresh() hops onto the real Dispatchers.IO, so advanceUntilIdle() on the virtual test
        // dispatcher can't be relied on to observe completion — await the updated value like the
        // test above, with a predicate that skips the stale (budgetCount == 1) value.
        val info = vm.connection.first { it != null && it.budgetCount == 2 }!!
        assertEquals(2, info.budgetCount)
        assertEquals(3, info.accountCount)
    }

    @Test
    fun `saveToken reloads connection counts from the newly-saved snapshot`() = runTest(dispatcher) {
        val api = FakeYnabApi()
        api.budgets = {
            Response.success(
                BudgetsResponse(
                    BudgetsData(
                        listOf(
                            BudgetDto(id = "b1", name = "Personal", currencyFormat = CurrencyFormatDto("INR")),
                            BudgetDto(id = "b2", name = "Business", currencyFormat = CurrencyFormatDto("USD")),
                        ),
                    ),
                ),
            )
        }
        api.accountsByBudget = { budgetId ->
            val accounts = if (budgetId == "b1") {
                listOf(AccountDto(id = "a1", name = "Everyday", closed = false, deleted = false))
            } else {
                listOf(
                    AccountDto(id = "a2", name = "Checking", closed = false, deleted = false),
                    AccountDto(id = "a3", name = "Savings", closed = false, deleted = false),
                )
            }
            Response.success(AccountsResponse(AccountsData(accounts)))
        }
        val repository = YnabRepository(
            api, db.snapshotDao(), db.mappingRuleDao(), FakeTokenStore(), FakePostingStateStore(),
        )
        val vm = SettingsViewModel(
            repository = repository,
            tokenStore = FakeTokenStore(),
            postingState = FakePostingStateStore(),
            mappingRuleDao = db.mappingRuleDao(),
            processedMessageDao = db.processedMessageDao(),
            enqueuer = BackfillEnqueuer { _, _ -> },
            snapshotDao = db.snapshotDao(),
        )

        vm.saveToken("test-token")

        // saveToken hops onto the real Dispatchers.IO too; await the reloaded value the same
        // way the refresh test does, rather than relying on advanceUntilIdle().
        val info = vm.connection.first { it != null && it.budgetCount == 2 }!!
        assertEquals(2, info.budgetCount)
        assertEquals(3, info.accountCount)
    }
}
