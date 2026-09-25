package com.caimeo.markovpaper.minecraft

import com.caimeo.markovpaper.assemblage.BlockStateSpec
import com.caimeo.markovpaper.assemblage.Extent3i
import com.caimeo.markovpaper.assemblage.Vec3i
import com.caimeo.markovpaper.xml.MarkovXmlCompiler
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class PreviewLifecycleTest {
    @Test
    fun `cancelled worker output never reaches the viewer or world`() {
        val workerStarted = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val workers = Executors.newSingleThreadExecutor()
        val delayed = Executor { task -> workers.execute {
            workerStarted.countDown()
            check(releaseWorker.await(5, TimeUnit.SECONDS))
            task.run()
        } }
        try {
            val target = RecordingTarget()
            val session = PreviewLifecycle(source(), target, delayed, GenerationMode.MATERIALIZE)
            session.tick()
            assertTrue(workerStarted.await(5, TimeUnit.SECONDS))
            session.requestStop()
            session.tick()
            assertTrue(session.finished)
            releaseWorker.countDown()
            workers.submit {}.get(5, TimeUnit.SECONDS)
            session.tick()
            assertTrue(target.visible.isEmpty())
            assertTrue(target.committed.isEmpty())
            assertEquals(listOf(5), target.showSizes)
            assertTrue(target.errors.isEmpty())
        } finally {
            releaseWorker.countDown()
            workers.shutdownNow()
        }
    }

    @Test
    fun `a partially sent frame is restored instead of dropping its cleanup state`() {
        val target = RecordingTarget().apply { partialShowFailure = true }
        val session = PreviewLifecycle(source(), target, Executor(Runnable::run), GenerationMode.MATERIALIZE,
            pacing = PreviewPacing(displayBlocks = 2, restoreBlocks = 1))
        session.tick()
        assertFalse(session.finished)
        assertEquals(1, target.visible.size)
        repeat(10) { session.tick() }
        assertTrue(session.finished)
        assertTrue(target.visible.isEmpty())
        assertTrue(target.committed.isEmpty())
        assertEquals(2, target.restoreSizes.sum())
        assertEquals(1, target.errors.size)
    }

    @Test
    fun `shutdown finishes an accepted commit but only restores an unfinished preview`() {
        val committed = RecordingTarget()
        val materialization = PreviewLifecycle(source(), committed, Executor(Runnable::run), GenerationMode.MATERIALIZE,
            pacing = PreviewPacing(displayBlocks = 2, commitBlocks = 2))
        repeat(100) { if (!materialization.committing) materialization.tick() }
        materialization.tick()
        assertEquals(2, committed.committed.size)
        materialization.close()
        assertTrue(materialization.finished)
        assertEquals(5, committed.committed.size)
        assertTrue(committed.synchronousCommit)

        val previewed = RecordingTarget()
        val preview = PreviewLifecycle(source(), previewed, Executor(Runnable::run), GenerationMode.MATERIALIZE)
        preview.tick()
        preview.close()
        assertTrue(preview.finished)
        assertTrue(previewed.visible.isEmpty())
        assertTrue(previewed.committed.isEmpty())
    }

    @Test
    fun `failed restoration retains its batch and stop can retry it`() {
        val target = RecordingTarget()
        val session = PreviewLifecycle(source(), target, Executor(Runnable::run), GenerationMode.PREVIEW,
            pacing = PreviewPacing(displayBlocks = 2, restoreBlocks = 1))
        session.tick()
        session.requestStop()
        target.restoreFailures = 1
        session.tick()
        assertFalse(session.finished)
        assertEquals(2, target.visible.size)
        assertEquals(1, target.errors.size)
        session.requestStop()
        repeat(10) { session.tick() }
        assertTrue(session.finished)
        assertTrue(target.visible.isEmpty())
    }

    @Test
    fun `materialization starts after playback and continues across stop and disconnect`() {
        val target = RecordingTarget()
        val session = PreviewLifecycle(source(), target, Executor(Runnable::run), GenerationMode.MATERIALIZE,
            pacing = PreviewPacing(advances = 2, displayBlocks = 2, commitBlocks = 2, restoreBlocks = 1))
        repeat(100) { if (!session.committing) session.tick() }
        assertTrue(session.committing)
        assertTrue(target.committed.isEmpty())
        assertEquals(5, target.visible.size)
        assertTrue(target.visible.values.all { it == BlockStateSpec("minecraft:stone") })
        assertFalse(session.requestStop())
        target.viewerAvailable = false
        assertFalse(session.detach())
        target.partialCommitFailure = true
        session.tick()
        assertFalse(session.finished)
        assertEquals(1, target.committed.size)
        repeat(100) { if (!session.finished) session.tick() }
        assertTrue(session.finished)
        assertEquals(5, target.committed.size)
        assertTrue(target.committed.values.all { it == BlockStateSpec("minecraft:stone") })
        assertTrue(target.commitSizes.all { it <= 2 })
        assertTrue(target.showSizes.all { it <= 2 })
        assertEquals(1, target.errors.size)
    }

    private fun source(size: Int = 5): PreviewSource {
        val model = MarkovXmlCompiler.compile("""<one values="BW" in="B" out="W"/>""", size, 1, 1, 7)
        return VoxelPreviewSource(model.grid, model.node,
            mapOf(0.toByte() to BlockStateSpec("minecraft:air"), 1.toByte() to BlockStateSpec("minecraft:stone")), true)
    }

    @Test
    fun `stopping an initial preview restores only its displayed cells in bounded batches`() {
        val target = RecordingTarget()
        val session = PreviewLifecycle(source(), target, Executor(Runnable::run), GenerationMode.PREVIEW,
            pacing = PreviewPacing(displayBlocks = 2, commitBlocks = 2, restoreBlocks = 1))
        session.tick()
        assertEquals(2, target.visible.size)
        session.requestStop()
        session.tick()
        assertEquals(1, target.visible.size)
        session.tick()
        assertTrue(session.finished)
        assertTrue(target.visible.isEmpty())
        assertTrue(target.committed.isEmpty())
        assertTrue(target.showSizes.all { it <= 2 })
        assertTrue(target.restoreSizes.all { it <= 1 })
    }

    private class RecordingTarget : PreviewTarget {
        override var viewerAvailable = true
        val visible = linkedMapOf<Vec3i, BlockStateSpec?>()
        val committed = linkedMapOf<Vec3i, BlockStateSpec?>()
        val showSizes = mutableListOf<Int>()
        val restoreSizes = mutableListOf<Int>()
        val commitSizes = mutableListOf<Int>()
        val errors = mutableListOf<Exception>()
        var restoreFailures = 0
        var synchronousCommit = false
        var partialShowFailure = false
        var partialCommitFailure = false
        override fun validate(size: Extent3i) = Unit
        override fun show(cells: List<PreviewCell>) {
            showSizes += cells.size
            if (partialShowFailure) {
                partialShowFailure = false
                visible[cells.first().position] = cells.first().state
                error("partial send failure")
            }
            cells.forEach { visible[it.position] = it.state }
        }
        override fun restore(positions: List<Vec3i>): Int {
            if (restoreFailures > 0) { restoreFailures--; error("temporary restore failure") }
            restoreSizes += positions.size
            positions.forEach(visible::remove)
            return positions.size
        }
        override fun commit(cells: List<PreviewCell>, synchronous: Boolean): Int {
            synchronousCommit = synchronousCommit || synchronous
            commitSizes += cells.size
            if (partialCommitFailure) {
                partialCommitFailure = false
                committed[cells.first().position] = cells.first().state
                error("partial world write failure")
            }
            cells.forEach { committed[it.position] = it.state }
            return cells.size
        }
        override fun notify(message: String) = Unit
        override fun failure(operation: String, exception: Exception) { errors += exception }
    }
}
