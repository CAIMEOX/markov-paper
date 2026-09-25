package com.caimeo.markovpaper.scene

import com.caimeo.markovpaper.assemblage.AssemblagePlan
import com.caimeo.markovpaper.assemblage.AttachmentDirection
import com.caimeo.markovpaper.assemblage.AuthoredCell
import com.caimeo.markovpaper.assemblage.BlockStateSpec
import com.caimeo.markovpaper.assemblage.LocalCoherenceCellMutation
import com.caimeo.markovpaper.assemblage.LocalCoherenceKernel
import com.caimeo.markovpaper.assemblage.LocalCoherenceLimits
import com.caimeo.markovpaper.assemblage.PlannedAttachment
import com.caimeo.markovpaper.assemblage.PlannedStructureInstance
import com.caimeo.markovpaper.assemblage.SceneCell
import com.caimeo.markovpaper.assemblage.SceneContribution
import com.caimeo.markovpaper.assemblage.SceneProvenance
import com.caimeo.markovpaper.assemblage.SceneContributionKind
import com.caimeo.markovpaper.assemblage.SceneSnapshot
import com.caimeo.markovpaper.assemblage.SeamBandGeometry
import com.caimeo.markovpaper.assemblage.Vec3i

class MacroAssemblageException(message: String) : IllegalStateException(message)

