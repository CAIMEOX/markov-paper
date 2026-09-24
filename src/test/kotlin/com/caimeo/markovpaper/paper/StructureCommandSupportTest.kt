package com.caimeo.markovpaper.paper

import com.caimeo.markovpaper.assemblage.AssetId
import com.caimeo.markovpaper.assemblage.AssemblageReport
import com.caimeo.markovpaper.assemblage.AssemblageResult
import com.caimeo.markovpaper.assemblage.AuthoredCell
import com.caimeo.markovpaper.assemblage.AuthoredPalette
import com.caimeo.markovpaper.assemblage.BlockStateSpec
import com.caimeo.markovpaper.assemblage.ControlKind
import com.caimeo.markovpaper.assemblage.Extent3i
import com.caimeo.markovpaper.assemblage.HorizontalRotation
import com.caimeo.markovpaper.assemblage.SceneSnapshot
import com.caimeo.markovpaper.assemblage.StructureAsset
import com.caimeo.markovpaper.assemblage.Vec3i
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StructureCommandSupportTest {
    @Test
    fun `asset command defaults an unqualified id to minecraft namespace`() {
        assertEquals(
            AssetId("minecraft:village/plains/houses/plains_big_house_1"),
            parseStructureAssetId("village/plains/houses/plains_big_house_1"),
        )
        assertEquals(
            AssetId("custom:gallery/tower"),
            parseStructureAssetId("custom:gallery/tower"),
        )
    }

    @Test
    fun `asset inspection reports structural facts only`() {
        val asset = StructureAsset(
            id = AssetId("minecraft:village/test"),
            size = Extent3i(5, 4, 3),
            palettes = listOf(
                AuthoredPalette(
                    mapOf(
                        Vec3i(0, 0, 0) to AuthoredCell.Block(BlockStateSpec("minecraft:stone")),
                        Vec3i(1, 0, 0) to AuthoredCell.AuthoredAir,
                        Vec3i(2, 0, 0) to AuthoredCell.Control(
                            ControlKind.JIGSAW,
                            BlockStateSpec("minecraft:jigsaw[orientation=north_up]"),
                            "north_up",
                        ),
                    )
                )
            ),
            tags = emptySet(),
        )

        val description = describeStructureAsset(asset)

        assertTrue("size=5x4x3" in description)
        assertTrue("authored=3" in description)
        assertTrue("air=1" in description)
        assertTrue("controls=1" in description)
        assertTrue("house" !in description.lowercase())
        assertTrue("floor" !in description.lowercase())
    }

    @Test
    fun `assemblage inspection reports composition facts only`() {
        val result = AssemblageResult(
            scene = SceneSnapshot(Extent3i(8, 5, 7), emptyMap()),
            report = AssemblageReport(
                seed = 42,
                secondRotation = HorizontalRotation.CLOCKWISE_90,
                secondOffset = Vec3i(2, -1, 3),
                firstOrigin = Vec3i(0, 1, 0),
                secondOrigin = Vec3i(2, 0, 3),
                solidCollisionCount = 18,
                solidOverlapRatio = 0.3,
            ),
        )

        val description = describeAssemblageResult(
            first = AssetId("test:first"),
            second = AssetId("test:second"),
            result = result,
        )

        assertTrue("test:first + test:second" in description)
        assertTrue("seed=42" in description)
        assertTrue("rotation=CLOCKWISE_90" in description)
        assertTrue("offset=2,-1,3" in description)
        assertTrue("overlap=30.0%" in description)
        assertTrue("collisions=18" in description)
        assertTrue("house" !in description.lowercase())
        assertTrue("floor" !in description.lowercase())
    }

    @Test
    fun `sequence command separates an optional seed from Asset References`() {
        val automatic = parseAssemblageSequenceInvocation(
            tokens = listOf("snowy_temple_1", "grass_16x16", "well_bottom"),
            randomSeed = { 8401L },
        )
        val explicit = parseAssemblageSequenceInvocation(
            tokens = listOf("5104", "snowy_temple_1", "grass_16x16"),
            randomSeed = { error("Explicit seed must not request a random seed") },
        )

        assertEquals(8401L, automatic.seed)
        assertEquals(
            listOf("snowy_temple_1", "grass_16x16", "well_bottom"),
            automatic.assetReferences,
        )
        assertEquals(5104L, explicit.seed)
        assertEquals(listOf("snowy_temple_1", "grass_16x16"), explicit.assetReferences)
    }

    @Test
    fun `hybrid command separates optional seed model size and Asset References`() {
        val invocation = parseHybridInvocation(
            tokens = listOf("apartemazements", "4", "snowy_temple_1", "well_bottom"),
            randomSeed = { 91L },
        )

        assertEquals(91L, invocation.seed)
        assertEquals("apartemazements", invocation.model)
        assertEquals(4, invocation.size)
        assertEquals(listOf("snowy_temple_1", "well_bottom"), invocation.assetReferences)
    }

    @Test
    fun `workbench growth accepts a bounded count and optional seed`() {
        val automatic = parseWorkbenchGrowthInvocation(emptyList()) { 91L }
        val explicit = parseWorkbenchGrowthInvocation(listOf("100", "42")) {
            error("Explicit seed must not request a random seed")
        }

        assertEquals(WorkbenchGrowthInvocation(count = 50, seed = 91), automatic)
        assertEquals(WorkbenchGrowthInvocation(count = 100, seed = 42), explicit)
        assertFailsWith<IllegalArgumentException> {
            parseWorkbenchGrowthInvocation(listOf("101")) { 1L }
        }
    }
}
