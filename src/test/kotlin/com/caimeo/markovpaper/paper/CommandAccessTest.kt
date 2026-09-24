package com.caimeo.markovpaper.paper

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommandAccessTest {
    @Test
    fun `preview access never authorizes materialization or world administration`() {
        fun allowed(command: String, permissions: Set<String>) = MarkovCommandAccess.allows(
            command.split(' ').toTypedArray(), permissions::contains,
        )
        val preview = setOf("markov-paper.use", "markov-paper.preview")
        for (command in listOf("preview", "asset preview", "assemblage animate", "hybrid trace", "workbench open", "stop")) {
            assertTrue(allowed(command, preview), command)
        }
        val writes = listOf("materialize", "assemblage materialize", "hybrid materialize", "workbench materialize")
        for (command in writes) {
            assertFalse(allowed(command, preview), command)
            assertTrue(allowed(command, preview + "markov-paper.materialize"), command)
        }
        assertFalse(allowed("debugworld rebuild", preview))
        assertTrue(allowed("debugworld rebuild", setOf("markov-paper.use", "markov-paper.admin")))
    }
}
