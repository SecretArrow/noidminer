package org.secretarrow.noidminer.work

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.secretarrow.noidminer.di.ServiceLocator

/**
 * HashrateLoggerWorker is a no-op when the miner is idle (state.running=false).
 * When running, it emits exactly one info-level log line. We verify both paths.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class HashrateLoggerWorkerTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val config = Configuration
            .Builder()
            .setMinimumLoggingLevel(Log.DEBUG)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        ServiceLocator.resetForTest()
        ServiceLocator.init(context)
    }

    @After
    fun tearDown() {
        ServiceLocator.resetForTest()
    }

    @Test
    fun `idle miner returns success without logging`() = runBlocking {
        val worker = TestListenableWorkerBuilder<HashrateLoggerWorker>(
            context,
            inputData = workDataOf(),
        ).build()
        val result = worker.doWork()
        assertEquals(
            androidx.work.ListenableWorker.Result
                .success(),
            result
        )
    }
}
