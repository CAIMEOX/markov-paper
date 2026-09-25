package com.caimeo.markovpaper.assemblage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class StructureLibraryTest {
    @Test
    fun `catalog scope selects village assets from manifest tags`() {
        val plainsHouse = asset(
            id = "minecraft:village/plains/houses/plains_small_house_1",
            size = Extent3i(7, 7, 7),
        )
        val desertHouse = asset(
            id = "minecraft:village/desert/houses/desert_small_house_1",
            size = Extent3i(6, 6, 5),
        )
        val shipwreck = asset(
            id = "minecraft:shipwreck/with_mast",
            size = Extent3i(9, 21, 28),
        )
        val source = InMemoryStructureSource(plainsHouse, desertHouse, shipwreck)
        val manifest = StructureManifest.parse(
            revision = CatalogRevision("test"),
            text = """
                minecraft:village/plains/houses/plains_small_house_1|family:village,biome:plains
                minecraft:village/desert/houses/desert_small_house_1|family:village,biome:desert
                minecraft:shipwreck/with_mast|family:shipwreck
            """.trimIndent(),
        )
        val catalog = StructureLibrary(manifest, source).open(
            AssetScope(requiredTags = setOf(AssetTag("family:village")))
        )

        assertEquals(
            listOf(plainsHouse.id, desertHouse.id),
            catalog.assets.map(AssetSummary::id),
        )
        assertEquals(
            listOf(plainsHouse.id),
            catalog.select(
                StructureQuery(requiredTags = setOf(AssetTag("biome:plains")))
            ).map(AssetSummary::id),
        )
        val loaded = assertIs<StructureLoadResult.Loaded>(catalog.load(plainsHouse.id)).asset
        assertEquals(plainsHouse.id, loaded.id)
        assertEquals(
            setOf(AssetTag("family:village"), AssetTag("biome:plains")),
            loaded.tags,
        )
        assertIs<StructureLoadResult.NotIndexed>(catalog.load(shipwreck.id))
    }

    @Test
    fun `authored air structure void and unspecified cells remain distinct`() {
        val palette = AuthoredPalette(
            mapOf(
                Vec3i(0, 0, 0) to AuthoredCell.Block(
                    BlockStateSpec("minecraft:stone")
                ),
                Vec3i(1, 0, 0) to AuthoredCell.AuthoredAir,
                Vec3i(2, 0, 0) to AuthoredCell.StructureVoid,
            )
        )

        assertIs<AuthoredCell.Block>(palette[Vec3i(0, 0, 0)])
        assertEquals(AuthoredCell.AuthoredAir, palette[Vec3i(1, 0, 0)])
        assertEquals(AuthoredCell.StructureVoid, palette[Vec3i(2, 0, 0)])
        assertNull(palette[Vec3i(3, 0, 0)])
    }

    @Test
    fun `catalog snapshot loads each immutable asset only once`() {
        val asset = asset("minecraft:village/test", Extent3i(2, 2, 2))
        var loads = 0
        val source = StructureAssetSource {
            loads++
            StructureLoadResult.Loaded(asset)
        }
        val manifest = StructureManifest.parse(
            CatalogRevision("test"),
            "minecraft:village/test|family:village",
        )
        val catalog = StructureLibrary(manifest, source).open()

        assertIs<StructureLoadResult.Loaded>(catalog.load(asset.id))
        assertIs<StructureLoadResult.Loaded>(catalog.load(asset.id))

        assertEquals(1, loads)
    }

    @Test
    fun `catalog resolves a unique path suffix to its canonical Asset id`() {
        val grass = asset("minecraft:village/decays/grass_16x16", Extent3i(16, 1, 16))
        val temple = asset(
            "minecraft:village/taiga/houses/taiga_temple_1",
            Extent3i(13, 14, 11),
        )
        val catalog = StructureLibrary(
            StructureManifest.parse(
                CatalogRevision("test"),
                """
                    ${grass.id}|family:village
                    ${temple.id}|family:village
                """.trimIndent(),
            ),
            InMemoryStructureSource(grass, temple),
        ).open()

        val resolution = assertIs<AssetReferenceResolution.Resolved>(
            catalog.resolve("grass_16x16")
        )

        assertEquals(grass.id, resolution.reference.id)
        assertEquals("grass_16x16", resolution.reference.shortest)
    }

    @Test
    fun `ambiguous suffix returns the shortest unique candidates without choosing`() {
        val normal = asset(
            "minecraft:village/taiga/houses/taiga_temple_1",
            Extent3i(13, 14, 11),
        )
        val zombie = asset(
            "minecraft:village/taiga/zombie/houses/taiga_temple_1",
            Extent3i(13, 14, 11),
        )
        val catalog = StructureLibrary(
            StructureManifest.parse(
                CatalogRevision("test"),
                """
                    ${normal.id}|family:village
                    ${zombie.id}|family:village
                """.trimIndent(),
            ),
            InMemoryStructureSource(normal, zombie),
        ).open()

        val resolution = assertIs<AssetReferenceResolution.Ambiguous>(
            catalog.resolve("taiga_temple_1")
        )

        assertEquals(
            listOf(
                AssetReference(normal.id, "taiga/houses/taiga_temple_1"),
                AssetReference(zombie.id, "zombie/houses/taiga_temple_1"),
            ),
            resolution.candidates,
        )
    }

    private fun asset(id: String, size: Extent3i): StructureAsset = StructureAsset(
        id = AssetId(id),
        size = size,
        palettes = listOf(AuthoredPalette(emptyMap())),
        tags = emptySet(),
    )

    private class InMemoryStructureSource(
        vararg assets: StructureAsset,
    ) : StructureAssetSource {
        private val assets = assets.associateBy(StructureAsset::id)

        override fun load(id: AssetId): StructureLoadResult =
            assets[id]?.let(StructureLoadResult::Loaded)
                ?: StructureLoadResult.Missing(id)
    }
}
