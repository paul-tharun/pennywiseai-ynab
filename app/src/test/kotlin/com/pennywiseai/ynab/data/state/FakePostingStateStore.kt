package com.pennywiseai.ynab.data.state

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory PostingStateStore for tests. Reused by the pipeline and repository tests. */
class FakePostingStateStore(initial: Boolean = false) : PostingStateStore {
    private val paused = MutableStateFlow(initial)
    override fun isPaused(): Boolean = paused.value
    override fun setPaused(paused: Boolean) { this.paused.value = paused }
    override fun observePaused(): Flow<Boolean> = paused
}