class MacroAssemblageProgram(
    val plan: AssemblagePlan,
    private val maxSceneCells: Int = 1_000_000,
    private val workUnitsPerAdvance: Int = 2_048,
) : SceneProgram {
    private val size = plan.bounds.size
    private val normalization = Vec3i(
        -plan.bounds.minimum.x,
        -plan.bounds.minimum.y,
        -plan.bounds.minimum.z,
    )
    private val attachmentsByChild = plan.attachments.associateBy(PlannedAttachment::child)
    private val normalizedInstances = plan.instances.map { instance ->
        instance.copy(origin = normalize(instance.origin))
    }
    private val instancesById = normalizedInstances.associateBy(PlannedStructureInstance::id)
    private val cells = LinkedHashMap<Vec3i, SceneCell>()
    private var instanceIndex = 0
    private var active: InstanceWork? = null
    private var cachedCurrent = SceneSnapshot(size, emptyMap())
    private var dirty = false

    var completedInstances: Int = 0
        private set

    var lastAdvanceWorkUnits: Int = 0
        private set

    override val initial: SceneSnapshot = cachedCurrent

    override val extent
        get() = size

    override val current: SceneSnapshot
        get() {
            if (dirty) {
                cachedCurrent = SceneSnapshot(size, cells.toMap())
                dirty = false
            }
            return cachedCurrent
        }

    init {
        require(maxSceneCells > 0)
        require(workUnitsPerAdvance > 0)
    }

    override fun advance(): SceneProgramDelta? {
        var remaining = workUnitsPerAdvance
        val changes = ArrayList<SceneProgramChange>(workUnitsPerAdvance)
        var instanceCompleted = false

        while (remaining > 0 && !instanceCompleted) {
            var work = active
            if (work == null) {
                val instance = normalizedInstances.getOrNull(instanceIndex)
                if (instance == null) {
                    lastAdvanceWorkUnits = workUnitsPerAdvance - remaining
                    return if (changes.isEmpty()) null else SceneProgramDelta(PHASE, changes)
                }
                work = InstanceWork(
                    instance = instance,
                    attachment = attachmentsByChild[instance.id],
                    collisionSampleLimit = COHERENCE_COLLISION_SAMPLE_LIMIT,
                )
                active = work
                remaining--
                if (remaining == 0) break
            }

            when (work.phase) {
                InstancePhase.SCAN -> {
                    if (work.cells.hasNext()) {
                        val (position, authored) = work.cells.next()
                        remaining--
                        if (authored is AuthoredCell.Block) {
                            val worldPosition = worldPosition(work.instance, position)
                            val parent = requireNotNull(
                                instancesById[requireNotNull(work.attachment).parent]
                            )
                            val parentPosition = Vec3i(
                                worldPosition.x - parent.origin.x,
                                worldPosition.y - parent.origin.y,
                                worldPosition.z - parent.origin.z,
                            )
                            if (
                                parent.source.asset.palettes.first().cells[parentPosition] is
                                AuthoredCell.Block
                            ) {
                                work.collisions.add(worldPosition)
                            }
                        }
                    } else {
                        work.cut = work.collisions.cut()
                        work.cells = paletteCells(work.instance)
                        work.phase = InstancePhase.APPLY
                    }
                }
                InstancePhase.APPLY -> {
                    if (work.cells.hasNext()) {
                        val (position, authored) = work.cells.next()
                        remaining--
                        applyAuthored(work, position, authored)?.let(changes::add)
                    } else if (work.attachment == null) {
                        remaining--
                        finishInstance()
                        instanceCompleted = true
                    } else {
                        work.phase = InstancePhase.PLAN_COHERENCE
                    }
                }
                InstancePhase.PLAN_COHERENCE -> {
                    val needed = COHERENCE_PLANNING_ALLOWANCE -
                        work.coherencePlanningCredits
                    val spent = minOf(remaining, needed)
                    work.coherencePlanningCredits += spent
                    remaining -= spent
                    if (work.coherencePlanningCredits == COHERENCE_PLANNING_ALLOWANCE) {
                        work.coherence = LocalCoherenceKernel.plan(
                            size = size,
                            cells = cells,
                            seam = SeamBandGeometry(
                                axis = work.collisions.axis,
                                threshold = requireNotNull(work.cut).threshold,
                                collisionPositions = work.collisions.samples,
                            ),
                            limits = MACRO_COHERENCE_LIMITS,
                        ).mutations.iterator()
                        work.phase = InstancePhase.COHERENCE
                    }
                }
                InstancePhase.COHERENCE -> {
                    if (work.coherence.hasNext()) {
                        remaining--
                        applyCoherence(work.coherence.next())?.let(changes::add)
                    } else {
                        remaining--
                        finishInstance()
                        instanceCompleted = true
                    }
                }
            }
        }

        lastAdvanceWorkUnits = workUnitsPerAdvance - remaining
        check(lastAdvanceWorkUnits in 1..workUnitsPerAdvance) {
            "Macro advance made no bounded progress"
        }
        return SceneProgramDelta(PHASE, changes)
    }

    private fun applyAuthored(
        work: InstanceWork,
        sourcePosition: Vec3i,
        authored: AuthoredCell,
    ): SceneProgramChange? {
        val (kind, state) = when (authored) {
            is AuthoredCell.Block -> SceneContributionKind.BLOCK to authored.state
            AuthoredCell.AuthoredAir ->
                SceneContributionKind.AUTHORED_AIR to BlockStateSpec("minecraft:air")
            AuthoredCell.StructureVoid, is AuthoredCell.Control -> return null
        }
        val position = worldPosition(work.instance, sourcePosition)
        val previous = cells[position]
        val addition = SceneContribution(
            provenance = SceneProvenance.Authored(work.instance.id, work.instance.source.asset.id, sourcePosition),
            kind = kind,
            state = state,
        )
        val previousSolid = previous?.selected?.takeIf { it.kind.isSolid }
        val additionSolid = addition.takeIf { it.kind.isSolid }
        val selected = when {
            previousSolid != null && additionSolid != null ->
                if (requireNotNull(work.cut).incomingWins(position)) additionSolid else previousSolid
            previousSolid != null -> previousSolid
            additionSolid != null -> additionSolid
            else -> previous?.selected ?: addition
        }
        requireCapacityFor(position)
        cells[position] = SceneCell(
            state = selected.state,
            selected = selected,
            contributions = previous?.contributions.orEmpty() + addition,
        )
        dirty = true
        return visualChange(position, previous?.state, selected.state)
    }

    private fun applyCoherence(mutation: LocalCoherenceCellMutation): SceneProgramChange? {
        require(size.contains(mutation.position)) {
            "Macro seam reaches outside its Scene extent at ${mutation.position}"
        }
        val previous = cells[mutation.position]
        requireCapacityFor(mutation.position)
        LocalCoherenceKernel.apply(cells, mutation)
        dirty = true
        return visualChange(mutation.position, previous?.state, mutation.state)
    }

    private fun visualChange(
        position: Vec3i,
        before: BlockStateSpec?,
        after: BlockStateSpec?,
    ): SceneProgramChange? =
        if (before == after) null else SceneProgramChange(position, before, after)

    private fun requireCapacityFor(position: Vec3i) {
        if (position !in cells && cells.size >= maxSceneCells) {
            throw MacroAssemblageException(
                "Macro Scene would exceed $maxSceneCells cells while adding macro-${instanceIndex}"
            )
        }
    }

    private fun finishInstance() {
        active = null
        instanceIndex++
        completedInstances++
    }

    private fun paletteCells(
        instance: PlannedStructureInstance,
    ): Iterator<Map.Entry<Vec3i, AuthoredCell>> =
        instance.source.asset.palettes.first().cells.entries.iterator()

    private fun worldPosition(
        instance: PlannedStructureInstance,
        sourcePosition: Vec3i,
    ): Vec3i = Vec3i(
        instance.origin.x + sourcePosition.x,
        instance.origin.y + sourcePosition.y,
        instance.origin.z + sourcePosition.z,
    )

    private fun normalize(position: Vec3i): Vec3i = Vec3i(
        position.x + normalization.x,
        position.y + normalization.y,
        position.z + normalization.z,
    )

    private class InstanceWork(
        val instance: PlannedStructureInstance,
        val attachment: PlannedAttachment?,
        collisionSampleLimit: Int,
    ) {
        var phase = if (attachment == null) InstancePhase.APPLY else InstancePhase.SCAN
        var cells: Iterator<Map.Entry<Vec3i, AuthoredCell>> =
            instance.source.asset.palettes.first().cells.entries.iterator()
        val collisions = CollisionSummary(
            direction = attachment?.direction ?: AttachmentDirection.EAST,
            sampleLimit = collisionSampleLimit,
        )
        var cut: AttachmentCut? = if (attachment == null) AttachmentCut.noCollisions() else null
        var coherence: Iterator<LocalCoherenceCellMutation> = emptyList<LocalCoherenceCellMutation>()
            .iterator()
        var coherencePlanningCredits: Int = 0
    }

    private enum class InstancePhase {
        SCAN,
        APPLY,
        PLAN_COHERENCE,
        COHERENCE,
    }

    private class CollisionSummary(
        direction: AttachmentDirection,
        private val sampleLimit: Int,
    ) {
        val axis: Int = when (direction) {
            AttachmentDirection.WEST, AttachmentDirection.EAST -> 0
            AttachmentDirection.DOWN, AttachmentDirection.UP -> 1
            AttachmentDirection.NORTH, AttachmentDirection.SOUTH -> 2
        }
        private val incomingOnGreaterSide = direction in setOf(
            AttachmentDirection.EAST,
            AttachmentDirection.UP,
            AttachmentDirection.SOUTH,
        )
        val samples = LinkedHashSet<Vec3i>()
        private var minimum = Int.MAX_VALUE
        private var maximum = Int.MIN_VALUE

        fun add(position: Vec3i) {
            val coordinate = coordinate(position)
            minimum = minOf(minimum, coordinate)
            maximum = maxOf(maximum, coordinate)
            if (samples.size < sampleLimit) samples += position
        }

        fun cut(): AttachmentCut = if (minimum == Int.MAX_VALUE) {
            AttachmentCut.noCollisions(axis, incomingOnGreaterSide)
        } else {
            AttachmentCut(
                axis = axis,
                threshold = minimum + (maximum - minimum) / 2,
                incomingOnGreaterSide = incomingOnGreaterSide,
                hasCollisions = true,
            )
        }

        private fun coordinate(position: Vec3i): Int = when (axis) {
            0 -> position.x
            1 -> position.y
            else -> position.z
        }
    }

    private data class AttachmentCut(
        val axis: Int,
        val threshold: Int,
        val incomingOnGreaterSide: Boolean,
        val hasCollisions: Boolean,
    ) {
        fun incomingWins(position: Vec3i): Boolean {
            if (!hasCollisions) return true
            val coordinate = when (axis) {
                0 -> position.x
                1 -> position.y
                else -> position.z
            }
            return if (incomingOnGreaterSide) coordinate > threshold else coordinate <= threshold
        }

        companion object {
            fun noCollisions(
                axis: Int = 0,
                incomingOnGreaterSide: Boolean = true,
            ): AttachmentCut = AttachmentCut(
                axis,
                threshold = 0,
                incomingOnGreaterSide,
                hasCollisions = false,
            )
        }
    }

    companion object {
        private const val PHASE = "macro-growth"
        private const val COHERENCE_PLANNING_ALLOWANCE = 2_048
        private const val COHERENCE_COLLISION_SAMPLE_LIMIT = 64
        private val MACRO_COHERENCE_LIMITS = LocalCoherenceLimits(
            maxCollisionPositions = COHERENCE_COLLISION_SAMPLE_LIMIT,
            maxDoorwayCandidates = 64,
            maxSupportDepth = 32,
        )
    }
}
