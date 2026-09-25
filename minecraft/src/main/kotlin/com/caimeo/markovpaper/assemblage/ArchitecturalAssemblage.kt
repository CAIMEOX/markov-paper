package com.caimeo.markovpaper.assemblage

import java.util.Random
import kotlin.math.abs

data class OrientedStructureAsset(
    val rotation: HorizontalRotation,
    val asset: StructureAsset,
)

sealed interface AssemblagePlacement {
    data class Fixed(
        val rotation: HorizontalRotation,
        val offset: Vec3i,
    ) : AssemblagePlacement

    data class Search(val targetSolidOverlap: Double = 0.30) : AssemblagePlacement {
        init {
            require(targetSolidOverlap in 0.0..1.0)
        }
    }

    data class SampledSearch(
        val targetSolidOverlap: Double = 0.30,
        val maxCandidateOffsets: Int = 256,
    ) : AssemblagePlacement {
        init {
            require(targetSolidOverlap in 0.0..1.0)
            require(maxCandidateOffsets > 0)
        }
    }
}

data class AssemblageRequest(
    val first: StructureAsset,
    val secondVariants: List<OrientedStructureAsset>,
    val placement: AssemblagePlacement = AssemblagePlacement.Search(),
    val seed: Long,
    val maxSolidPairEvaluations: Long = 2_000_000L,
) {
    init {
        require(secondVariants.isNotEmpty()) { "At least one second-source orientation is required" }
        require(secondVariants.all { it.asset.id == secondVariants.first().asset.id }) {
            "All second-source orientations must belong to the same Structure Asset"
        }
        require(maxSolidPairEvaluations > 0) { "Solid pair evaluation budget must be positive" }
    }
}

data class AssemblageAddition(
    val variants: List<OrientedStructureAsset>,
) {
    init {
        require(variants.isNotEmpty()) { "An assemblage addition needs at least one orientation" }
        require(variants.all { it.asset.id == variants.first().asset.id }) {
            "All orientations in an assemblage addition must belong to the same Structure Asset"
        }
    }
}

data class AssemblageSequenceRequest(
    val first: StructureAsset,
    val additions: List<AssemblageAddition>,
    val targetSolidOverlap: Double = 0.30,
    val seed: Long,
    val maxSolidPairEvaluations: Long = 2_000_000L,
) {
    init {
        require(additions.isNotEmpty()) { "An assemblage sequence needs at least two sources" }
        require(targetSolidOverlap in 0.0..1.0)
        require(maxSolidPairEvaluations > 0) { "Solid pair evaluation budget must be positive" }
    }
}

data class SceneAssemblageSequenceRequest(
    val first: SceneSnapshot,
    val firstSource: TraceSource,
    val additions: List<AssemblageAddition>,
    val targetSolidOverlap: Double = 0.30,
    val placement: AssemblagePlacement = AssemblagePlacement.SampledSearch(targetSolidOverlap),
    val seed: Long,
    val maxSolidPairEvaluations: Long = 2_000_000L,
) {
    init {
        require(additions.isNotEmpty()) { "An assemblage sequence needs at least two sources" }
        require(targetSolidOverlap in 0.0..1.0)
        require(maxSolidPairEvaluations > 0) { "Solid pair evaluation budget must be positive" }
    }
}

sealed interface TraceSource {
    val label: String

    data class Authored(val asset: AssetId) : TraceSource {
        override val label: String = asset.toString()
    }

    data class Procedural(
        val model: String,
        val seed: Long,
    ) : TraceSource {
        init {
            require(model.isNotBlank()) { "Trace model name cannot be blank" }
        }

        override val label: String = "$model seed=$seed"
    }
}

sealed interface AssemblageSequenceOutcome {
    data class Generated(val trace: AssemblageTrace) : AssemblageSequenceOutcome

    data class Rejected(
        val additionIndex: Int,
        val reason: String,
    ) : AssemblageSequenceOutcome
}

