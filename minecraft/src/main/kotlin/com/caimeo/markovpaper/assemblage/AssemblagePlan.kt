package com.caimeo.markovpaper.assemblage

enum class AttachmentDirection {
    WEST,
    EAST,
    DOWN,
    UP,
    NORTH,
    SOUTH;

    val opposite: AttachmentDirection
        get() = when (this) {
            WEST -> EAST
            EAST -> WEST
            DOWN -> UP
            UP -> DOWN
            NORTH -> SOUTH
            SOUTH -> NORTH
        }
}

data class IntBox3i(
    val minimum: Vec3i,
    val maximumExclusive: Vec3i,
) {
    init {
        require(maximumExclusive.x > minimum.x)
        require(maximumExclusive.y > minimum.y)
        require(maximumExclusive.z > minimum.z)
    }

    val size: Extent3i = Extent3i(
        maximumExclusive.x - minimum.x,
        maximumExclusive.y - minimum.y,
        maximumExclusive.z - minimum.z,
    )

    val volume: Long = size.x.toLong() * size.y * size.z

    fun intersectionVolume(other: IntBox3i): Long {
        val x = minOf(maximumExclusive.x, other.maximumExclusive.x) -
            maxOf(minimum.x, other.minimum.x)
        val y = minOf(maximumExclusive.y, other.maximumExclusive.y) -
            maxOf(minimum.y, other.minimum.y)
        val z = minOf(maximumExclusive.z, other.maximumExclusive.z) -
            maxOf(minimum.z, other.minimum.z)
        if (x <= 0 || y <= 0 || z <= 0) return 0
        return x.toLong() * y * z
    }

}

data class PlannedStructureInstance(
    val id: SourceInstanceId,
    val source: OrientedStructureAsset,
    val origin: Vec3i,
) {
    val bounds: IntBox3i = IntBox3i(
        minimum = origin,
        maximumExclusive = Vec3i(
            Math.addExact(origin.x, source.asset.size.x),
            Math.addExact(origin.y, source.asset.size.y),
            Math.addExact(origin.z, source.asset.size.z),
        ),
    )
}

data class PlannedAttachment(
    val parent: SourceInstanceId,
    val child: SourceInstanceId,
    val direction: AttachmentDirection,
    val overlapDepth: Int,
) {
    init {
        require(parent != child)
        require(overlapDepth > 0)
    }
}

data class AssemblagePlan(
    val instances: List<PlannedStructureInstance>,
    val attachments: List<PlannedAttachment>,
    val candidateEvaluations: Int,
    val solidContactProbes: Int,
    val closedFrontiers: Int,
) {
    init {
        require(instances.isNotEmpty()) { "An Assemblage Plan needs a root instance" }
        require(instances.map { it.id }.distinct().size == instances.size) {
            "Assemblage Plan instance ids must be unique"
        }
        val ids = instances.mapTo(HashSet()) { it.id }
        require(attachments.all { it.parent in ids && it.child in ids }) {
            "Assemblage Plan attachment references an unknown instance"
        }
        require(attachments.map { it.child }.distinct().size == attachments.size) {
            "An Assemblage Plan instance may have only one parent"
        }
        require(attachments.map { it.child }.toSet() == instances.drop(1).map { it.id }.toSet()) {
            "Every non-root Assemblage Plan instance needs one parent"
        }
        val indexById = instances.mapIndexed { index, instance -> instance.id to index }.toMap()
        require(attachments.all { attachment ->
            indexById.getValue(attachment.parent) < indexById.getValue(attachment.child)
        }) {
            "Assemblage Plan instances must follow rooted parent-before-child order"
        }
        require(candidateEvaluations >= 0 && solidContactProbes >= 0 && closedFrontiers >= 0)
    }

    val bounds: IntBox3i = IntBox3i(
        minimum = Vec3i(
            instances.minOf { it.bounds.minimum.x },
            instances.minOf { it.bounds.minimum.y },
            instances.minOf { it.bounds.minimum.z },
        ),
        maximumExclusive = Vec3i(
            instances.maxOf { it.bounds.maximumExclusive.x },
            instances.maxOf { it.bounds.maximumExclusive.y },
            instances.maxOf { it.bounds.maximumExclusive.z },
        ),
    )
}
