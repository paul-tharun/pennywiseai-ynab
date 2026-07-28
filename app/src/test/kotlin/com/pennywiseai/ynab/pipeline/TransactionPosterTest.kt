package com.pennywiseai.ynab.pipeline

import com.pennywiseai.ynab.core.model.SaveTransaction
import com.pennywiseai.ynab.data.remote.FakeYnabApi
import com.pennywiseai.ynab.data.remote.dto.SaveTransactionsData
import com.pennywiseai.ynab.data.remote.dto.SaveTransactionsResponse
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class TransactionPosterTest {

    private val api = FakeYnabApi()
    private val poster = YnabTransactionPoster(api)

    private fun txn(importId: String = "PW:abc") = SaveTransaction(
        accountId = "a1", date = "2026-07-28", amount = -100_000L, importId = importId,
    )

    private fun errorBody() = "{}".toResponseBody("application/json".toMediaType())

    @Test
    fun `2xx single element is Posted`() = runTest {
        api.postResponder = { _, _ ->
            Response.success(SaveTransactionsResponse(SaveTransactionsData(transactionIds = listOf("t1"))))
        }
        assertEquals(PostOutcome.Posted, poster.post("b1", listOf(txn())))
    }

    @Test
    fun `2xx bulk with a duplicate import id is still Posted`() = runTest {
        // A duplicate import_id is a successful post (ADR-0005), so the whole 2xx batch is Posted.
        api.postResponder = { _, _ ->
            Response.success(
                SaveTransactionsResponse(
                    SaveTransactionsData(transactionIds = listOf("t1"), duplicateImportIds = listOf("PW:dup")),
                ),
            )
        }
        assertEquals(PostOutcome.Posted, poster.post("b1", listOf(txn("PW:new"), txn("PW:dup"))))
    }

    @Test
    fun `401 is Unauthorized`() = runTest {
        api.postResponder = { _, _ -> Response.error(401, errorBody()) }
        assertEquals(PostOutcome.Unauthorized, poster.post("b1", listOf(txn())))
    }

    @Test
    fun `404 signals a broken route`() = runTest {
        api.postResponder = { _, _ -> Response.error(404, errorBody()) }
        assertEquals(PostOutcome.RouteBroken, poster.post("b1", listOf(txn())))
    }

    @Test
    fun `400 is a terminal failure`() = runTest {
        api.postResponder = { _, _ -> Response.error(400, errorBody()) }
        val outcome = poster.post("b1", listOf(txn())) as PostOutcome.Failed
        assertEquals(false, outcome.retryable)
    }

    @Test
    fun `429 is a retryable failure`() = runTest {
        api.postResponder = { _, _ -> Response.error(429, errorBody()) }
        assertTrue((poster.post("b1", listOf(txn())) as PostOutcome.Failed).retryable)
    }

    @Test
    fun `5xx is a retryable failure`() = runTest {
        api.postResponder = { _, _ -> Response.error(503, errorBody()) }
        assertTrue((poster.post("b1", listOf(txn())) as PostOutcome.Failed).retryable)
    }

    @Test
    fun `IOException offline is a retryable failure`() = runTest {
        api.postResponder = { _, _ -> throw IOException("no network") }
        assertTrue((poster.post("b1", listOf(txn())) as PostOutcome.Failed).retryable)
    }
}
