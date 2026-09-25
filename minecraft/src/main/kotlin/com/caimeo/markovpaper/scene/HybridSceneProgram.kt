package com.caimeo.markovpaper.scene

import com.caimeo.markovpaper.assemblage.ArchitecturalAssemblage
import com.caimeo.markovpaper.assemblage.AssemblageAddition
import com.caimeo.markovpaper.assemblage.AssemblageTrace
import com.caimeo.markovpaper.assemblage.AssemblageSequenceOutcome
import com.caimeo.markovpaper.assemblage.SceneAssemblageSequenceRequest
import com.caimeo.markovpaper.assemblage.SceneSnapshot
import com.caimeo.markovpaper.assemblage.TraceSource
import com.caimeo.markovpaper.assemblage.Vec3i

class HybridAssemblageException(message: String) : IllegalStateException(message)

class HybridSceneProgram(
    private val prefix: SceneProgram,
    private val prefixSource: TraceSource.Procedural,
    private val additions: List<AssemblageAddition>,
    private val targetSolidOverlap: Double,
    private val seed: Long,
    private val maxSolidPairEvaluations: Long,
    private val changesPerAdvance: Int,
    private val maxSceneCells: Int = 20_000,
) : SceneProgram {
    override val initial: SceneSnapshot = prefix.initial

    private var scene = initial
    private var prefixComplete = false
    private var replayStages = emptyList<ReplayStage>()
    private var stageIndex = 0
    private var changeIndex = 0

    var assemblageTrace: AssemblageTrace? = null
        private set

    override val current: SceneSnapshot
        get() = if (prefixComplete) scene else prefix.current

    init {
        require(additions.isNotEmpty()) { "Hybrid generation needs an authored addition" }
        require(targetSolidOverlap in 0.0..1.0)
        require(maxSolidPairEvaluations > 0)
        require(changesPerAdvance > 0)
        require(maxSceneCells > 0)
    }

    override fun advance(): SceneProgramDelta? {
        if (!prefixComplete) {
            val prefixDelta = prefix.advance()
            if (prefixDelta != null) {
                return prefixDelta
            }
            scene = prefix.current
            prefixComplete = true
            prepareReplay()
        }
        return advanceReplay()
    }

    private fun prepareReplay() {
        val outcome = ArchitecturalAssemblage.compose(
            SceneAssemblageSequenceRequest(
                first = scene,
                firstSource = prefixSource,
                additions = additions,
                targetSolidOverlap = targetSolidOverlap,
                seed = seed,
                maxSolidPairEvaluations = maxSolidPairEvaluations,
            )
        )
        val trace = when (outcome) {
            is AssemblageSequenceOutcome.Generated -> outcome.trace
            is AssemblageSequenceOutcome.Rejected -> throw HybridAssemblageException(
                "Could not add source ${outcome.additionIndex + 2}: ${outcome.reason}"
            )
        }
        assemblageTrace = trace
        if (trace.finalScene.cells.size > maxSceneCells) {
            throw HybridAssemblageException(
                "Final hybrid Scene has ${trace.finalScene.cells.size} cells; limit=$maxSceneCells"
            )
        }
        val stages = ArrayList<ReplayStage>(trace.frames.size)
        var before = scene
        for ((index, frame) in trace.frames.withIndex()) {
            stages += ReplayStage(
                phase = if (index == 0) {
                    "assemblage-align"
                } else {
                    "assemblage:${frame.introducedSource.label}"
                },
                target = frame.scene,
                changes = sceneChanges(before, frame.scene),
            )
            before = frame.scene
        }
        replayStages = stages
    }

    private fun advanceReplay(): SceneProgramDelta? {
        if (stageIndex >= replayStages.size) return null
        val stage = replayStages[stageIndex]
        val end = minOf(changeIndex + changesPerAdvance, stage.changes.size)
        val batch = stage.changes.subList(changeIndex, end).toList()
        val cells = scene.cells.toMutableMap()
        for (change in batch) {
            val targetCell = stage.target.cells[change.position]
            if (targetCell == null) cells.remove(change.position) else cells[change.position] = targetCell
        }
        scene = SceneSnapshot(stage.target.size, cells.toMap())
        changeIndex = end
        if (changeIndex >= stage.changes.size) {
            scene = stage.target
            stageIndex++
            changeIndex = 0
        }
        return SceneProgramDelta(stage.phase, batch)
    }

    private fun sceneChanges(
        before: SceneSnapshot,
        after: SceneSnapshot,
    ): List<SceneProgramChange> = (before.cells.keys + after.cells.keys)
        .distinct()
        .sortedWith(compareBy(Vec3i::y, Vec3i::x, Vec3i::z))
        .mapNotNull { position ->
            val beforeState = before.cells[position]?.state
            val afterState = after.cells[position]?.state
            if (beforeState == afterState) null
            else SceneProgramChange(position, beforeState, afterState)
        }

    private data class ReplayStage(
        val phase: String,
        val target: SceneSnapshot,
        val changes: List<SceneProgramChange>,
    )
}
