package com.pennywiseai.ynab.data.state

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * PostingStateStore backed by plain SharedPreferences. The pause flag is a boolean
 * app-state signal, not a secret, so it is deliberately NOT encrypted (unlike the
 * PAT). Defaults to not-paused so a fresh install posts normally.
 */
@Singleton
class SharedPrefsPostingStateStore @Inject constructor(
    @ApplicationContext context: Context,
) : PostingStateStore {

    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    override fun isPaused(): Boolean = prefs.getBoolean(KEY_PAUSED, false)

    override fun setPaused(paused: Boolean) {
        prefs.edit().putBoolean(KEY_PAUSED, paused).apply()
    }

    override fun observePaused(): Flow<Boolean> = callbackFlow {
        trySend(isPaused())
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_PAUSED) trySend(isPaused())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private companion object {
        const val PREFS_FILE = "posting_state"
        const val KEY_PAUSED = "posting_paused"
    }
}
