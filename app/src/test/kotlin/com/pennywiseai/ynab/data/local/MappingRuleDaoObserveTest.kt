package com.pennywiseai.ynab.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pennywiseai.ynab.data.local.dao.MappingRuleDao
import com.pennywiseai.ynab.data.local.entity.MappingRuleEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MappingRuleDaoObserveTest {

    private lateinit var db: PennyWiseDatabase
    private lateinit var dao: MappingRuleDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PennyWiseDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.mappingRuleDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `observeAll reflects the current rows`() = runTest {
        dao.insert(
            MappingRuleEntity(bankName = "HDFC Bank", last4 = "1234", budgetId = "b1", accountId = "a1", currencyCode = "INR"),
        )
        assertEquals(listOf("1234"), dao.observeAll().first().map { it.last4 })
    }
}
