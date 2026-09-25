package com.caimeo.markovpaper.minecraft

import com.caimeo.markovpaper.assemblage.Extent3i
import com.caimeo.markovpaper.assemblage.Vec3i
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException

interface PreviewTarget {
    val viewerAvailable: Boolean
    fun validate(size: Extent3i)
    fun show(cells: List<PreviewCell>)
    fun restore(positions: List<Vec3i>): Int
    fun commit(cells: List<PreviewCell>, synchronous: Boolean): Int
    fun notify(message: String)
    fun failure(operation: String, exception: Exception)
    fun close() = Unit
}

data class PreviewPacing(
    val advances: Int = 1,
    val displayBlocks: Int = 512,
    val commitBlocks: Int = 512,
    val restoreBlocks: Int = 512,
) {
    init { require(advances > 0 && displayBlocks > 0 && commitBlocks > 0 && restoreBlocks > 0) }
}

/** One client-overlay lifecycle, independent of the generator and server transport. */
class PreviewLifecycle(
    source: PreviewSource,
    private val target: PreviewTarget,
    private val executor: Executor,
    private val mode: GenerationMode,
    private val label: String = "Markov",
    private val pacing: PreviewPacing = PreviewPacing(),
) {
    private enum class Phase { DISPLAYING, GENERATING, COMMITTING, PREVIEW, RESTORING, FINISHED }
    private var phase = Phase.DISPLAYING
    private var generator: PreviewSource? = source
    private var display: BoundedWorkCursor<PreviewCell>? = BoundedWorkCursor(source.initialCells().iterator())
    private var restoration: BoundedWorkCursor<Vec3i>? = null
    private var commit: BoundedWorkCursor<PreviewCell>? = null
    private var work: FutureTask<ComputedFrame>? = null
    private var result: PreviewResult? = null
    private var lastPhase: String? = null
    private var retryDelay = 0
    private var lastFailure: String? = null
    private val displayed = LinkedHashSet<Vec3i>()
    val finished: Boolean get() = phase == Phase.FINISHED
    val committing: Boolean get() = phase == Phase.COMMITTING

    init { target.validate(source.initialSize) }

    fun tick(): Boolean {
        if (finished) return true
        try {
            return advanceTick()
        } catch (failure: Exception) {
            val exception = if (failure is ExecutionException) failure.cause as? Exception ?: failure else failure
            val operation = if (committing) "commit" else if (phase == Phase.RESTORING) "restore" else "generation"
            report(operation, exception)
            if (committing || phase == Phase.RESTORING) {
                retryDelay = 20
            } else {
                requestStop()
            }
            return finished
        }
    }

    private fun advanceTick(): Boolean {
        if (!target.viewerAvailable && !committing) return detach()
        if (retryDelay > 0) { retryDelay--; return false }
        when (phase) {
            Phase.DISPLAYING -> displayPending()
            Phase.GENERATING -> {
                val current = work
                if (current != null && current.isDone) {
                    val frame = current.get()
                    work = null
                    for (name in frame.phases) if (name != lastPhase) {
                        notify("$label phase: $name")
                        lastPhase = name
                    }
                    result = frame.result
                    display = BoundedWorkCursor(frame.cells.iterator())
                    phase = Phase.DISPLAYING
                    displayPending()
                }
            }
            Phase.COMMITTING -> if (requireNotNull(commit).advance(pacing.commitBlocks) {
                target.commit(it, false).also { count -> if (count > 0) lastFailure = null }
            }) {
                notify("$label complete and materialized into the world.")
                discard()
            }
            Phase.RESTORING -> if (requireNotNull(restoration).advance(pacing.restoreBlocks) {
                target.restore(it).also { count -> if (count > 0) lastFailure = null }
            }) {
                notify("$label preview restoration complete.")
                discard()
            }
            else -> Unit
        }
        if (phase == Phase.GENERATING && work == null) startWork()
        return finished
    }

    private fun displayPending() {
        if (requireNotNull(display).advance(pacing.displayBlocks) { cells ->
            displayed.addAll(cells.map { it.position })
            target.show(cells)
            lastFailure = null
            cells.size
        }) {
            display = null
            val output = result
            result = null
            if (output == null) phase = Phase.GENERATING else {
                generator = null
                output.details.forEach(::notify)
                if (mode == GenerationMode.MATERIALIZE) {
                    target.validate(output.size)
                    commit = BoundedWorkCursor(output.cells.iterator())
                    phase = Phase.COMMITTING
                    notify("$label generation complete; materializing at most ${pacing.commitBlocks} blocks/tick. The commit will finish even if you stop or leave.")
                } else {
                    phase = Phase.PREVIEW
                    notify("$label preview complete. Use /mj stop to restore the world.")
                }
            }
        }
    }

    private fun startWork() {
        val source = requireNotNull(generator)
        val task = FutureTask {
            val frames = ArrayList<PreviewFrame>()
            var count = 0
            var output: PreviewResult? = null
            for (step in 0 until pacing.advances) {
                if (Thread.currentThread().isInterrupted) throw CancellationException("Preview stopped")
                val frame = source.advance()
                if (frame == null) { output = source.finish(); break }
                frames += frame
                count += frame.cellCount
                if (count >= pacing.displayBlocks) break
            }
            ComputedFrame(frames.asSequence().flatMap { it.cells }, frames.mapNotNull { it.phase }, output)
        }
        work = task
        executor.execute(task)
    }

    fun requestStop(): Boolean {
        retryDelay = 0
        if (finished) return true
        if (committing) {
            notify("$label is already materializing; its commit will finish before another preview can start.")
            return false
        }
        if (phase != Phase.RESTORING) {
            work?.cancel(true)
            work = null
            generator = null
            display = null
            result = null
            if (displayed.isEmpty() || !target.viewerAvailable) return discard()
            restoration = BoundedWorkCursor(displayed.iterator())
            phase = Phase.RESTORING
            notify("Restoring $label preview at most ${pacing.restoreBlocks} blocks/tick.")
        }
        return false
    }

    /** A viewer leaving cancels only work that has not started committing. */
    fun detach(): Boolean = if (committing) false else discard()

    /** Shutdown has no future ticks: finish an accepted commit, otherwise restore the overlay. */
    fun close() {
        if (finished) return
        work?.cancel(true)
        try {
            if (committing) {
                while (!requireNotNull(commit).advance(pacing.commitBlocks) { cells ->
                    target.commit(cells, true).also { check(it > 0) { "Shutdown commit made no progress" } }
                }) Unit
                notify("$label complete and materialized into the world.")
            } else if (target.viewerAvailable) {
                val remaining = restoration ?: BoundedWorkCursor(displayed.iterator())
                while (!remaining.advance(pacing.restoreBlocks) { positions ->
                    target.restore(positions).also { check(it > 0) { "Shutdown restoration made no progress" } }
                }) Unit
            }
        } catch (exception: Exception) {
            runCatching { target.failure("$label shutdown cleanup; the world or client may be partially updated", exception) }
        } finally {
            discard()
        }
    }

    private fun discard(): Boolean {
        work?.cancel(true)
        work = null
        generator = null
        display = null
        restoration = null
        commit = null
        result = null
        displayed.clear()
        phase = Phase.FINISHED
        runCatching { target.close() }
        return true
    }

    private fun notify(message: String) { runCatching { target.notify(message) } }

    private fun report(operation: String, exception: Exception) {
        val key = "$operation:${exception.javaClass.name}:${exception.message}"
        if (lastFailure == key) return
        lastFailure = key
        runCatching { target.failure("$label $operation", exception) }
        notify("$label $operation failed: ${exception.message}. " +
            if (committing || phase == Phase.RESTORING) "The pending batch is retained for retry." else "Restoring the preview.")
    }

    private data class ComputedFrame(val cells: Sequence<PreviewCell>, val phases: List<String>, val result: PreviewResult?)
}
