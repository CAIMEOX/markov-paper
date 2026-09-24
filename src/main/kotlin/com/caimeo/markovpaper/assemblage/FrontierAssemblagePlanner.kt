package com.caimeo.markovpaper.assemblage

import java.util.Random
import kotlin.math.min

data class FrontierAssemblageRequest(
    val root: StructureAsset,
    val palette: List<AssemblageAddition>,
    val targetInstances: Int,
    val seed: Long,
    val maxCandidateEvaluations: Int = targetInstances * 64,
    val maxSolidContactProbes: Int = targetInstances * 4_096,
    val candidatesPerFrontier: Int = 16,
    val maxOverlapDepth: Int = 3,
    val tangentialJitter: Int = 3,
    val maxParentOverlapRatio: Double = 0.45,
    val maxNonParentOverlapRatio: Double = 0.15,
) {
    init {
        require(targetInstances in 2..100) { "Macro instance count must be in 2..100" }
        require(palette.isNotEmpty()) { "Macro growth needs a non-empty asset palette" }
        require(maxCandidateEvaluations > 0)
        require(maxSolidContactProbes > 0)
        require(candidatesPerFrontier > 0)
        require(maxOverlapDepth > 0)
        require(tangentialJitter >= 0)
        require(maxParentOverlapRatio in 0.0..1.0)
        require(maxNonParentOverlapRatio in 0.0..1.0)
    }
}

sealed interface FrontierAssemblageOutcome {
    val plan: AssemblagePlan

    data class Completed(override val plan: AssemblagePlan) : FrontierAssemblageOutcome

    data class Exhausted(
        override val plan: AssemblagePlan,
        val reason: String,
    ) : FrontierAssemblageOutcome
}

object FrontierAssemblagePlanner {
    fun plan(request: FrontierAssemblageRequest): FrontierAssemblageOutcome {
        val random = Random(request.seed)
        val instances = ArrayList<PlannedStructureInstance>(request.targetInstances)
        val attachments = ArrayList<PlannedAttachment>(request.targetInstances - 1)
        val byId = HashMap<SourceInstanceId, PlannedStructureInstance>()
        val frontiers = ArrayList<Frontier>()
        val spatial = PlanSpatialIndex()
        var evaluations = 0
        var solidContactProbes = 0
        var closedFrontiers = 0
        var solidProbeBudgetExhausted = false

        val root = PlannedStructureInstance(
            id = SourceInstanceId("macro-0"),
            source = OrientedStructureAsset(HorizontalRotation.NONE, request.root),
            origin = Vec3i(0, 0, 0),
        )
        instances += root
        byId[root.id] = root
        spatial.add(root)
        addFrontiers(frontiers, root.id, connectedFace = null)

        while (
            instances.size < request.targetInstances &&
            frontiers.isNotEmpty() &&
            evaluations < request.maxCandidateEvaluations &&
            solidContactProbes < request.maxSolidContactProbes
        ) {
            val frontier = frontiers.removeAt(random.nextInt(frontiers.size))
            val parent = requireNotNull(byId[frontier.parent])
            var accepted: PlannedStructureInstance? = null
            var acceptedOverlapDepth = 0
            val attempts = min(
                request.candidatesPerFrontier,
                request.maxCandidateEvaluations - evaluations,
            )
            for (attempt in 0 until attempts) {
                val addition = request.palette[random.nextInt(request.palette.size)]
                val source = addition.variants[random.nextInt(addition.variants.size)]
                val placement = placeAgainst(parent.bounds, source.asset.size, frontier.direction, request, random)
                evaluations++
                val candidate = PlannedStructureInstance(
                    id = SourceInstanceId("macro-${instances.size}"),
                    source = source,
                    origin = placement.origin,
                )
                val fit = fits(
                    candidate = candidate,
                    parent = parent,
                    spatial = spatial,
                    byId = byId,
                    remainingSolidContactProbes = request.maxSolidContactProbes -
                        solidContactProbes,
                    request = request,
                )
                solidContactProbes += fit.solidContactProbes
                if (fit.solidProbeBudgetExhausted) {
                    solidProbeBudgetExhausted = true
                    break
                }
                if (fit.fits) {
                    accepted = candidate
                    acceptedOverlapDepth = placement.overlapDepth
                    break
                }
            }

            if (solidProbeBudgetExhausted) break

            val child = accepted
            if (child == null) {
                closedFrontiers++
                continue
            }
            instances += child
            byId[child.id] = child
            spatial.add(child)
            attachments += PlannedAttachment(
                parent = parent.id,
                child = child.id,
                direction = frontier.direction,
                overlapDepth = acceptedOverlapDepth,
            )
            addFrontiers(frontiers, child.id, connectedFace = frontier.direction.opposite)
        }

        val plan = AssemblagePlan(
            instances = instances.toList(),
            attachments = attachments.toList(),
            candidateEvaluations = evaluations,
            solidContactProbes = solidContactProbes,
            closedFrontiers = closedFrontiers,
        )
        return if (instances.size == request.targetInstances) {
            FrontierAssemblageOutcome.Completed(plan)
        } else {
            FrontierAssemblageOutcome.Exhausted(
                plan = plan,
                reason = when {
                    solidProbeBudgetExhausted ||
                        solidContactProbes >= request.maxSolidContactProbes ->
                        "Solid contact probe budget exhausted"
                    evaluations >= request.maxCandidateEvaluations ->
                        "Candidate evaluation budget exhausted"
                    else -> "No open structural-face frontiers remain"
                },
            )
        }
    }

