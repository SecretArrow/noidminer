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
 * HealthCheckWorker should never crash even when:
 *  - the miner is idle (running=false) → no-op success
 *  - the foreground service is missing but state says running → restart attempt
 *  - ActivityManager.getRunningServices returns empty (modern Android)
 *
 * We only assert Result.success() — full restart semantics are exercised in
 * the e2e instrumented test, not here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class HealthCheckWorkerTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // WorkManager must be initialized for TestListenableWorkerBuilder.
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
    fun `idle miner returns success without crashing`() = runBlocking {
        val worker = TestListenableWorkerBuilder<HealthCheckWorker>(
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
