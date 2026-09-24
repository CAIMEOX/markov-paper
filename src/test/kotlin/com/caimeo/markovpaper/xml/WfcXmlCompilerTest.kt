package com.caimeo.markovpaper.xml

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class WfcXmlCompilerTest {
    @Test
    fun `root wfc is independent of the registered model name and resources`() {
        val plan = MarkovXmlCompiler.prepare(
            """<wfc values="BVL" tileset="Partition" overlap="-3" shannon="True" tries="100"/>""",
            2, 3, 1,
        )
        assertEquals(GridSize(9, 15, 3), plan.size)
        val model = plan.create(22)
        var done = false
        repeat(200) { if (model.node.advance() == null) done = true }
        assertTrue(done)
        assertTrue(model.grid.copyState().any { it != 0.toByte() })
    }

    @Test
    fun `grid-local unions do not leak across a map and animation clears previous frames`() {
        val model = MarkovXmlCompiler.compile("""
            <sequence values="BR" origin="True" symmetry="()">
              <union symbol="." values="R"/>
              <map scale="2 1 1" values="BG" symmetry="()">
                <union symbol="." values="G"/>
                <rule in="." out="GG"/>
                <all in="." out="B"/>
              </map>
            </sequence>
        """.trimIndent(), 1, 1, 1, 0)
        assertNotNull(model.node.advance())
        assertContentEquals(byteArrayOf(1, 1), model.grid.copyState())
        val erased = requireNotNull(model.node.advance())
        assertEquals(2, erased.changes.size)
        assertTrue(erased.changes.all { it.after == 0.toByte() })
        assertContentEquals(byteArrayOf(0, 0), model.grid.copyState())
    }

    @Test
    fun `invalid wfc configuration fails explicitly during preparation`() {
        assertFailsWith<IllegalArgumentException> {
            MarkovXmlCompiler.prepare("""<wfc values="BW" sample="test"/>""", 3, 3, 1)
        }
        assertFailsWith<IllegalArgumentException> {
            MarkovXmlCompiler.prepare("""
                <sequence values="B"><wfc tileset="Partition" values="BVL">
                  <rule in="B" out="Misspelled"/>
                </wfc></sequence>
            """.trimIndent(), 2, 2, 1)
        }
        assertFailsWith<IllegalArgumentException> {
            MarkovXmlCompiler.prepare("""
                <sequence values="B"><map scale="2000000 2000000 1" values="B"/></sequence>
            """.trimIndent(), 10, 10, 1)
        }
    }

    @Test
    fun `nested wfc map and postprocessing run through the normal compiler`() {
        val model = MarkovXmlCompiler.compile(
            """
            <markov values="BN" origin="True" symmetry="()">
              <all in="B" out="N"/>
              <wfc tileset="Partition" values="BVL" tries="100">
                <rule in="N" out="I"/>
                <map scale="1 1 2" values="BRS" symmetry="()">
                  <rule in="V" out="R R"/>
                  <rule in="L" out="R R"/>
                  <all in="R" out="S"/>
                </map>
              </wfc>
            </markov>
            """.trimIndent(), 1, 1, 1, 17,
        )
        val replay = model.grid.copyState()
        var visible = 0
        var finished = false
        for (step in 0 until 1_000) {
            val delta = model.node.advance()
            if (delta == null) { finished = true; break }
            if (delta.changes.isNotEmpty()) visible++
            for (change in delta.changes) {
                assertEquals(replay[change.index], change.before)
                replay[change.index] = change.after
            }
            assertContentEquals(model.grid.copyState(), replay, "frame $step")
        }
        assertTrue(finished)
        assertTrue(visible > 1)
        assertEquals(listOf('B', 'R', 'S'), model.symbols)
        assertEquals(6, model.grid.sizeZ)
        assertTrue(replay.any { it == model.valueOf('S') })
        assertTrue(replay.none { it == model.valueOf('R') })
    }
}
