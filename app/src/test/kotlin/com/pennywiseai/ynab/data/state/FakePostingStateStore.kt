package com.pennywiseai.ynab.data.state

/** In-memory PostingStateStore for tests. Reused by the pipeline and repository tests. */
class FakePostingStateStore(initial: Boolean = false) : PostingStateStore {
    private var paused = initial
    override fun isPaused(): Boolean = paused
    override fun setPaused(paused: Boolean) { this.paused = paused }
}