data class AssemblageTraceFrame(
    val introducedInstance: SourceInstanceId?,
    val introducedSource: TraceSource,
    val scene: SceneSnapshot,
    val placement: AssemblageReport?,
)

data class AssemblageTrace(
    val seed: Long,
    val frames: List<AssemblageTraceFrame>,
) {
    init {
        require(frames.isNotEmpty()) { "An assemblage trace needs at least one frame" }
    }

    val finalScene: SceneSnapshot = frames.last().scene
}

sealed interface AssemblageOutcome {
    data class Generated(val result: AssemblageResult) : AssemblageOutcome

    data class Rejected(val reason: String) : AssemblageOutcome
}

data class AssemblageResult(
    val scene: SceneSnapshot,
    val report: AssemblageReport,
)

data class AssemblageReport(
    val seed: Long,
    val secondRotation: HorizontalRotation,
    val secondOffset: Vec3i,
    val firstOrigin: Vec3i,
    val secondOrigin: Vec3i,
    val solidCollisionCount: Int,
    val solidOverlapRatio: Double,
    val localCoherence: LocalCoherenceReport = LocalCoherenceReport(),
)

object ArchitecturalAssemblage {
    fun compose(request: AssemblageSequenceRequest): AssemblageSequenceOutcome {
        val instance = SourceInstanceId("source-0")
        return composeSequence(
            first = sceneFromAsset(request.first, instance),
            firstSource = TraceSource.Authored(request.first.id),
            firstInstance = instance,
            additions = request.additions,
            targetSolidOverlap = request.targetSolidOverlap,
            placement = AssemblagePlacement.Search(request.targetSolidOverlap),
            seed = request.seed,
            maxSolidPairEvaluations = request.maxSolidPairEvaluations,
        )
    }

    fun compose(request: SceneAssemblageSequenceRequest): AssemblageSequenceOutcome =
        composeSequence(
            first = request.first,
            firstSource = request.firstSource,
            firstInstance = null,
            additions = request.additions,
            targetSolidOverlap = request.targetSolidOverlap,
            placement = request.placement,
            seed = request.seed,
            maxSolidPairEvaluations = request.maxSolidPairEvaluations,
        )

    private fun composeSequence(
        first: SceneSnapshot,
        firstSource: TraceSource,
        firstInstance: SourceInstanceId?,
        additions: List<AssemblageAddition>,
        targetSolidOverlap: Double,
        placement: AssemblagePlacement,
        seed: Long,
        maxSolidPairEvaluations: Long,
    ): AssemblageSequenceOutcome {
        var current = first
        val frames = mutableListOf(
            AssemblageTraceFrame(
                introducedInstance = firstInstance,
                introducedSource = firstSource,
                scene = current,
                placement = null,
            )
        )
        val random = Random(seed)
        var remainingWork = maxSolidPairEvaluations
        for ((index, addition) in additions.withIndex()) {
            val instance = SourceInstanceId("source-${index + 1}")
            val stepSeed = if (index == 0) seed else random.nextLong()
            when (val outcome = attach(
                first = current,
                secondVariants = addition.variants,
                secondInstance = instance,
                placement = when (placement) {
                    is AssemblagePlacement.Search -> placement.copy(
                        targetSolidOverlap = targetSolidOverlap
                    )
                    is AssemblagePlacement.SampledSearch -> placement.copy(
                        targetSolidOverlap = targetSolidOverlap
                    )
                    is AssemblagePlacement.Fixed -> placement
                },
                seed = stepSeed,
                maxSolidPairEvaluations = remainingWork,
            )) {
                is AttachmentOutcome.Rejected -> return AssemblageSequenceOutcome.Rejected(
                    additionIndex = index,
                    reason = outcome.reason,
                )
                is AttachmentOutcome.Generated -> {
                    remainingWork -= outcome.solidPairEvaluations
                    val result = outcome.result
                    for (frameIndex in frames.indices) {
                        val frame = frames[frameIndex]
                        val shift = result.report.firstOrigin
                        frames[frameIndex] = frame.copy(
                            scene = frame.scene.shifted(
                                offset = shift,
                                size = result.scene.size,
                            ),
                            placement = frame.placement?.shifted(shift),
                        )
                    }
                    current = result.scene
                    frames += AssemblageTraceFrame(
                        introducedInstance = instance,
                        introducedSource = TraceSource.Authored(
                            addition.variants.first().asset.id
                        ),
                        scene = current,
                        placement = result.report,
                    )
                }
            }
        }
        return AssemblageSequenceOutcome.Generated(
            AssemblageTrace(seed, frames.toList())
        )
    }