    private fun fits(
        candidate: PlannedStructureInstance,
        parent: PlannedStructureInstance,
        spatial: PlanSpatialIndex,
        byId: Map<SourceInstanceId, PlannedStructureInstance>,
        remainingSolidContactProbes: Int,
        request: FrontierAssemblageRequest,
    ): FitResult {
        val parentOverlap = candidate.bounds.intersectionVolume(parent.bounds)
        if (parentOverlap == 0L) return FitResult.REJECTED
        val parentRatio = parentOverlap.toDouble() / min(candidate.bounds.volume, parent.bounds.volume)
        if (parentRatio > request.maxParentOverlapRatio) return FitResult.REJECTED

        for (neighborId in spatial.nearby(candidate.bounds)) {
            if (neighborId == parent.id) continue
            val neighbor = requireNotNull(byId[neighborId])
            val overlap = candidate.bounds.intersectionVolume(neighbor.bounds)
            if (overlap == 0L) continue
            val ratio = overlap.toDouble() / min(candidate.bounds.volume, neighbor.bounds.volume)
            if (ratio > request.maxNonParentOverlapRatio) return FitResult.REJECTED
        }
        return when (val contact = solidContact(candidate, parent, remainingSolidContactProbes)) {
            is SolidContactResult.Found -> FitResult(true, contact.probes, false)
            is SolidContactResult.Missing -> FitResult(false, contact.probes, false)
            is SolidContactResult.BudgetExhausted -> FitResult(false, contact.probes, true)
        }
    }

    private fun solidContact(
        first: PlannedStructureInstance,
        second: PlannedStructureInstance,
        probeBudget: Int,
    ): SolidContactResult {
        val minimum = Vec3i(
            maxOf(first.bounds.minimum.x, second.bounds.minimum.x),
            maxOf(first.bounds.minimum.y, second.bounds.minimum.y),
            maxOf(first.bounds.minimum.z, second.bounds.minimum.z),
        )
        val maximum = Vec3i(
            minOf(first.bounds.maximumExclusive.x, second.bounds.maximumExclusive.x),
            minOf(first.bounds.maximumExclusive.y, second.bounds.maximumExclusive.y),
            minOf(first.bounds.maximumExclusive.z, second.bounds.maximumExclusive.z),
        )
        val firstCells = first.source.asset.palettes.first().cells
        val secondCells = second.source.asset.palettes.first().cells
        var probes = 0
        for (x in minimum.x until maximum.x) {
            for (y in minimum.y until maximum.y) {
                for (z in minimum.z until maximum.z) {
                    if (probes >= probeBudget) {
                        return SolidContactResult.BudgetExhausted(probes)
                    }
                    probes++
                    val firstCell = firstCells[
                        Vec3i(x - first.origin.x, y - first.origin.y, z - first.origin.z)
                    ]
                    val secondCell = secondCells[
                        Vec3i(x - second.origin.x, y - second.origin.y, z - second.origin.z)
                    ]
                    if (firstCell is AuthoredCell.Block && secondCell is AuthoredCell.Block) {
                        return SolidContactResult.Found(probes)
                    }
                }
            }
        }
        return SolidContactResult.Missing(probes)
    }

