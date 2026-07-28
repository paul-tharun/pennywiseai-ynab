package com.pennywiseai.ynab.data.remote

import com.pennywiseai.ynab.core.model.SaveTransaction
import com.pennywiseai.ynab.data.remote.dto.SaveTransactionsRequest
import com.pennywiseai.ynab.data.token.FakeTokenStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class YnabApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: YnabApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(FakeTokenStore("pat")))
            .build()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(YnabApi::class.java)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `getBudgets hits v1 budgets and parses the body`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"data":{"budgets":[{"id":"b1","name":"Personal","currency_format":{"iso_code":"USD"}}]}}""",
            ),
        )
        val response = api.getBudgets()
        assertTrue(response.isSuccessful)
        assertEquals("USD", response.body()!!.data.budgets.single().currencyFormat?.isoCode)
        assertEquals("/v1/budgets", server.takeRequest().path)
    }

    @Test
    fun `getAccounts interpolates the budget id into the path`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"data":{"accounts":[{"id":"a1","name":"Checking","closed":false,"deleted":false}]}}""",
            ),
        )
        val response = api.getAccounts("b1")
        assertEquals("a1", response.body()!!.data.accounts.single().id)
        assertEquals("/v1/budgets/b1/accounts", server.takeRequest().path)
    }

    @Test
    fun `postTransactions sends the transactions array and parses dedup fields`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"data":{"transaction_ids":["t1"],"duplicate_import_ids":["PW:dup"]}}""",
            ),
        )
        val request = SaveTransactionsRequest(
            transactions = listOf(
                SaveTransaction(accountId = "a1", date = "2026-07-28", amount = -100_000L, importId = "PW:abc"),
            ),
        )
        val response = api.postTransactions("b1", request)

        assertEquals(listOf("t1"), response.body()!!.data.transactionIds)
        assertEquals(listOf("PW:dup"), response.body()!!.data.duplicateImportIds)

        val recorded = server.takeRequest()
        assertEquals("/v1/budgets/b1/transactions", recorded.path)
        val sentBody = recorded.body.readUtf8()
        assertTrue(sentBody.contains("\"transactions\""))
        assertTrue(sentBody.contains("\"import_id\":\"PW:abc\""))
        assertTrue(sentBody.contains("\"account_id\":\"a1\""))
    }

    @Test
    fun `a 401 is surfaced as an unsuccessful response, not an exception`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"id":"401"}}"""))
        val response = api.getBudgets()
        assertEquals(false, response.isSuccessful)
        assertEquals(401, response.code())
    }
}
