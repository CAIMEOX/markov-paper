package com.caimeo.markovpaper.xml

import com.caimeo.markovpaper.minecraft.ModelCatalog
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackroomsHeightModelsTest {
    @TempDir lateinit var directory: Path
    @Test
    fun `runtime compiler extrudes backrooms to the requested height`() {
        val catalog = ModelCatalog(directory).apply { installBundled() }
        val model = catalog.prepare("backrooms2d", 41, 7).execution.create(17)
        var frames = 0
        while (frames < 5_000 && model.node.advance() != null) frames++
        val air = model.valueOf('B')
        val sandstone = model.valueOf('S')
        val light = model.valueOf('L')
        val state = model.grid.copyState()
        val planeSize = 41 * 41

        assertTrue(frames < 5_000, "projected backrooms did not terminate")
        assertEquals(41, model.grid.sizeX)
        assertEquals(41, model.grid.sizeY)
        assertEquals(7, model.grid.sizeZ)
        assertEquals(listOf('B', 'S', 'L'), model.symbols)
        assertEquals(planeSize, state.countAtLevel(0, planeSize, sandstone))
        assertEquals(0, (0 until 6).sumOf { level -> state.countAtLevel(level, planeSize, light) })
        assertEquals(8, state.countAtLevel(6, planeSize, light))
        assertEquals(0, state.countAtLevel(6, planeSize, air))
        assertTrue(hasColumn(model) { column -> column.all { it == sandstone } })
        assertTrue(
            hasColumn(model) { column ->
                column == listOf(sandstone, air, air, air, sandstone, sandstone, sandstone)
            },
        )
    }

    @Test
    fun `backrooms symbols project into rooms walls lintels and ceiling lights`() {
        val symbols = "BFWHVLDRQ".toList()
        val catalog = ModelCatalog(directory).apply { installBundled() }
        Files.writeString(directory.resolve("columns.xml"), """<one values="BFWHVLDRQ" in="B" out="F"/>""")
        Files.writeString(directory.resolve("columns.properties"),
            Files.readString(directory.resolve("backrooms2d.properties"))
                .replace("min-size=15", "min-size=1").replace("input=size size 1", "input=size 1 1"))
        val projected = catalog.prepare("columns", symbols.size, 6).execution.create(7, ByteArray(symbols.size) { it.toByte() })
        val air = projected.valueOf('B')
        val sandstone = projected.valueOf('S')
        val light = projected.valueOf('L')

        assertEquals(6, projected.grid.sizeZ)
        assertEquals(List(6) { air }, projected.column(symbols, 'B'))
        assertEquals(listOf(sandstone, air, air, air, air, sandstone), projected.column(symbols, 'F'))
        assertEquals(List(6) { sandstone }, projected.column(symbols, 'W'))
        assertEquals(List(6) { sandstone }, projected.column(symbols, 'H'))
        assertEquals(List(6) { sandstone }, projected.column(symbols, 'V'))
        assertEquals(listOf(sandstone, air, air, sandstone, sandstone, sandstone), projected.column(symbols, 'D'))
        assertEquals(listOf(sandstone, air, air, air, air, light), projected.column(symbols, 'L'))
        assertEquals(listOf(sandstone, air, air, air, air, sandstone), projected.column(symbols, 'R'))
        assertEquals(listOf(sandstone, air, air, sandstone, sandstone, sandstone), projected.column(symbols, 'Q'))
    }

    private fun CompiledMarkovModel.column(sourceSymbols: List<Char>, symbol: Char): List<Byte> {
        val x = sourceSymbols.indexOf(symbol)
        return List(grid.sizeZ) { level -> grid[x, 0, level] }
    }

    private fun ByteArray.countAtLevel(level: Int, planeSize: Int, value: Byte): Int =
        indices.count { index -> index / planeSize == level && this[index] == value }

    private fun hasColumn(
        model: CompiledMarkovModel,
        predicate: (List<Byte>) -> Boolean,
    ): Boolean = (0 until model.grid.sizeY).any { y ->
        (0 until model.grid.sizeX).any { x ->
            predicate(List(model.grid.sizeZ) { level -> model.grid[x, y, level] })
        }
    }
}
