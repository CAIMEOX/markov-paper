package com.caimeo.markovpaper.minecraft

internal class BoundedWorkCursor<T>(
    private val source: Iterator<T>,
) {
    private val pending = ArrayList<T>()

    val complete: Boolean
        get() = pending.isEmpty() && !source.hasNext()

    /** Keep a batch until its consumer acknowledges a prefix; exceptions acknowledge nothing. */
    fun advance(limit: Int, consume: (List<T>) -> Int): Boolean {
        require(limit > 0)
        while (pending.size < limit && source.hasNext()) pending += source.next()
        if (pending.isEmpty()) return true
        val batch = pending.take(limit)
        val acknowledged = consume(batch)
        require(acknowledged in 0..batch.size) { "Invalid batch acknowledgement: $acknowledged" }
        pending.subList(0, acknowledged).clear()
        return complete
    }
}