    fun compose(request: AssemblageRequest): AssemblageOutcome {
        val first = sceneFromAsset(request.first, SourceInstanceId("first"))
        return when (val outcome = attach(
            first = first,
            secondVariants = request.secondVariants,
            secondInstance = SourceInstanceId("second"),
            placement = request.placement,
            seed = request.seed,
            maxSolidPairEvaluations = request.maxSolidPairEvaluations,
        )) {
            is AttachmentOutcome.Generated -> AssemblageOutcome.Generated(outcome.result)
            is AttachmentOutcome.Rejected -> AssemblageOutcome.Rejected(outcome.reason)
        }
    }

    private fun attach(
        first: SceneSnapshot,
        secondVariants: List<OrientedStructureAsset>,
        secondInstance: SourceInstanceId,
        placement: AssemblagePlacement,
        seed: Long,
        maxSolidPairEvaluations: Long,
    ): AttachmentOutcome {
        val firstSolids = first.solidPositions()
        if (firstSolids.isEmpty()) {
            return AttachmentOutcome.Rejected("First source has no solid cells")
        }
        val preparedVariants = secondVariants.map { variant ->
            PreparedVariant(variant, variant.asset.solidPositions())
        }

        val (candidate, work) = when (placement) {
            is AssemblagePlacement.Fixed -> {
                val prepared = preparedVariants.firstOrNull {
                    it.variant.rotation == placement.rotation
                } ?: return AttachmentOutcome.Rejected(
                    "Second source has no ${placement.rotation} orientation"
                )
                if (prepared.solids.isEmpty()) {
                    return AttachmentOutcome.Rejected("Second source has no solid cells")
                }
                Candidate(
                    prepared.variant,
                    placement.offset,
                    solidCollisionCount(firstSolids, prepared.solids, placement.offset),
                    firstSolids.size,
                    prepared.solids.size,
                ) to 0L
            }
            is AssemblagePlacement.Search -> {
                val required = solidPairEvaluations(firstSolids.size, preparedVariants)
                if (required > maxSolidPairEvaluations) {
                    return AttachmentOutcome.Rejected(
                        "SEARCH_WORK_BUDGET_EXCEEDED required=$required " +
                            "limit=$maxSolidPairEvaluations"
                    )
                }
                val candidate = searchCandidate(
                    firstSolids,
                    preparedVariants,
                    placement.targetSolidOverlap,
                    seed,
                ) ?: return AttachmentOutcome.Rejected("No solid overlap candidate exists")
                candidate to required
            }
            is AssemblagePlacement.SampledSearch -> {
                val sampled = sampledCandidates(
                    firstSolids = firstSolids,
                    variants = preparedVariants,
                    seed = seed,
                    maxCandidateOffsets = placement.maxCandidateOffsets,
                    maxSolidPairEvaluations = maxSolidPairEvaluations,
                )
                if (sampled.candidates.isEmpty()) {
                    return AttachmentOutcome.Rejected(
                        if (sampled.minimumEvaluationCost > maxSolidPairEvaluations) {
                            "SEARCH_WORK_BUDGET_EXCEEDED requiredAtLeast=" +
                                "${sampled.minimumEvaluationCost} limit=$maxSolidPairEvaluations"
                        } else {
                            "No sampled solid overlap candidate exists"
                        }
                    )
                }
                chooseCandidate(sampled.candidates, placement.targetSolidOverlap, seed) to
                    sampled.solidPairEvaluations
            }
        }
        return AttachmentOutcome.Generated(
            result = merge(first, candidate, secondInstance, seed),
            solidPairEvaluations = work,
        )
    }

