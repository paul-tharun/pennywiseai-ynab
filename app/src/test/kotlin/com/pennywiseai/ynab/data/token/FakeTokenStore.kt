package com.pennywiseai.ynab.data.token

/** In-memory TokenStore for tests (no Keystore). Reused across remote/repository tests. */
class FakeTokenStore(initial: String? = null) : TokenStore {
    private var token: String? = initial
    override fun getToken(): String? = token
    override fun setToken(token: String) { this.token = token }
    override fun clear() { token = null }
}
