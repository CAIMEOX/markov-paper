package com.caimeo.markovpaper.paper

import com.caimeo.markovpaper.assemblage.*
import com.caimeo.markovpaper.scene.VoxelGridSceneProgram
import com.caimeo.markovpaper.xml.MarkovXmlCompiler
import kotlin.test.*

class PreviewSourceTest {
    @Test
    fun `voxel and scene program playback preserve the same y-up result`() {
        val palette = mapOf(0.toByte() to BlockStateSpec("minecraft:air"), 1.toByte() to BlockStateSpec("minecraft:stone"))
        val prepared = MarkovXmlCompiler.prepare("""<all values="BW" in="B" out="W"/>""", 2, 3, 4)
        for (asProgram in listOf(false, true)) {
            val model = prepared.create(7, ByteArray(24).apply { this[23] = 1 })
            val source: PreviewSource = if (asProgram) ProgramPreviewSource(VoxelGridSceneProgram(
                "test", 7, model.grid, model.node, model.symbols, palette, true,
            )) else VoxelPreviewSource(model.grid, model.node, palette, true)
            assertEquals(Extent3i(2, 4, 3), source.initialSize)
            val replay = source.initialCells().associateTo(linkedMapOf()) { it.position to it.state }
            assertEquals(Vec3i(1, 3, 2), replay.filterValues { it == palette[1] }.keys.single())
            while (true) {
                val frame = source.advance() ?: break
                frame.cells.forEach { replay[it.position] = it.state }
            }
            val final = source.finish().cells.associate { it.position to it.state }
            assertEquals(24, final.size)
            assertTrue(final.values.all { it == palette[1] })
            assertEquals(final, replay)
        }
    }

    @Test
    fun `authored air is committed but unowned scene coordinates remain untouched`() {
        val asset = StructureAsset(
            AssetId("test:sparse"), Extent3i(3, 1, 1),
            listOf(AuthoredPalette(mapOf(
                Vec3i(0, 0, 0) to AuthoredCell.Block(BlockStateSpec("minecraft:stone")),
                Vec3i(1, 0, 0) to AuthoredCell.AuthoredAir,
                Vec3i(2, 0, 0) to AuthoredCell.StructureVoid,
            ))), emptySet(),
        )
        val source = StaticPreviewSource(StructureAssetScenes.preview(asset))
        assertNull(source.advance())
        val final = source.finish().cells.toList()
        assertEquals(source.initialCells().toList(), final)
        assertEquals(listOf(
            PreviewCell(Vec3i(0, 0, 0), BlockStateSpec("minecraft:stone")),
            PreviewCell(Vec3i(1, 0, 0), BlockStateSpec("minecraft:air")),
        ), final)
    }
}
