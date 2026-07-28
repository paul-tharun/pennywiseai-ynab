package com.pennywiseai.ynab.data.state

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SharedPrefsPostingStateStoreObserveTest {

    private val store = SharedPrefsPostingStateStore(ApplicationProvider.getApplicationContext())

    @Test
    fun `observePaused emits the current value first`() = runTest {
        store.setPaused(true)
        assertEquals(true, store.observePaused().first())
    }

    @Test
    fun `observePaused reflects a later change`() = runTest {
        store.setPaused(false)
        store.setPaused(true)
        assertEquals(true, store.observePaused().first())
    }
}
