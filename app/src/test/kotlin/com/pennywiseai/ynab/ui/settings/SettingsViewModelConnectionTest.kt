package com.pennywiseai.ynab.ui.settings

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pennywiseai.ynab.data.local.PennyWiseDatabase
import com.pennywiseai.ynab.data.local.entity.AccountEntity
import com.pennywiseai.ynab.data.local.entity.BudgetEntity
import com.pennywiseai.ynab.data.remote.FakeYnabApi
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
}
