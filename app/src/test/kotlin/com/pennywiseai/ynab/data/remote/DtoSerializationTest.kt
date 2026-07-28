package com.pennywiseai.ynab.data.remote

import com.pennywiseai.ynab.core.model.SaveTransaction
import com.pennywiseai.ynab.data.remote.dto.AccountsResponse
import com.pennywiseai.ynab.data.remote.dto.BudgetsResponse
import com.pennywiseai.ynab.data.remote.dto.SaveTransactionsRequest
import com.pennywiseai.ynab.data.remote.dto.SaveTransactionsResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DtoSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `budgets response parses id name and iso currency, ignoring unknown fields`() {
        val body = """
            {"data":{"budgets":[
              {"id":"b1","name":"Personal","last_modified_on":"2026-01-01",
               "currency_format":{"iso_code":"USD","decimal_digits":2,"symbol_first":true}},
              {"id":"b2","name":"Family","currency_format":{"iso_code":"INR","decimal_digits":2}}
            ],"default_budget":null}}
        """.trimIndent()
        val parsed = json.decodeFromString<BudgetsResponse>(body)
        assertEquals(listOf("b1", "b2"), parsed.data.budgets.map { it.id })
        assertEquals("USD", parsed.data.budgets[0].currencyFormat?.isoCode)
        assertEquals("INR", parsed.data.budgets[1].currencyFormat?.isoCode)
    }

    @Test
    fun `budget with no currency_format decodes to null`() {
        val body = """{"data":{"budgets":[{"id":"b1","name":"NoCurrency"}]}}"""
        val parsed = json.decodeFromString<BudgetsResponse>(body)
        assertNull(parsed.data.budgets.single().currencyFormat)
    }

    @Test
    fun `accounts response parses closed and deleted flags`() {
        val body = """
            {"data":{"accounts":[
              {"id":"a1","name":"Checking","type":"checking","on_budget":true,"closed":false,"deleted":false,"balance":123000},
              {"id":"a2","name":"Old Card","type":"creditCard","closed":true,"deleted":false}
            ]}}
        """.trimIndent()
        val parsed = json.decodeFromString<AccountsResponse>(body)
        assertEquals(listOf("a1", "a2"), parsed.data.accounts.map { it.id })
        assertEquals(false, parsed.data.accounts[0].closed)
        assertEquals(true, parsed.data.accounts[1].closed)
    }

    @Test
    fun `save transactions request serializes to the transactions array with snake_case`() {
        val request = SaveTransactionsRequest(
            transactions = listOf(
                SaveTransaction(
                    accountId = "a1", date = "2026-07-28", amount = -100_000L,
                    payeeName = "Coffee", memo = "ref123", importId = "PW:abc",
                ),
            ),
        )
        val encoded = Json.encodeToString(request)
        // snake_case field names and the wrapping array must be present for YNAB.
        assertEquals(true, encoded.contains("\"transactions\""))
        assertEquals(true, encoded.contains("\"account_id\":\"a1\""))
        assertEquals(true, encoded.contains("\"import_id\":\"PW:abc\""))
        assertEquals(true, encoded.contains("\"amount\":-100000"))
    }

    @Test
    fun `post response parses transaction_ids and duplicate_import_ids`() {
        val body = """
            {"data":{"transaction_ids":["t1","t2"],"duplicate_import_ids":["PW:dup"],
                     "transactions":[],"server_knowledge":42}}
        """.trimIndent()
        val parsed = json.decodeFromString<SaveTransactionsResponse>(body)
        assertEquals(listOf("t1", "t2"), parsed.data.transactionIds)
        assertEquals(listOf("PW:dup"), parsed.data.duplicateImportIds)
    }

    @Test
    fun `post response with missing dedup fields defaults to empty lists`() {
        val parsed = json.decodeFromString<SaveTransactionsResponse>("""{"data":{}}""")
        assertEquals(emptyList<String>(), parsed.data.transactionIds)
        assertEquals(emptyList<String>(), parsed.data.duplicateImportIds)
    }
}
