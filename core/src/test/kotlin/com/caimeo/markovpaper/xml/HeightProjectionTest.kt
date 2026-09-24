package com.caimeo.markovpaper.xml

import kotlin.test.*

class HeightProjectionTest {
    @Test
    fun `column profiles preserve air floor ceiling and animated wall conversion`() {
        val source = MarkovXmlCompiler.prepare("""<one values="AFW" in="F" out="W"/>""", 3, 1, 1)
        val plan = HeightProjection.prepare(source, 6, "BSL".toList(), mapOf('A' to "B", 'F' to "SBBL", 'W' to "S"), 100)
        val model = plan.create(7, byteArrayOf(0, 1, 2))
        assertEquals(1, model.state.grid.sizeZ)
        assertEquals(source.symbols, model.state.symbols)
        fun column(x: Int) = List(6) { model.symbols[model.grid[x, 0, it].toInt()] }.joinToString("")
        assertEquals("BBBBBB", column(0))
        assertEquals("SBBBBL", column(1))
        assertEquals("SSSSSS", column(2))
        val replay = model.grid.copyState()
        val delta = requireNotNull(model.node.advance())
        assertTrue(delta.changes.all { it.x == 1 })
        delta.changes.forEach { replay[it.index] = it.after }
        assertEquals("SSSSSS", column(1))
        assertContentEquals(model.grid.copyState(), replay)
        assertContentEquals(byteArrayOf(0, 2, 2), model.state.grid.copyState())
        assertNull(model.node.advance())
    }

    @Test
    fun `invalid projection alphabet and dimensions fail before runtime allocation`() {
        val source = MarkovXmlCompiler.prepare("""<one values="AF" in="A" out="F"/>""", 3, 1, 1)
        for (columns in listOf(mapOf('A' to "B"), mapOf('A' to "BB", 'F' to "S"), mapOf('A' to "B", 'F' to "X"))) {
            assertFailsWith<IllegalArgumentException> { HeightProjection.prepare(source, 6, "BS".toList(), columns, 100) }
        }
        assertFailsWith<IllegalArgumentException> {
            HeightProjection.prepare(source, 100, "BS".toList(), mapOf('A' to "B", 'F' to "S"), 100)
        }
    }
}
