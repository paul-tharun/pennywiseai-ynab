package com.pennywiseai.ynab.data.state

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
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

    @Test
    fun `observePaused re-emits when the value changes after subscribing`() = runTest {
        store.setPaused(false)
        val emissions = mutableListOf<Boolean>()
        // UnconfinedTestDispatcher starts the collector eagerly, so the SharedPreferences
        // listener is registered and the initial value emitted before we mutate below.
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            store.observePaused().take(2).toList(emissions)
        }

        // Trigger the change listener (Robolectric invokes it synchronously on apply()).
        store.setPaused(true)
        advanceUntilIdle()
        job.join()

        // Proves the immediate value AND the listener-driven re-emission.
        assertEquals(listOf(false, true), emissions)
    }
}
