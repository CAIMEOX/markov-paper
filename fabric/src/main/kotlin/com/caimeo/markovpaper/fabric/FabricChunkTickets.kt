package com.caimeo.markovpaper.fabric

import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.TicketType
import net.minecraft.world.level.ChunkPos
import java.util.concurrent.CompletableFuture

/** Server-thread leases keep an asynchronously requested chunk alive through a commit batch. */
internal class FabricChunkTickets(private val type: TicketType) : AutoCloseable {
    private data class Key(val level: ServerLevel, val position: ChunkPos)
    private data class Entry(val ready: CompletableFuture<*>, var users: Int)
    private val entries = HashMap<Key, Entry>()

    fun acquire(level: ServerLevel, position: ChunkPos): Lease {
        val key = Key(level, position)
        val entry = entries[key] ?: try {
            Entry(level.chunkSource.addTicketAndLoadWithRadius(type, position, 0), 0).also { entries[key] = it }
        } catch (exception: Exception) {
            level.chunkSource.removeTicketWithRadius(type, position, 0)
            throw exception
        }
        entry.users++
        return Lease(entry.ready) {
            if (entries[key] === entry && --entry.users == 0) {
                entries.remove(key)
                level.chunkSource.removeTicketWithRadius(type, position, 0)
            }
        }
    }

    override fun close() {
        try {
            for (key in entries.keys) key.level.chunkSource.removeTicketWithRadius(type, key.position, 0)
        } finally { entries.clear() }
    }

    class Lease(val ready: CompletableFuture<*>, private val release: () -> Unit) : AutoCloseable {
        private var closed = false
        override fun close() { if (!closed) { closed = true; release() } }
    }
}
