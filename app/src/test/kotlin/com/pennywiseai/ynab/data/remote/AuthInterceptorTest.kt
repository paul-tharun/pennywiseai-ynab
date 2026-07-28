package com.pennywiseai.ynab.data.remote

import com.pennywiseai.ynab.data.token.FakeTokenStore
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AuthInterceptorTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun clientWith(token: String?): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(FakeTokenStore(token)))
            .build()

    private fun call(client: OkHttpClient) {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        client.newCall(Request.Builder().url(server.url("/v1/budgets")).build())
            .execute().close()
    }

    @Test
    fun `attaches bearer header when a token is present`() {
        call(clientWith("secret-pat"))
        val recorded = server.takeRequest()
        assertEquals("Bearer secret-pat", recorded.getHeader("Authorization"))
    }

    @Test
    fun `omits the header when no token is set`() {
        call(clientWith(null))
        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("Authorization"))
    }

    @Test
    fun `omits the header when the token is blank`() {
        call(clientWith("   "))
        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("Authorization"))
    }
}
