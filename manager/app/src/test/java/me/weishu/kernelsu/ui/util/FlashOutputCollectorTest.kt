package me.weishu.kernelsu.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.Executor

class FlashOutputCollectorTest {
    @Test
    fun preflightOutputIsAvailableBeforeUiCallbackDelivery() {
        val pending = ArrayDeque<Runnable>()
        val delivered = mutableListOf<String>()
        val output = FlashOutputCollector(Executor { pending.add(it) }, delivered::add)
        val json = "{\"schema_version\":2}"

        val reader = Thread { output.add(json) }
        reader.start()
        reader.join()

        assertEquals(listOf(json), output.toList())
        assertTrue(delivered.isEmpty())
        pending.removeFirst().run()
        assertEquals(listOf(json), delivered)
    }

    @Test
    fun installationMarkersAreAvailableWithoutDrainingUiQueue() {
        val pending = ArrayDeque<Runnable>()
        val stdout = FlashOutputCollector(Executor { pending.add(it) }) {}
        val stderr = FlashOutputCollector(Executor { pending.add(it) }) {}
        val reader = Thread {
            stdout.add("FOLKZYGISK_BATCH_CHANGED")
            stderr.add("Error: FOLKZYGISK_STATE_CHANGED: selection changed")
        }
        reader.start()
        reader.join()

        assertTrue(stdout.any { it == "FOLKZYGISK_BATCH_CHANGED" })
        assertTrue(stderr.any {
            it.trimStart().removePrefix("Error: ").startsWith("FOLKZYGISK_STATE_CHANGED:")
        })
        assertEquals(2, pending.size)
    }
}
