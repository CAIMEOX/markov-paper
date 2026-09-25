package com.caimeo.markovpaper.assemblage

import kotlin.test.Test
import kotlin.test.assertFailsWith

class AssemblagePlanInvariantTest {
    @Test
    fun `instance order must be a rooted attachment topology`() {
        val asset = StructureAsset(
            id = AssetId("test:node"),
            size = Extent3i(1, 1, 1),
            palettes = listOf(
                AuthoredPalette(
                    mapOf(
                        Vec3i(0, 0, 0) to
                            AuthoredCell.Block(BlockStateSpec("minecraft:stone"))
                    )
                )
            ),
            tags = emptySet(),
        )
        val source = OrientedStructureAsset(HorizontalRotation.NONE, asset)
        val root = PlannedStructureInstance(SourceInstanceId("root"), source, Vec3i(0, 0, 0))
        val first = PlannedStructureInstance(SourceInstanceId("first"), source, Vec3i(1, 0, 0))
        val second = PlannedStructureInstance(SourceInstanceId("second"), source, Vec3i(2, 0, 0))

        assertFailsWith<IllegalArgumentException> {
            AssemblagePlan(
                instances = listOf(root, first, second),
                attachments = listOf(
                    PlannedAttachment(first.id, second.id, AttachmentDirection.EAST, 1),
                    PlannedAttachment(second.id, first.id, AttachmentDirection.WEST, 1),
                ),
                candidateEvaluations = 2,
                solidContactProbes = 2,
                closedFrontiers = 0,
            )
        }
    }
}
