package org.secretarrow.noidminer.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.secretarrow.noidminer.di.ServiceLocator
import org.secretarrow.noidminer.pow.LogLine

/**
 * Testing-setup skill (android/skills): unit tests for the log console
 * merge logic — engine log + service log converge into one capped feed.
 */
class LogViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `service log lines flow into the merged feed`() {
        val vm = LogViewModel()
        ServiceLocator.emitServiceLog("info", "hello-console-1")
        ServiceLocator.emitServiceLog("error", "hello-console-2")

        awaitTrue {
            vm.lines.value.any { it.message == "hello-console-1" } &&
                vm.lines.value.any { it.message == "hello-console-2" }
        }
    }

    @Test
    fun `feed is capped at 400 lines`() {
        val vm = LogViewModel()
        repeat(450) { i ->
            ServiceLocator.emitServiceLog("debug", "line-$i")
        }
        awaitTrue { vm.lines.value.size == 400 }
        // Newest entries survive the cap.
        assertTrue(vm.lines.value.any { it.message == "line-449" })
        // Oldest entries are trimmed.
        assertEquals(false, vm.lines.value.any { it.message == "line-10" })
    }

    @Test
    fun `log lines have unique sequence numbers for LazyColumn keys`() {
        val a = LogLine(1, "info", "x")
        val b = LogLine(1, "info", "x") // same ts+content, must still be unique
        assertTrue(a.seq != b.seq)
    }
}
