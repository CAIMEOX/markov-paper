package com.caimeo.markovpaper.assemblage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BundledVanillaManifestTest {
    @Test
    fun `paper 26_2 manifest indexes every vanilla structure template`() {
        val catalog = VanillaStructureCatalogs.open(
            minecraftVersion = "26.2",
            source = StructureAssetSource { StructureLoadResult.Missing(it) },
        )

        assertEquals(1_212, catalog.assets.size)
        assertEquals(CatalogRevision("minecraft-26.2-structures-v1"), catalog.revision)
        assertEquals(483, catalog.select(StructureQuery(requiredTags = setOf(AssetTag("family:village")))).size)
        assertTrue(AssetId("minecraft:village/plains/houses/plains_big_house_1") in
            catalog.select(StructureQuery(requiredTags = setOf(AssetTag("path:plains"), AssetTag("path:houses")))).map(AssetSummary::id))
        assertEquals(167, catalog.select(StructureQuery(requiredTags = setOf(AssetTag("family:bastion")))).size)
        assertEquals(194, catalog.select(StructureQuery(requiredTags = setOf(AssetTag("dimension:nether")))).size)
        assertEquals(20, catalog.select(StructureQuery(requiredTags = setOf(AssetTag("dimension:end")))).size)
        assertTrue(
            catalog.resolve("bastion/bridge/starting_pieces/entrance") is
                AssetReferenceResolution.Resolved
        )
        assertTrue(
            catalog.resolve("end_city/base_floor") is AssetReferenceResolution.Resolved
        )
        assertTrue(catalog.resolve("empty") is AssetReferenceResolution.Resolved)
    }
}
