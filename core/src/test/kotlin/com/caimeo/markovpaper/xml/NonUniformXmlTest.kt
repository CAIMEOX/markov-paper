package com.caimeo.markovpaper.xml

import com.caimeo.markovpaper.engine.WfcInitialContradictionException
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class NonUniformXmlTest {
    @TempDir lateinit var directory: Path

    private fun tiles(xml: String): ModelResources {
        Files.createDirectories(directory.resolve("tilesets"))
        Files.writeString(directory.resolve("tilesets/Test.xml"), xml)
        return ModelResources(directory)
    }

    @Test
    fun `L modules interlock through their unowned corners without fragmenting in any animation frame`() {
        val resources = tiles("""
            <tileset nonUniform="True"><tiles>
              <tile name="L" voxels="CD/EB" mask="XX/X." rotations="z"/>
            </tiles><neighbors><neighbor left="L" right="L"/></neighbors></tileset>
        """.trimIndent())
        for (seed in 0L..7L) {
            val model = MarkovXmlCompiler.compile("""<wfc values="BCDE" tileset="Test"/>""", 3, 2, 1, seed, resources)
            val replay = model.grid.copyState()
            var done = false
            for (step in 0 until 500) {
                val delta = model.node.advance() ?: run { done = true; break }
                for (change in delta.changes) {
                    assertEquals(replay[change.index], change.before)
                    replay[change.index] = change.after
                }
                assertContentEquals(model.grid.copyState(), replay)
                // One logical collapse reveals complete Ls, not independently painted fragments.
                assertEquals(replay.count { it == 1.toByte() }, replay.count { it == 2.toByte() })
                assertEquals(replay.count { it == 1.toByte() }, replay.count { it == 3.toByte() })
            }
            assertTrue(done)
            assertEquals(2, replay.count { it == 1.toByte() })
            assertFalse(replay.contains(0))
        }
    }

    @Test
    fun `explicit air occupies module space even with coarser rectangular atoms`() {
        val resources = tiles("""
            <tileset nonUniform="True" atomSize="2 1 1"><tiles>
              <tile name="Hollow" voxels="BBCC"/>
              <tile name="Solid" voxels="CC" weight="100"/>
            </tiles><neighbors><neighbor left="*" right="*" directions="x"/></neighbors></tileset>
        """.trimIndent())
        val model = MarkovXmlCompiler.compile("""
            <sequence values="BW" origin="True"><wfc values="BC" tileset="Test">
              <rule in="W" out="Hollow"/>
            </wfc></sequence>
        """.trimIndent(), 3, 1, 1, 7, resources)
        var done = false
        for (step in 0 until 100) if (model.node.advance() == null) { done = true; break }
        assertTrue(done)
        assertEquals(6, model.grid.sizeX)
        val cells = model.grid.copyState().toList()
        assertTrue(cells == listOf<Byte>(0, 0, 1, 1, 1, 1) || cells == listOf<Byte>(1, 1, 0, 0, 1, 1))
    }

    @Test
    fun `same material atoms remain distinct and vertical modules cannot be clipped at edges`() {
        val resources = tiles("""
            <tileset nonUniform="True"><tiles><tile name="Pillar" voxels="C C"/></tiles>
              <neighbors><neighbor bottom="Pillar" top="Pillar"/></neighbors>
            </tileset>
        """.trimIndent())
        val xml = """<wfc values="BC" tileset="Test"/>"""
        val even = MarkovXmlCompiler.compile(xml, 1, 1, 4, 0, resources)
        repeat(50) { even.node.advance() }
        assertContentEquals(byteArrayOf(1, 1, 1, 1), even.grid.copyState())
        val odd = MarkovXmlCompiler.compile(xml, 1, 1, 3, 0, resources)
        assertFailsWith<WfcInitialContradictionException> { odd.node.advance() }
        assertFailsWith<IllegalArgumentException> {
            MarkovXmlCompiler.prepare("""<wfc values="BC" tileset="Test" periodic="True"/>""", 1, 1, 4, resources)
        }
    }
}
