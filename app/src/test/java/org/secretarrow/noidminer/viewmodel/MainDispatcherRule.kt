package org.secretarrow.noidminer.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Testing-setup skill (android/skills): ViewModels use viewModelScope which
 * defaults to Dispatchers.Main. In JVM unit tests Main is unavailable, so
 * swap in an eager test dispatcher. Unconfined keeps viewModelScope
 * launches running eagerly while DataStore/IO work suspends for real —
 * assertions then poll StateFlow values with real-time timeouts.
 */
class MainDispatcherRule : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
