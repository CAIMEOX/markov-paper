package com.caimeo.markovpaper.assemblage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class StructureImportTest {
    @Test
    fun `normalizer preserves structural facts without inferring building semantics`() {
        val imported = ImportedStructure(
            size = Extent3i(5, 4, 3),
            palettes = listOf(
                ImportedPalette(
                    listOf(
                        ImportedCell(
                            Vec3i(0, 0, 0),
                            BlockStateSpec("minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]"),
                            ImportedCellKind.BLOCK,
                        ),
                        ImportedCell(
                            Vec3i(1, 0, 0),
                            BlockStateSpec("minecraft:air"),
                            ImportedCellKind.AUTHORED_AIR,
                        ),
                        ImportedCell(
                            Vec3i(2, 0, 0),
                            BlockStateSpec("minecraft:structure_void"),
                            ImportedCellKind.STRUCTURE_VOID,
                        ),
                        ImportedCell(
                            Vec3i(3, 0, 0),
                            BlockStateSpec("minecraft:jigsaw[orientation=north_up]"),
                            ImportedCellKind.JIGSAW,
                            orientation = "north_up",
                        ),
                    )
                )
            ),
        )

        val asset = assertIs<StructureLoadResult.Loaded>(
            StructureAssetNormalizer.normalize(
                AssetId("minecraft:village/test_piece"),
                imported,
            )
        ).asset

        assertEquals(Extent3i(5, 4, 3), asset.size)
        assertEquals(
            AuthoredCell.Block(
                BlockStateSpec("minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]")
            ),
            asset.palettes.single()[Vec3i(0, 0, 0)],
        )
        assertEquals(AuthoredCell.AuthoredAir, asset.palettes.single()[Vec3i(1, 0, 0)])
        assertEquals(AuthoredCell.StructureVoid, asset.palettes.single()[Vec3i(2, 0, 0)])
        assertEquals(
            AuthoredCell.Control(
                kind = ControlKind.JIGSAW,
                state = BlockStateSpec("minecraft:jigsaw[orientation=north_up]"),
                orientation = "north_up",
            ),
            asset.palettes.single()[Vec3i(3, 0, 0)],
        )
        assertNull(asset.palettes.single()[Vec3i(4, 0, 0)])
    }

    @Test
    fun `asset scene includes exact authored cells but not transparent cells`() {
        val asset = StructureAsset(
            id = AssetId("minecraft:village/test_piece"),
            size = Extent3i(4, 1, 1),
            palettes = listOf(
                AuthoredPalette(
                    mapOf(
                        Vec3i(0, 0, 0) to AuthoredCell.Block(BlockStateSpec("minecraft:stone")),
                        Vec3i(1, 0, 0) to AuthoredCell.AuthoredAir,
                        Vec3i(2, 0, 0) to AuthoredCell.StructureVoid,
                        Vec3i(3, 0, 0) to AuthoredCell.Control(
                            ControlKind.JIGSAW,
                            BlockStateSpec("minecraft:jigsaw[orientation=east_up]"),
                            "east_up",
                        ),
                    )
                )
            ),
            tags = emptySet(),
        )

        val scene = StructureAssetScenes.preview(asset)

        assertEquals(Extent3i(4, 1, 1), scene.size)
        assertEquals(BlockStateSpec("minecraft:stone"), scene.cells[Vec3i(0, 0, 0)]?.state)
        assertEquals(BlockStateSpec("minecraft:air"), scene.cells[Vec3i(1, 0, 0)]?.state)
        assertNull(scene.cells[Vec3i(2, 0, 0)])
        assertEquals(
            BlockStateSpec("minecraft:jigsaw[orientation=east_up]"),
            scene.cells[Vec3i(3, 0, 0)]?.state,
        )
        assertEquals(setOf(asset.id), scene.cells[Vec3i(0, 0, 0)]?.sources)
    }
}
