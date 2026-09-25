package com.caimeo.markovpaper.minecraft

import com.caimeo.markovpaper.assemblage.BlockStateSpec
import com.caimeo.markovpaper.xml.GridSize
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ModelCatalogTest {
    @TempDir lateinit var directory: Path
    private fun installed() = ModelCatalog(directory).apply { installBundled() }

    @Test
    fun `model XML and profiles cannot resolve outside the model directory`() {
        val modelDirectory = Files.createDirectory(directory.resolve("models"))
        val catalog = ModelCatalog(modelDirectory)
        val external = directory.resolve("outside.xml")
        Files.writeString(external, """<one values="BW" in="B" out="W"/>""")
        Files.createSymbolicLink(modelDirectory.resolve("escape.xml"), external)
        assertFailsWith<IllegalArgumentException> { catalog.prepare("escape", 3) }
        Files.copy(external, modelDirectory.resolve("local.xml"))
        Files.createSymbolicLink(modelDirectory.resolve("local.properties"), external)
        assertFailsWith<IllegalArgumentException> { catalog.prepare("local", 3) }
        assertFalse(catalog.contains("../outside"))
    }

    @Test
    fun `bundled models prepare from metadata with unchanged bounds and materials`() {
        val catalog = installed()
        val plans = catalog.names().associateWith { catalog.prepare(it, catalog.profile(it).defaultSize) }
        assertEquals(ModelCatalog.bundledNames.toSet(), plans.keys)
        assertEquals(GridSize(95, 95, 24), plans.getValue("sea-villa").size)
        assertEquals(GridSize(51, 51, 24), plans.getValue("modern-house").size)
        assertEquals(GridSize(48, 48, 72), plans.getValue("carma-tower").size)
        assertEquals(GridSize(41, 41, 6), plans.getValue("backrooms2d").size)
        assertEquals(4, catalog.profile("backrooms2d").rewrites)
        assertFalse(plans.getValue("fill").modelZIsUp)
        assertEquals(setOf("apartemazements", "stairs3d"), catalog.names().filter { catalog.profile(it).hybrid }.toSet())
        for ((name, expected) in mapOf(
            "apartemazements" to mapOf('R' to "gray_stained_glass"),
            "nystrom-dungeon2d" to mapOf('D' to "stone_bricks", 'F' to "smooth_stone", 'P' to "gold_block"),
            "dungeon-growth2d" to mapOf('D' to "stone_bricks", 'F' to "smooth_stone"),
            "carma-tower" to mapOf('L' to "smooth_quartz", 'D' to "polished_deepslate", 'W' to "gray_stained_glass", 'Y' to "yellow_stained_glass", 'a' to "white_concrete", 'l' to "gray_concrete"),
            "sea-villa" to mapOf('U' to "water", 'y' to "water", 'g' to "oak_leaves[persistent=true]", 'E' to "oak_planks", 'a' to "smooth_sandstone", 'o' to "stone_bricks"),
            "modern-house" to mapOf('U' to "gray_stained_glass", 'H' to "gray_stained_glass", 'N' to "oak_planks", 'g' to "oak_leaves[persistent=true]", 'O' to "quartz_pillar", 'l' to "smooth_quartz", 'd' to "gray_concrete"),
            "backrooms2d" to mapOf('S' to "smooth_sandstone", 'L' to "sea_lantern"),
            "fill" to mapOf('B' to "black_concrete", 'W' to "white_concrete"),
        )) {
            val plan = plans.getValue(name)
            for ((symbol, block) in expected) assertEquals(BlockStateSpec("minecraft:$block"),
                plan.palette[plan.execution.symbols.indexOf(symbol).toByte()], "$name/$symbol")
        }
    }

    @Test
    fun `renamed models keep their profiles and installation never overwrites edits`() {
        val catalog = installed()
        Files.copy(directory.resolve("backrooms2d.xml"), directory.resolve("my-rooms.xml"))
        Files.copy(directory.resolve("backrooms2d.properties"), directory.resolve("my-rooms.properties"))
        val original = catalog.prepare("backrooms2d", 15, 7)
        val renamed = catalog.prepare("my-rooms", 15, 7)
        assertEquals(original.size, renamed.size)
        assertEquals(original.palette, renamed.palette)
        assertTrue(renamed.modelZIsUp)
        val a = original.execution.create(7)
        val b = renamed.execution.create(7)
        repeat(30) {
            assertEquals(a.node.advance(), b.node.advance())
            assertContentEquals(a.grid.copyState(), b.grid.copyState())
        }
        Files.writeString(directory.resolve("fill.properties"), "default-size=3\nup=z\nblock.B=minecraft:gold_block\n")
        val modifiedXml = """<one values="BX" in="B" out="X"/>"""
        Files.writeString(directory.resolve("fill.xml"), modifiedXml)
        catalog.installBundled()
        assertEquals(modifiedXml, Files.readString(directory.resolve("fill.xml")))
        val modified = catalog.prepare("fill", catalog.profile("fill").defaultSize)
        assertEquals(GridSize(3, 3, 3), modified.size)
        assertTrue(modified.modelZIsUp)
        assertEquals(BlockStateSpec("minecraft:gold_block"), modified.palette[0])
    }

    @Test
    fun `invalid profile and projection requests fail during preparation`() {
        val catalog = installed()
        assertFailsWith<IllegalArgumentException> { catalog.prepare("backrooms2d", 14) }
        assertFailsWith<IllegalArgumentException> { catalog.prepare("backrooms2d", 15, 4) }
        assertFailsWith<IllegalArgumentException> { catalog.prepare("modern-house", 10) }
        assertFailsWith<IllegalArgumentException> { catalog.prepare("maze", 3, 6) }
        assertFailsWith<IllegalArgumentException> { catalog.prepare("apartemazements", 2) }
        assertFailsWith<IllegalArgumentException> { catalog.prepare("backrooms2d", 1000, 6) }
        for (text in listOf("defaut-size=5", "up=x", "rewrites=0", "input=size size -1", "hybrid=maybe", "height=6", "up=z\nup=y")) {
            Files.writeString(directory.resolve("fill.properties"), text)
            assertFailsWith<IllegalArgumentException>(text) { catalog.prepare("fill", 3) }
        }
        Files.writeString(directory.resolve("fill.properties"), "input=size size 1\nheight=6\noutput-symbols=BS\ncolumn.B=B\n")
        assertFailsWith<IllegalArgumentException> { catalog.prepare("fill", 3) }
    }

    @Test
    fun `custom wfc bounds and runtime share the same prepared definition`() {
        val catalog = ModelCatalog(directory)
        Files.writeString(directory.resolve("custom.xml"), """
            <sequence values="B"><wfc values="BVL" tileset="Partition" overlap="-3">
              <map values="BW" scale="1 1 2"><rule in="V" out="W W"/></map>
            </wfc></sequence>
        """.trimIndent())
        val plan = catalog.prepare("custom", 4)
        assertEquals(GridSize(21, 21, 24), plan.size)
        val runtime = plan.execution.create(7)
        assertEquals(plan.size, GridSize(runtime.grid.sizeX, runtime.grid.sizeY, runtime.grid.sizeZ))
    }
}