    private fun searchCandidate(
        firstSolids: Set<Vec3i>,
        variants: List<PreparedVariant>,
        target: Double,
        seed: Long,
    ): Candidate? {
        val candidates = ArrayList<Candidate>()
        for (prepared in variants) {
            if (prepared.solids.isEmpty()) continue
            val collisionCounts = HashMap<Vec3i, Int>()
            for (firstPosition in firstSolids) {
                for (secondPosition in prepared.solids) {
                    val offset = firstPosition - secondPosition
                    collisionCounts[offset] = (collisionCounts[offset] ?: 0) + 1
                }
            }
            for ((offset, count) in collisionCounts) {
                candidates += Candidate(
                    prepared.variant,
                    offset,
                    count,
                    firstSolids.size,
                    prepared.solids.size,
                )
            }
        }
        if (candidates.isEmpty()) return null
        return chooseCandidate(candidates, target, seed)
    }

    private fun sampledCandidates(
        firstSolids: Set<Vec3i>,
        variants: List<PreparedVariant>,
        seed: Long,
        maxCandidateOffsets: Int,
        maxSolidPairEvaluations: Long,
    ): SampledCandidates {
        val active = variants.filter { it.solids.isNotEmpty() }
        if (active.isEmpty()) return SampledCandidates(emptyList(), 0, 0)
        val order = compareBy(Vec3i::x, Vec3i::y, Vec3i::z)
        val first = firstSolids.sortedWith(order)
        val random = Random(seed xor -7046029254386353131L)
        val candidates = ArrayList<Candidate>()
        var work = 0L
        var minimumCost = Long.MAX_VALUE
        var offsetsRemaining = maxOf(maxCandidateOffsets, active.size)
        for ((variantIndex, prepared) in active.withIndex()) {
            val second = prepared.solids.sortedWith(order)
            val evaluationCost = minOf(first.size, second.size).toLong()
            minimumCost = minOf(minimumCost, evaluationCost)
            val variantsRemaining = active.size - variantIndex
            val desiredOffsets = maxOf(1, offsetsRemaining / variantsRemaining)
            val offsets = LinkedHashSet<Vec3i>()
            offsets += first[first.size / 2] - second[second.size / 2]
            var attempts = 0
            while (offsets.size < desiredOffsets && attempts < desiredOffsets * 8) {
                offsets += first[random.nextInt(first.size)] -
                    second[random.nextInt(second.size)]
                attempts++
            }
            offsetsRemaining -= offsets.size
            for (offset in offsets) {
                if (work + evaluationCost > maxSolidPairEvaluations) break
                val collisionCount = solidCollisionCount(firstSolids, prepared.solids, offset)
                candidates += Candidate(
                    prepared.variant,
                    offset,
                    collisionCount,
                    firstSolids.size,
                    prepared.solids.size,
                )
                work += evaluationCost
            }
        }
        return SampledCandidates(candidates, work, minimumCost)
    }

    private fun chooseCandidate(
        candidates: List<Candidate>,
        target: Double,
        seed: Long,
    ): Candidate {
        val bestScore = candidates.minOf { abs(it.overlapRatio - target) }
        val best = candidates.filter { abs(abs(it.overlapRatio - target) - bestScore) < 1.0e-12 }
            .sortedWith(compareBy(
                { it.variant.rotation.ordinal },
                { it.offset.x },
                { it.offset.y },
                { it.offset.z },
            ))
        return best[Random(seed).nextInt(best.size)]
    }

