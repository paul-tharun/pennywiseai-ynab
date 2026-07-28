package com.pennywiseai.ynab.data.token

/**
 * The single seam for the YNAB Personal Access Token. The token is a secret: it
 * lives only behind this interface (encrypted at rest) and is read only by the
 * AuthInterceptor to build the Authorization header. Kept an interface so
 * consumers depend on the abstraction and tests use an in-memory fake — the
 * encrypted implementation needs the Android Keystore and is not unit-tested.
 */
interface TokenStore {
    /** The stored PAT, or null if none is set. */
    fun getToken(): String?

    /** Store (or overwrite) the PAT. */
    fun setToken(token: String)

    /** Remove the stored PAT (used when the user clears/replaces it). */
    fun clear()
}
