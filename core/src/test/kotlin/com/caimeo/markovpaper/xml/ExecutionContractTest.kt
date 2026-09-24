package com.caimeo.markovpaper.xml

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ExecutionContractTest {
    @Test
    fun `stage state retains its own coordinates and symbols independently of the animation view`() {
        val model = MarkovXmlCompiler.compile("""
            <sequence values="BR" origin="True">
              <one in="R" out="R" steps="1"/>
              <map values="BG" scale="2 1 1" symmetry="()"><rule in="R" out="GG"/></map>
            </sequence>
        """.trimIndent(), 1, 1, 1, 1)
        assertEquals(listOf('B', 'R'), model.state.symbols)
        assertEquals(1, model.state.grid.sizeX)
        assertEquals(listOf('B', 'G'), model.symbols)
        assertEquals(2, model.grid.sizeX)
        while (model.node.advance() != null) Unit
        assertEquals(model.symbols, model.state.symbols)
        assertContentEquals(model.grid.copyState(), model.state.grid.copyState())
    }

    @Test
    fun `initial state means the same thing before and after adding an identity map`() {
        for (suffix in listOf("", """<map values="BRG" scale="1 1 1"><rule in="B" out="B"/><rule in="R" out="R"/><rule in="G" out="G"/></map>""")) {
            val plan = MarkovXmlCompiler.prepare("""
                <sequence values="BRG"><one in="R" out="G" steps="1"/>$suffix</sequence>
            """.trimIndent(), 3, 1, 1)
            val initial = byteArrayOf(1, 0, 0)
            val model = plan.create(1, initialState = initial)
            initial[0] = 0
            while (model.node.advance() != null) Unit
            assertContentEquals(byteArrayOf(2, 0, 0), model.grid.copyState())
        }
    }

    @Test
    fun `completed executions stay complete with or without a map stage`() {
        for (suffix in listOf("", """<map values="BR" scale="1 1 1"><rule in="B" out="B"/><rule in="R" out="R"/></map>""")) {
            val model = MarkovXmlCompiler.compile("""
                <sequence values="BR"><one in="B" out="R" steps="1"/>$suffix</sequence>
            """.trimIndent(), 3, 1, 1, 1)
            while (model.node.advance() != null) Unit
            val final = model.grid.copyState()
            repeat(3) { assertNull(model.node.advance()) }
            assertContentEquals(final, model.grid.copyState())
        }
    }

    @Test
    fun `an unavailable observation leaves the grid untouched`() {
        val model = MarkovXmlCompiler.compile("""
            <one values="BRG" origin="True" symmetry="()">
              <rule in="G" out="B"/>
              <observe value="R" from="B" to="B"/>
              <observe value="G" to="B"/>
            </one>
        """.trimIndent(), 3, 1, 1, 1)
        val initial = model.grid.copyState()
        assertNull(model.node.advance())
        assertContentEquals(initial, model.grid.copyState())
    }

    @Test
    fun `observation normalization is emitted even when no rewrite can follow`() {
        val model = MarkovXmlCompiler.compile("""
            <one values="BRG" origin="True" symmetry="()">
              <rule in="G" out="B"/>
              <observe value="R" from="B" to="G"/>
            </one>
        """.trimIndent(), 3, 1, 1, 1)
        val replay = model.grid.copyState()
        val delta = assertNotNull(model.node.advance())
        for (change in delta.changes) {
            assertEquals(replay[change.index], change.before)
            replay[change.index] = change.after
        }
        assertContentEquals(model.grid.copyState(), replay)
        assertNull(model.node.advance())
        assertContentEquals(model.grid.copyState(), replay)
    }
}