    private fun placeAgainst(
        parent: IntBox3i,
        childSize: Extent3i,
        direction: AttachmentDirection,
        request: FrontierAssemblageRequest,
        random: Random,
    ): CandidatePlacement {
        val parentNormalSize = when (direction) {
            AttachmentDirection.WEST, AttachmentDirection.EAST -> parent.size.x
            AttachmentDirection.DOWN, AttachmentDirection.UP -> parent.size.y
            AttachmentDirection.NORTH, AttachmentDirection.SOUTH -> parent.size.z
        }
        val childNormalSize = when (direction) {
            AttachmentDirection.WEST, AttachmentDirection.EAST -> childSize.x
            AttachmentDirection.DOWN, AttachmentDirection.UP -> childSize.y
            AttachmentDirection.NORTH, AttachmentDirection.SOUTH -> childSize.z
        }
        val overlapLimit = min(
            request.maxOverlapDepth,
            maxOf(1, min(parentNormalSize, childNormalSize) / 3),
        )
        val overlap = 1 + random.nextInt(overlapLimit)
        val alignedX = align(parent.minimum.x, parent.size.x, childSize.x, request.tangentialJitter, random)
        val alignedY = align(parent.minimum.y, parent.size.y, childSize.y, min(2, request.tangentialJitter), random)
        val alignedZ = align(parent.minimum.z, parent.size.z, childSize.z, request.tangentialJitter, random)
        val origin = when (direction) {
            AttachmentDirection.WEST -> Vec3i(
                parent.minimum.x - childSize.x + overlap,
                alignedY,
                alignedZ,
            )
            AttachmentDirection.EAST -> Vec3i(
                parent.maximumExclusive.x - overlap,
                alignedY,
                alignedZ,
            )
            AttachmentDirection.DOWN -> Vec3i(
                alignedX,
                parent.minimum.y - childSize.y + overlap,
                alignedZ,
            )
            AttachmentDirection.UP -> Vec3i(
                alignedX,
                parent.maximumExclusive.y - overlap,
                alignedZ,
            )
            AttachmentDirection.NORTH -> Vec3i(
                alignedX,
                alignedY,
                parent.minimum.z - childSize.z + overlap,
            )
            AttachmentDirection.SOUTH -> Vec3i(
                alignedX,
                alignedY,
                parent.maximumExclusive.z - overlap,
            )
        }
        return CandidatePlacement(origin, overlap)
    }

    private fun align(
        parentMinimum: Int,
        parentSize: Int,
        childSize: Int,
        maximumJitter: Int,
        random: Random,
    ): Int {
        val jitterLimit = min(maximumJitter, maxOf(0, min(parentSize, childSize) / 3))
        val jitter = if (jitterLimit == 0) 0 else random.nextInt(jitterLimit * 2 + 1) - jitterLimit
        return parentMinimum + (parentSize - childSize) / 2 + jitter
    }

    private fun addFrontiers(
        frontiers: MutableList<Frontier>,
        instance: SourceInstanceId,
        connectedFace: AttachmentDirection?,
    ) {
        for (direction in AttachmentDirection.entries) {
            if (direction != connectedFace) frontiers += Frontier(instance, direction)
        }
    }

    private data class Frontier(
        val parent: SourceInstanceId,
        val direction: AttachmentDirection,
    )

    private data class CandidatePlacement(
        val origin: Vec3i,
        val overlapDepth: Int,
    )

    private data class FitResult(
        val fits: Boolean,
        val solidContactProbes: Int,
        val solidProbeBudgetExhausted: Boolean,
    ) {
        companion object {
            val REJECTED = FitResult(false, 0, false)
        }
    }

    private sealed interface SolidContactResult {
        val probes: Int

        data class Found(override val probes: Int) : SolidContactResult
        data class Missing(override val probes: Int) : SolidContactResult
        data class BudgetExhausted(override val probes: Int) : SolidContactResult
    }
}

private class PlanSpatialIndex(
    private val cellSize: Int = 16,
) {
    private val cells = HashMap<SpatialCell, MutableSet<SourceInstanceId>>()

    fun add(instance: PlannedStructureInstance) {
        for (cell in cellsFor(instance.bounds)) {
            cells.getOrPut(cell, ::LinkedHashSet) += instance.id
        }
    }

    fun nearby(bounds: IntBox3i): Set<SourceInstanceId> = buildSet {
        for (cell in cellsFor(bounds)) addAll(cells[cell].orEmpty())
    }

    private fun cellsFor(bounds: IntBox3i): Sequence<SpatialCell> = sequence {
        val minimumX = Math.floorDiv(bounds.minimum.x, cellSize)
        val minimumY = Math.floorDiv(bounds.minimum.y, cellSize)
        val minimumZ = Math.floorDiv(bounds.minimum.z, cellSize)
        val maximumX = Math.floorDiv(bounds.maximumExclusive.x - 1, cellSize)
        val maximumY = Math.floorDiv(bounds.maximumExclusive.y - 1, cellSize)
        val maximumZ = Math.floorDiv(bounds.maximumExclusive.z - 1, cellSize)
        for (x in minimumX..maximumX) {
            for (y in minimumY..maximumY) {
                for (z in minimumZ..maximumZ) yield(SpatialCell(x, y, z))
            }
        }
    }

    private data class SpatialCell(val x: Int, val y: Int, val z: Int)
}
