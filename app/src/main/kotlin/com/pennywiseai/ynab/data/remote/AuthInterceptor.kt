package com.pennywiseai.ynab.data.remote

import com.pennywiseai.ynab.data.token.TokenStore
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Attaches the YNAB PAT as `Authorization: Bearer <token>` on every outgoing
 * request. When no token is stored the header is omitted (the request will 401,
 * which the repository/pipeline handle) — the interceptor never invents a header.
 * Reads the token fresh per request so a token saved mid-session takes effect
 * immediately. The token appears only here; there is no logging interceptor that
 * could leak it (design spec, Single network destination).
 */
class AuthInterceptor(private val tokenStore: TokenStore) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokenStore.getToken()
        val request = if (token.isNullOrBlank()) {
            chain.request()
        } else {
            chain.request().newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        }
        return chain.proceed(request)
    }
}
