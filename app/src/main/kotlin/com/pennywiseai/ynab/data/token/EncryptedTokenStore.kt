package com.pennywiseai.ynab.data.token

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TokenStore backed by EncryptedSharedPreferences (AES-256, key in the Android
 * Keystore). security-crypto is deprecated but adequate for a single secret in v1
 * (design spec, Token storage). Not unit-tested — the Keystore is unavailable
 * off-device; correctness is a framework guarantee, verified by assembleDebug and
 * the on-device smoke check.
 */
@Singleton
class EncryptedTokenStore @Inject constructor(
    @ApplicationContext context: Context,
) : TokenStore {

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun getToken(): String? = prefs.getString(KEY_TOKEN, null)

    override fun setToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    private companion object {
        const val PREFS_FILE = "ynab_secure_prefs"
        const val KEY_TOKEN = "ynab_pat"
    }
}