    private fun merge(
        first: SceneSnapshot,
        candidate: Candidate,
        secondInstance: SourceInstanceId,
        seed: Long,
    ): AssemblageResult {
        val second = candidate.variant.asset
        val minimum = Vec3i(
            minOf(0, candidate.offset.x),
            minOf(0, candidate.offset.y),
            minOf(0, candidate.offset.z),
        )
        val firstOrigin = Vec3i(-minimum.x, -minimum.y, -minimum.z)
        val secondOrigin = firstOrigin + candidate.offset
        val size = Extent3i(
            maxOf(firstOrigin.x + first.size.x, secondOrigin.x + second.size.x),
            maxOf(firstOrigin.y + first.size.y, secondOrigin.y + second.size.y),
            maxOf(firstOrigin.z + first.size.z, secondOrigin.z + second.size.z),
        )
        val existing = first.cells.mapKeys { (position) -> position + firstOrigin }
        val incoming = contributionsFromAsset(second, secondOrigin, secondInstance)
        val collisionPositions = existing.keys.intersect(incoming.keys).filter { position ->
            existing.getValue(position).selected?.kind?.isSolid == true &&
                incoming.getValue(position).kind.isSolid
        }
        val cut = CutPlane.from(collisionPositions, seed)
        val positions = LinkedHashSet<Vec3i>(existing.size + incoming.size).apply {
            addAll(existing.keys)
            addAll(incoming.keys)
        }
        val cells = buildMap {
            for (position in positions) {
                val previous = existing[position]
                val addition = incoming[position]
                val previousBlock = previous?.selected?.takeIf {
                    it.kind.isSolid
                }
                val additionBlock = addition?.takeIf {
                    it.kind.isSolid
                }
                val selected = when {
                    previousBlock != null && additionBlock != null -> when (cut.winner(position)) {
                        LayerSide.EXISTING -> previousBlock
                        LayerSide.INCOMING -> additionBlock
                    }
                    previousBlock != null -> previousBlock
                    additionBlock != null -> additionBlock
                    else -> previous?.selected ?: addition ?: continue
                }
                val contributions = buildList {
                    previous?.contributions?.let(::addAll)
                    addition?.let(::add)
                }
                put(
                    position,
                    SceneCell(
                        state = selected.state,
                        selected = selected,
                        contributions = contributions,
                    )
                )
            }
        }
        val coherence = LocalCoherenceKernel.reconcile(
            scene = SceneSnapshot(size, cells),
            seam = SeamBandGeometry(
                axis = cut.axis,
                threshold = cut.threshold,
                collisionPositions = collisionPositions.toSet(),
            ),
        )
        return AssemblageResult(
            scene = coherence.scene,
            report = AssemblageReport(
                seed = seed,
                secondRotation = candidate.variant.rotation,
                secondOffset = candidate.offset,
                firstOrigin = firstOrigin,
                secondOrigin = secondOrigin,
                solidCollisionCount = candidate.collisionCount,
                solidOverlapRatio = candidate.overlapRatio,
                localCoherence = coherence.report,
            ),
        )
    }

    private fun sceneFromAsset(
        asset: StructureAsset,
        instance: SourceInstanceId,
    ): SceneSnapshot {
        val contributions = contributionsFromAsset(asset, Vec3i(0, 0, 0), instance)
        return SceneSnapshot(
            size = asset.size,
            cells = contributions.mapValues { (_, contribution) ->
                SceneCell(
                    state = contribution.state,
                    selected = contribution,
                    contributions = listOf(contribution),
                )
            },
        )
    }

    private fun contributionsFromAsset(
        asset: StructureAsset,
        origin: Vec3i,
        instance: SourceInstanceId,
    ): Map<Vec3i, SceneContribution> = StructureAssetScenes.authoredContributions(
        asset = asset,
        instance = instance,
        origin = origin,
    )

    private fun SceneSnapshot.shifted(offset: Vec3i, size: Extent3i): SceneSnapshot =
        SceneSnapshot(
            size = size,
            cells = cells.mapKeys { (position) -> position + offset },
        )

    private fun AssemblageReport.shifted(offset: Vec3i): AssemblageReport = copy(
        firstOrigin = firstOrigin + offset,
        secondOrigin = secondOrigin + offset,
    )

