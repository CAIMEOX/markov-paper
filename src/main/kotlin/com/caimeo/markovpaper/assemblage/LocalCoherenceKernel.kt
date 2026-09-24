package com.caimeo.markovpaper.assemblage

import kotlin.math.abs

internal data class SeamBandGeometry(
    val axis: Int,
    val threshold: Int,
    val collisionPositions: Set<Vec3i>,
)

data class LocalCoherenceReport(
    val doorwayCells: Int = 0,
    val floorConnectionCells: Int = 0,
    val supportCells: Int = 0,
)

internal data class LocalCoherenceResult(
    val scene: SceneSnapshot,
    val report: LocalCoherenceReport,
)

internal data class LocalCoherenceCellMutation(
    val position: Vec3i,
    val state: BlockStateSpec,
    val kind: SceneContributionKind,
    val role: LocalCoherenceRole,
)

internal data class LocalCoherencePlan(
    val mutations: List<LocalCoherenceCellMutation>,
    val report: LocalCoherenceReport,
) {
    companion object {
        val EMPTY = LocalCoherencePlan(emptyList(), LocalCoherenceReport())
    }
}

internal data class LocalCoherenceLimits(
    val maxCollisionPositions: Int = Int.MAX_VALUE,
    val maxDoorwayCandidates: Int = Int.MAX_VALUE,
    val maxSupportDepth: Int = Int.MAX_VALUE,
) {
    init {
        require(maxCollisionPositions > 0)
        require(maxDoorwayCandidates > 0)
        require(maxSupportDepth > 0)
    }
}

internal object LocalCoherenceKernel {
    fun reconcile(scene: SceneSnapshot, seam: SeamBandGeometry): LocalCoherenceResult {
        val cells = scene.cells.toMutableMap()
        val plan = plan(scene.size, cells, seam)
        for (mutation in plan.mutations) apply(cells, mutation)
        return LocalCoherenceResult(
            scene = SceneSnapshot(scene.size, cells.toMap()),
            report = plan.report,
        )
    }

    fun plan(
        size: Extent3i,
        cells: Map<Vec3i, SceneCell>,
        seam: SeamBandGeometry,
        limits: LocalCoherenceLimits = LocalCoherenceLimits(),
    ): LocalCoherencePlan {
        val boundedCollisions = seam.collisionPositions
            .take(limits.maxCollisionPositions)
            .toCollection(LinkedHashSet())
        if (boundedCollisions.isEmpty()) return LocalCoherencePlan.EMPTY
        val boundedSeam = seam.copy(collisionPositions = boundedCollisions)
        if (boundedSeam.axis == Y_AXIS) {
            return planHorizontal(size, cells, boundedSeam, limits)
        }
        if (boundedSeam.axis !in setOf(X_AXIS, Z_AXIS)) return LocalCoherencePlan.EMPTY
        val minimumY = boundedCollisions.minOf(Vec3i::y)
        val maximumY = boundedCollisions.maxOf(Vec3i::y)
        if (maximumY - minimumY < 2) return LocalCoherencePlan.EMPTY

        val normalCoordinates = listOf(boundedSeam.threshold, boundedSeam.threshold + 1)
        val lateralValues = boundedCollisions.map { position ->
            if (boundedSeam.axis == X_AXIS) position.z else position.x
        }.distinct()
        val lateralCenter = (lateralValues.min() + lateralValues.max()) / 2
        val orderedLaterals = lateralValues.sortedWith(
            compareBy<Int>({ abs(it - lateralCenter) }, { it })
        )
        var doorway: Doorway? = null
        var doorwayCandidates = 0
        doorwaySearch@ for (floorY in minimumY until maximumY) {
            for (lateral in orderedLaterals) {
                if (doorwayCandidates >= limits.maxDoorwayCandidates) break@doorwaySearch
                doorwayCandidates++
                val positions = buildList {
                    for (normal in normalCoordinates) {
                        val lower = position(boundedSeam.axis, normal, floorY + 1, lateral)
                        val upper = position(boundedSeam.axis, normal, floorY + 2, lateral)
                        if (size.contains(lower)) add(lower)
                        if (size.contains(upper)) add(upper)
                    }
                }
                val visibleSolids = positions.count { position ->
                    cells[position]?.selected?.kind?.isSolid == true
                }
                val collisions = positions.count(boundedCollisions::contains)
                if (visibleSolids >= 2 && collisions >= 1) {
                    doorway = Doorway(floorY, lateral, positions)
                    break@doorwaySearch
                }
            }
        }
        val selectedDoorway = doorway ?: return LocalCoherencePlan.EMPTY

        val floorPositions = (boundedSeam.threshold - 1..boundedSeam.threshold + 2)
            .map { normal ->
                position(boundedSeam.axis, normal, selectedDoorway.floorY, selectedDoorway.lateral)
            }
            .filter(size::contains)
        val floorState = dominantSolidState(cells, floorPositions)
            ?: dominantSolidState(cells, boundedCollisions)
            ?: return LocalCoherencePlan.EMPTY
        val floor = floorPositions.map { position ->
            LocalCoherenceCellMutation(
                position,
                floorState,
                SceneContributionKind.INFERRED_BLOCK,
                LocalCoherenceRole.FLOOR_CONNECTION,
            )
        }
        val supports = supportMutations(
            cells = cells,
            anchors = listOfNotNull(
                floorPositions.firstOrNull(),
                floorPositions.lastOrNull(),
            ).distinct(),
            startY = selectedDoorway.floorY - 1,
            state = floorState,
            maxDepth = limits.maxSupportDepth,
        )
        val air = BlockStateSpec("minecraft:air")
        val openings = selectedDoorway.positions.map { position ->
            LocalCoherenceCellMutation(
                position,
                air,
                SceneContributionKind.INFERRED_AIR,
                LocalCoherenceRole.DOORWAY,
            )
        }
        return LocalCoherencePlan(
            mutations = floor + supports + openings,
            report = LocalCoherenceReport(
                doorwayCells = openings.size,
                floorConnectionCells = floor.size,
                supportCells = supports.size,
            ),
        )
    }

