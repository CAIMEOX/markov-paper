package com.caimeo.markovpaper.paper

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DebugWorldOwnershipTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `existing world directory without sidecar marker is rejected`() {
        val worldDirectory = temporaryDirectory.resolve("markov-asset-debug")
        Files.createDirectories(worldDirectory)

        assertFailsWith<IllegalArgumentException> {
            DebugWorldOwnership.requireOwnedOrAbsent(worldDirectory)
        }

        DebugWorldOwnership.markOwned(worldDirectory)
        DebugWorldOwnership.requireOwnedOrAbsent(worldDirectory)
        assertTrue(Files.isRegularFile(worldDirectory.resolve(DebugWorldOwnership.MARKER_FILE)))
    }

    @Test
    fun `paper custom world lives below the primary world dimension directory`() {
        val primaryWorld = temporaryDirectory.resolve("smoke-world")
        val paperOverworld = primaryWorld.resolve("dimensions/minecraft/overworld")
        val expected = primaryWorld.resolve("dimensions/minecraft/markov-asset-debug")

        assertEquals(expected, DebugWorldOwnership.dimensionDirectory(primaryWorld, "markov-asset-debug"))
        assertEquals(expected, DebugWorldOwnership.dimensionDirectory(paperOverworld, "markov-asset-debug"))
    }
}
