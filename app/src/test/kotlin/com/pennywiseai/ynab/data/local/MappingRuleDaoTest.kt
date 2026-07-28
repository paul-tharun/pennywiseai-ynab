package com.pennywiseai.ynab.data.local

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pennywiseai.ynab.core.MappingResolver
import com.pennywiseai.ynab.data.local.dao.MappingRuleDao
import com.pennywiseai.ynab.data.local.entity.MappingRuleEntity
import com.pennywiseai.ynab.data.mapper.toDomain
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MappingRuleDaoTest {

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

    private fun exact(last4: String = "1234", accountId: String = "a-exact") =
        MappingRuleEntity(bankName = "HDFC Bank", last4 = last4, budgetId = "b1", accountId = accountId, currencyCode = "INR")

    private fun wildcard(accountId: String = "a-wild") =
        MappingRuleEntity(bankName = "HDFC Bank", last4 = "", budgetId = "b1", accountId = accountId, currencyCode = "INR")

    @Test
    fun `insert then getAll returns the rule`() = runTest {
        dao.insert(exact())
        assertEquals(listOf("a-exact"), dao.getAll().map { it.accountId })
    }

    @Test
    fun `update changes the target account`() = runTest {
        val id = dao.insert(exact())
        dao.update(exact().copy(id = id, accountId = "a-new"))
        assertEquals("a-new", dao.getAll().single().accountId)
    }

    @Test
    fun `delete removes the rule`() = runTest {
        val id = dao.insert(exact())
        dao.delete(exact().copy(id = id))
        assertEquals(0, dao.getAll().size)
    }

    @Test
    fun `duplicate exact route is rejected by the unique index`() = runTest {
        dao.insert(exact())
        try {
            dao.insert(exact(accountId = "a-dup"))
            fail("expected SQLiteConstraintException for duplicate (bankName, last4)")
        } catch (_: SQLiteConstraintException) {
            // expected
        }
    }

    @Test
    fun `duplicate bank wildcard is rejected by the unique index`() = runTest {
        dao.insert(wildcard())
        try {
            dao.insert(wildcard(accountId = "a-dup"))
            fail("expected SQLiteConstraintException for a second bank wildcard")
        } catch (_: SQLiteConstraintException) {
            // expected — the "" sentinel makes duplicate wildcards representable-proof
        }
    }

    @Test
    fun `an exact rule and a wildcard for the same bank coexist`() = runTest {
        dao.insert(exact())
        dao.insert(wildcard())
        assertEquals(2, dao.getAll().size)
    }

    @Test
    fun `persisted rules feed the Plan 1 resolver with exact-over-wildcard precedence`() = runTest {
        dao.insert(wildcard())
        dao.insert(exact())
        val rules = dao.getAll().map { it.toDomain() } // "" -> null so the resolver's wildcard logic works
        val resolved = MappingResolver().resolve(rules, "HDFC Bank", "1234")
        assertEquals("a-exact", resolved!!.accountId)
    }

    @Test
    fun `inserted rule defaults to not broken`() = runTest {
        dao.insert(exact())
        assertEquals(false, dao.getAll().single().broken)
    }

    @Test
    fun `setBroken flips the flag for the matching route only`() = runTest {
        dao.insert(exact(last4 = "1234"))
        dao.insert(exact(last4 = "5678", accountId = "a-other"))
        dao.setBroken("HDFC Bank", "1234", true)
        val byLast4 = dao.getAll().associate { it.last4 to it.broken }
        assertEquals(true, byLast4["1234"])
        assertEquals(false, byLast4["5678"]) // untouched
    }

    @Test
    fun `setBroken can clear the flag again`() = runTest {
        dao.insert(exact(last4 = "1234"))
        dao.setBroken("HDFC Bank", "1234", true)
        dao.setBroken("HDFC Bank", "1234", false)
        assertEquals(false, dao.getAll().single().broken)
    }
}
