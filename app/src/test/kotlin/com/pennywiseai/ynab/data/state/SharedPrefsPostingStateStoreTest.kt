package com.pennywiseai.ynab.data.state

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SharedPrefsPostingStateStoreTest {

    private lateinit var store: SharedPrefsPostingStateStore

    @Before
    fun setUp() {
        store = SharedPrefsPostingStateStore(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `defaults to not paused`() {
        assertFalse(store.isPaused())
    }

    @Test
    fun `setPaused true is read back as paused`() {
        store.setPaused(true)
        assertTrue(store.isPaused())
    }

    @Test
    fun `setPaused false clears the flag`() {
        store.setPaused(true)
        store.setPaused(false)
        assertFalse(store.isPaused())
    }

    @Test
    fun `a second store instance sees the persisted value`() {
        store.setPaused(true)
        val reopened = SharedPrefsPostingStateStore(ApplicationProvider.getApplicationContext())
        assertTrue(reopened.isPaused()) // persistence, not just in-memory
    }
}
