package org.secretarrow.noidminer.viewmodel

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real-time polling helpers for assertions that depend on asynchronous
 * writes (DataStore commits run on Dispatchers.IO, outside the test
 * dispatcher). Polls until [probe] satisfies the predicate or the timeout
 * elapses.
 */
fun awaitTrue(timeoutMs: Long = 10_000, probe: () -> Boolean) {
    val flag = AtomicBoolean(false)
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!flag.get()) {
        if (probe()) {
            flag.set(true)
            return
        }
        if (System.currentTimeMillis() > deadline) {
            throw AssertionError("condition not met within ${timeoutMs}ms")
        }
        Thread.sleep(50)
    }
}

fun <T : Any> awaitValue(timeoutMs: Long = 10_000, probe: () -> T, isValid: (T) -> Boolean): T {
    var last = probe()
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!isValid(last)) {
        if (System.currentTimeMillis() > deadline) {
            throw AssertionError("condition not met within ${timeoutMs}ms (last=$last)")
        }
        Thread.sleep(50)
        last = probe()
    }
    return last
}
