package com.caimeo.markovpaper.workbench

import com.caimeo.markovpaper.assemblage.AssetId
import com.caimeo.markovpaper.assemblage.AssetReference
import java.util.Collections
import java.util.Random

class AssetWorkbenchState(
    val query: String?,
    matches: List<AssetReference>,
    val pageSize: Int = 6,
) {
    val matches: List<AssetReference> = matches.toList()
    private val mutableTray = ArrayList<AssetId>()
    private var pageIndex = 0

    var selected: AssetReference? = null
        private set

    var portsVisible: Boolean = true
        private set

    val tray: List<AssetId>
        get() = mutableTray.toList()

    val pageNumber: Int
        get() = pageIndex + 1

    val pageCount: Int
        get() = (matches.size + pageSize - 1) / pageSize

    init {
        require(matches.isNotEmpty()) { "Workbench needs at least one catalog match" }
        require(pageSize > 0)
    }

    fun page(): List<AssetReference> = matches.drop(pageIndex * pageSize).take(pageSize)

    fun nextPage() {
        pageIndex = (pageIndex + 1) % pageCount
        selected = null
    }

    fun previousPage() {
        pageIndex = (pageIndex - 1 + pageCount) % pageCount
        selected = null
    }

    fun pick(slot: Int): AssetReference {
        val reference = page().getOrNull(slot - 1)
            ?: throw IllegalArgumentException("Workbench slot $slot is unavailable")
        selected = reference
        return reference
    }

    fun back() {
        selected = null
    }

    fun togglePorts(): Boolean {
        portsVisible = !portsVisible
        return portsVisible
    }

    fun addSelectedToTray(): AssetId {
        val id = requireNotNull(selected) { "No Workbench asset is selected" }.id
        addToTray(id)
        return id
    }

    fun addToTray(id: AssetId) {
        mutableTray += id
    }

    fun addAllToTray(ids: Iterable<AssetId>): Int {
        val existing = mutableTray.toHashSet()
        var added = 0
        for (id in ids) {
            if (!existing.add(id)) continue
            mutableTray += id
            added++
        }
        return added
    }

    fun growthPalette(maxAssets: Int, seed: Long): List<AssetId> {
        require(maxAssets >= 2) { "Growth palette limit must be at least two" }
        require(mutableTray.size >= 2) { "Workbench tray needs at least two Structure Assets" }
        if (mutableTray.size <= maxAssets) return tray
        val candidates = mutableTray.drop(1).toMutableList()
        Collections.shuffle(candidates, Random(seed))
        return listOf(mutableTray.first()) + candidates.take(maxAssets - 1)
    }

    fun removeFromTray(index: Int): AssetId {
        require(index in 1..mutableTray.size) { "Tray index $index is unavailable" }
        return mutableTray.removeAt(index - 1)
    }

    fun clearTray() {
        mutableTray.clear()
    }
}
