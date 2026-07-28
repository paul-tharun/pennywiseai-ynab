package com.pennywiseai.ynab.data.state

import kotlinx.coroutines.flow.Flow

/**
 * The persistent `postingPaused` flag (design spec, Error handling). Set when the
 * pipeline hits a 401 or finds no token — while paused the pipeline records FAILED
 * without touching the network, so a bad/absent token can't trigger a 401 storm.
 * Cleared when a token save validates successfully (YnabRepository, Task 6). Kept
 * an interface so the pipeline/repository depend on an abstraction and tests use an
 * in-memory fake. The flag is not a secret, so the impl uses plain SharedPreferences.
 */
interface PostingStateStore {
    /** True while posting is paused pending a valid token. */
    fun isPaused(): Boolean

    /** Pause (true) or resume (false) posting. */
    fun setPaused(paused: Boolean)

    /** Emits the current paused flag immediately, then every subsequent change. */
    fun observePaused(): Flow<Boolean>
}