    private fun SceneSnapshot.solidPositions(): Set<Vec3i> = cells.mapNotNullTo(HashSet()) {
        (position, cell) -> position.takeIf {
            cell.selected?.kind?.isSolid == true
        }
    }

    private fun StructureAsset.solidPositions(): Set<Vec3i> =
        palettes.first().cells.mapNotNullTo(HashSet()) { (position, cell) ->
            position.takeIf { cell is AuthoredCell.Block }
        }

    private fun solidCollisionCount(
        first: Set<Vec3i>,
        second: Set<Vec3i>,
        offset: Vec3i,
    ): Int = if (second.size <= first.size) {
        second.count { it + offset in first }
    } else {
        first.count { it - offset in second }
    }

    private fun solidPairEvaluations(
        firstSolidCount: Int,
        variants: List<PreparedVariant>,
    ): Long = try {
        val secondSolidCount = variants.fold(0L) { total, variant ->
            Math.addExact(total, variant.solids.size.toLong())
        }
        Math.multiplyExact(firstSolidCount.toLong(), secondSolidCount)
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }

    private sealed interface AttachmentOutcome {
        data class Generated(
            val result: AssemblageResult,
            val solidPairEvaluations: Long,
        ) : AttachmentOutcome

        data class Rejected(val reason: String) : AttachmentOutcome
    }

    private data class Candidate(
        val variant: OrientedStructureAsset,
        val offset: Vec3i,
        val collisionCount: Int,
        val firstSolidCount: Int,
        val secondSolidCount: Int,
    ) {
        val overlapRatio: Double = collisionCount.toDouble() /
            minOf(firstSolidCount, secondSolidCount)
    }

    private data class PreparedVariant(
        val variant: OrientedStructureAsset,
        val solids: Set<Vec3i>,
    )

    private data class SampledCandidates(
        val candidates: List<Candidate>,
        val solidPairEvaluations: Long,
        val minimumEvaluationCost: Long,
    )

    private enum class LayerSide {
        EXISTING,
        INCOMING,
    }

    private data class CutPlane(
        val axis: Int,
        val threshold: Int,
        val singleWinner: LayerSide?,
    ) {
        fun winner(position: Vec3i): LayerSide {
            singleWinner?.let { return it }
            val coordinate = when (axis) {
                0 -> position.x
                1 -> position.y
                else -> position.z
            }
            return if (coordinate <= threshold) LayerSide.EXISTING else LayerSide.INCOMING
        }

        companion object {
            fun from(positions: Collection<Vec3i>, seed: Long): CutPlane {
                if (positions.isEmpty()) return CutPlane(0, 0, LayerSide.EXISTING)
                val min = Vec3i(
                    positions.minOf(Vec3i::x),
                    positions.minOf(Vec3i::y),
                    positions.minOf(Vec3i::z),
                )
                val max = Vec3i(
                    positions.maxOf(Vec3i::x),
                    positions.maxOf(Vec3i::y),
                    positions.maxOf(Vec3i::z),
                )
                val spans = intArrayOf(max.x - min.x, max.y - min.y, max.z - min.z)
                val axis = spans.indices.maxBy { spans[it] }
                if (spans[axis] == 0) {
                    val winner = if (((seed xor positions.size.toLong()) and 1L) == 0L) {
                        LayerSide.EXISTING
                    } else {
                        LayerSide.INCOMING
                    }
                    return CutPlane(axis, 0, winner)
                }
                val threshold = when (axis) {
                    0 -> (min.x + max.x) / 2
                    1 -> (min.y + max.y) / 2
                    else -> (min.z + max.z) / 2
                }
                return CutPlane(axis, threshold, null)
            }
        }
    }
}

private operator fun Vec3i.plus(other: Vec3i): Vec3i =
    Vec3i(x + other.x, y + other.y, z + other.z)

private operator fun Vec3i.minus(other: Vec3i): Vec3i =
    Vec3i(x - other.x, y - other.y, z - other.z)