    fun apply(
        cells: MutableMap<Vec3i, SceneCell>,
        mutation: LocalCoherenceCellMutation,
    ): SceneCell? {
        val previous = cells[mutation.position]
        val contribution = SceneContribution(
            provenance = SceneProvenance.LocalCoherence(mutation.role),
            kind = mutation.kind,
            state = mutation.state,
        )
        cells[mutation.position] = SceneCell(
            state = mutation.state,
            selected = contribution,
            contributions = previous?.contributions.orEmpty() + contribution,
        )
        return previous
    }

    private fun planHorizontal(
        size: Extent3i,
        cells: Map<Vec3i, SceneCell>,
        seam: SeamBandGeometry,
        limits: LocalCoherenceLimits,
    ): LocalCoherencePlan {
        val centerX = (
            seam.collisionPositions.minOf(Vec3i::x) +
                seam.collisionPositions.maxOf(Vec3i::x)
            ) / 2
        val centerZ = (
            seam.collisionPositions.minOf(Vec3i::z) +
                seam.collisionPositions.maxOf(Vec3i::z)
            ) / 2
        val floorPositions = buildList {
            for (z in centerZ - 1..centerZ + 1) {
                for (x in centerX - 1..centerX + 1) {
                    val position = Vec3i(x, seam.threshold, z)
                    if (size.contains(position)) add(position)
                }
            }
        }
        val floorState = dominantSolidState(cells, seam.collisionPositions)
            ?: return LocalCoherencePlan.EMPTY
        val floor = floorPositions.map { position ->
            LocalCoherenceCellMutation(
                position,
                floorState,
                SceneContributionKind.INFERRED_BLOCK,
                LocalCoherenceRole.FLOOR_CONNECTION,
            )
        }
        val supportAnchors = floorPositions.filter { position ->
            (position.x == centerX - 1 || position.x == centerX + 1) &&
                (position.z == centerZ - 1 || position.z == centerZ + 1)
        }
        val supports = supportMutations(
            cells = cells,
            anchors = supportAnchors,
            startY = seam.threshold - 1,
            state = floorState,
            maxDepth = limits.maxSupportDepth,
        )
        return LocalCoherencePlan(
            mutations = floor + supports,
            report = LocalCoherenceReport(
                floorConnectionCells = floor.size,
                supportCells = supports.size,
            ),
        )
    }

    private fun position(axis: Int, normal: Int, y: Int, lateral: Int): Vec3i =
        if (axis == X_AXIS) Vec3i(normal, y, lateral) else Vec3i(lateral, y, normal)

    private fun dominantSolidState(
        cells: Map<Vec3i, SceneCell>,
        positions: Collection<Vec3i>,
    ): BlockStateSpec? = positions.mapNotNull { position ->
        cells[position]?.selected?.takeIf { it.kind.isSolid }?.state
    }.groupingBy(BlockStateSpec::canonical).eachCount().entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .firstOrNull()?.key?.let(::BlockStateSpec)

    private fun supportMutations(
        cells: Map<Vec3i, SceneCell>,
        anchors: Collection<Vec3i>,
        startY: Int,
        state: BlockStateSpec,
        maxDepth: Int,
    ): List<LocalCoherenceCellMutation> = buildList {
        for (anchor in anchors) {
            var depth = 0
            for (y in startY downTo 0) {
                if (depth >= maxDepth) break
                depth++
                val support = Vec3i(anchor.x, y, anchor.z)
                if (cells[support]?.selected?.kind?.isSolid == true) break
                add(
                    LocalCoherenceCellMutation(
                        support,
                        state,
                        SceneContributionKind.INFERRED_BLOCK,
                        LocalCoherenceRole.SUPPORT,
                    )
                )
            }
        }
    }

    private data class Doorway(
        val floorY: Int,
        val lateral: Int,
        val positions: List<Vec3i>,
    )

    private const val X_AXIS = 0
    private const val Y_AXIS = 1
    private const val Z_AXIS = 2
}
