package com.caimeo.markovpaper.paper

import com.caimeo.markovpaper.assemblage.AssetId
import com.caimeo.markovpaper.assemblage.AssetReference
import com.caimeo.markovpaper.assemblage.AssetReferenceResolution
import com.caimeo.markovpaper.assemblage.CatalogSnapshot
import com.caimeo.markovpaper.assemblage.StructureAsset
import com.caimeo.markovpaper.assemblage.StructureLoadResult
import com.caimeo.markovpaper.assemblage.StructureQuery
import com.caimeo.markovpaper.workbench.AssetDescriptors
import com.caimeo.markovpaper.workbench.AssetWorkbenchState
import com.caimeo.markovpaper.workbench.buildAssetGallery
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import java.util.UUID

class AssetWorkbenchManager(
    private val catalog: CatalogSnapshot,
    private val previews: PreviewManager,
) {
    private val states = HashMap<UUID, AssetWorkbenchState>()

    fun open(player: Player, query: String?) {
        val normalized = query?.trim()?.takeIf(String::isNotEmpty)
        val matches = catalog.selectReferences(StructureQuery(text = normalized))
        if (matches.isEmpty()) {
            player.sendMessage("No Structure Assets match '${normalized.orEmpty()}'.")
            return
        }
        val previousTray = states[player.uniqueId]?.tray.orEmpty()
        val state = AssetWorkbenchState(normalized, matches, PAGE_SIZE)
        previousTray.forEach(state::addToTray)
        states[player.uniqueId] = state
        render(player, state)
    }

    fun next(player: Player) {
        val state = state(player) ?: return
        state.nextPage()
        render(player, state)
    }

    fun previous(player: Player) {
        val state = state(player) ?: return
        state.previousPage()
        render(player, state)
    }

    fun back(player: Player) {
        val state = state(player) ?: return
        state.back()
        render(player, state)
    }

    fun pick(player: Player, slot: Int) {
        val state = state(player) ?: return
        try {
            state.pick(slot)
            render(player, state)
        } catch (exception: IllegalArgumentException) {
            player.sendMessage(exception.message ?: "Invalid Workbench slot")
        }
    }

    fun togglePorts(player: Player) {
        val state = state(player) ?: return
        val visible = state.togglePorts()
        render(player, state)
        player.sendMessage("Workbench overlays ${if (visible) "enabled" else "disabled"}.")
    }

    fun add(player: Player, slot: Int?) {
        val state = state(player) ?: return
        try {
            if (slot != null) state.pick(slot)
            val id = state.addSelectedToTray()
            player.sendMessage("Added ${shortReference(id)} to tray (${state.tray.size} items).")
        } catch (exception: IllegalArgumentException) {
            player.sendMessage(exception.message ?: "Could not add Workbench asset")
        }
    }

    fun addAll(player: Player, query: String?) {
        val normalized = query?.trim()?.takeIf(String::isNotEmpty)
        val references = catalog.selectReferences(StructureQuery(text = normalized))
        if (references.isEmpty()) {
            player.sendMessage("No Structure Assets match '${normalized.orEmpty()}'.")
            return
        }
        val state = states[player.uniqueId] ?: AssetWorkbenchState(
            query = normalized,
            matches = references,
            pageSize = PAGE_SIZE,
        ).also { states[player.uniqueId] = it }
        val added = state.addAllToTray(references.map { it.id })
        player.sendMessage(
            "Added $added/${references.size} matching Structure Assets to the Workbench tray " +
                "(${state.tray.size} total)."
        )
    }

    fun showTray(player: Player) {
        val state = state(player) ?: return
        if (state.tray.isEmpty()) {
            player.sendMessage("Workbench tray is empty.")
            return
        }
        player.sendMessage("Workbench tray (${state.tray.size} items):")
        state.tray.take(TRAY_DISPLAY_LIMIT).forEachIndexed { index, id ->
            player.sendMessage("${index + 1}. ${shortReference(id)}")
        }
        if (state.tray.size > TRAY_DISPLAY_LIMIT) {
            player.sendMessage("… ${state.tray.size - TRAY_DISPLAY_LIMIT} more")
        }
    }

    fun remove(player: Player, index: Int) {
        val state = state(player) ?: return
        try {
            val removed = state.removeFromTray(index)
            player.sendMessage("Removed ${shortReference(removed)} from tray.")
        } catch (exception: IllegalArgumentException) {
            player.sendMessage(exception.message ?: "Invalid tray index")
        }
    }

    fun clear(player: Player) {
        val state = state(player) ?: return
        state.clearTray()
        player.sendMessage("Workbench tray cleared.")
    }

    fun trayAssets(player: Player): List<StructureAsset>? {
        val state = state(player) ?: return null
        if (state.tray.size < 2) {
            player.sendMessage("Workbench tray needs at least two Structure Assets.")
            return null
        }
        if (state.tray.size > MAX_SEQUENCE_ASSETS) {
            player.sendMessage(
                "Workbench animate/materialize uses every tray item and is limited to " +
                    "$MAX_SEQUENCE_ASSETS; use /mj workbench grow or remove assets."
            )
            return null
        }
        return state.tray.mapNotNull { id ->
            when (val loaded = catalog.load(id)) {
                is StructureLoadResult.Loaded -> loaded.asset
                else -> {
                    player.sendMessage("Could not load $id from Workbench tray.")
                    null
                }
            }
        }.takeIf { it.size == state.tray.size }
    }

    fun growthAssets(
        player: Player,
        count: Int,
        seed: Long,
        maxPaletteAssets: Int,
    ): List<StructureAsset>? {
        val state = state(player) ?: return null
        val ids = try {
            state.growthPalette(
                maxAssets = minOf(count, maxPaletteAssets),
                seed = seed,
            )
        } catch (exception: IllegalArgumentException) {
            player.sendMessage(exception.message ?: "Could not sample Workbench growth palette")
            return null
        }
        return ids.mapNotNull { id ->
            when (val loaded = catalog.load(id)) {
                is StructureLoadResult.Loaded -> loaded.asset
                else -> null.also { player.sendMessage("Could not load $id for Workbench growth.") }
            }
        }.takeIf { it.size == ids.size }
    }

    fun discard(playerId: UUID) {
        states.remove(playerId)
    }

    private fun render(player: Player, state: AssetWorkbenchState) {
        val references = state.selected?.let(::listOf) ?: state.page()
        val loaded = ArrayList<Pair<AssetReference, StructureAsset>>(references.size)
        for (reference in references) {
            val asset = load(player, reference) ?: return
            loaded += reference to asset
        }
        val gallery = buildAssetGallery(loaded.map { it.second })
        previews.previewWorkbench(
            player = player,
            label = if (state.selected == null) {
                "Workbench page ${state.pageNumber}/${state.pageCount}"
            } else {
                "Workbench ${state.selected!!.shortest}"
            },
            gallery = gallery,
            portsVisible = state.portsVisible,
        )
        if (state.selected == null) {
            player.sendMessage(
                "Workbench page ${state.pageNumber}/${state.pageCount}; " +
                    "query=${state.query ?: "all"}."
            )
            loaded.forEachIndexed { index, (reference, asset) ->
                val descriptor = AssetDescriptors.describe(asset)
                val text = "${index + 1}. [${family(asset)}] ${reference.shortest} " +
                    "${descriptor.size.x}x${descriptor.size.y}x${descriptor.size.z} " +
                    "solid=${descriptor.solidCells} controls=${descriptor.controls.size}"
                player.sendMessage(
                    Component.text(text, NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.runCommand("/mj workbench pick ${index + 1}"))
                        .hoverEvent(HoverEvent.showText(Component.text("Preview this Structure Asset")))
                )
            }
            player.sendMessage(
                Component.text("[Prev]", NamedTextColor.YELLOW)
                    .clickEvent(ClickEvent.runCommand("/mj workbench prev"))
                    .append(Component.space())
                    .append(
                        Component.text("[Next]", NamedTextColor.YELLOW)
                            .clickEvent(ClickEvent.runCommand("/mj workbench next"))
                    )
            )
        } else {
            val (reference, asset) = loaded.single()
            val descriptor = AssetDescriptors.describe(asset)
            player.sendMessage(
                "${reference.shortest}: ${descriptor.size.x}x${descriptor.size.y}x${descriptor.size.z}, " +
                    "solid=${descriptor.solidCells}, air=${descriptor.authoredAirCells}."
            )
            player.sendMessage("Catalog tags: ${catalogTags(asset)}")
            player.sendMessage(
                "Materials: " + descriptor.materialCounts.entries
                    .sortedByDescending { it.value }
                    .take(4)
                    .joinToString { (material, count) -> "$material=$count" }
            )
            player.sendMessage(
                "Boundary profiles: " + descriptor.boundaries.entries.joinToString { (face, profile) ->
                    "${face.name.lowercase()}=${profile.solidCells}/${profile.authoredAirCells}"
                } + " (solid/air)"
            )
            descriptor.controls.forEachIndexed { index, marker ->
                player.sendMessage(
                    "${marker.kind.name} C$index " +
                        "${marker.position.x},${marker.position.y},${marker.position.z} " +
                        "${marker.orientation ?: "orientation-unknown"}"
                )
            }
            player.sendMessage(
                Component.text("[Add to tray]", NamedTextColor.GREEN)
                    .clickEvent(ClickEvent.runCommand("/mj workbench add"))
                    .append(Component.space())
                    .append(
                        Component.text("[Back]", NamedTextColor.YELLOW)
                            .clickEvent(ClickEvent.runCommand("/mj workbench back"))
                    )
                    .append(Component.space())
                    .append(
                        Component.text("[Toggle overlays]", NamedTextColor.LIGHT_PURPLE)
                            .clickEvent(ClickEvent.runCommand("/mj workbench ports"))
                    )
            )
        }
    }

    private fun load(player: Player, reference: AssetReference): StructureAsset? =
        when (val loaded = catalog.load(reference.id)) {
            is StructureLoadResult.Loaded -> loaded.asset
            else -> null.also { player.sendMessage("Could not load ${reference.shortest}.") }
        }

    private fun shortReference(id: AssetId): String =
        when (val resolution = catalog.resolve(id.toString())) {
            is AssetReferenceResolution.Resolved -> resolution.reference.shortest
            else -> id.toString()
        }

    private fun family(asset: StructureAsset): String = asset.tags
        .firstOrNull { it.value.startsWith("family:") }
        ?.value
        ?.substringAfter(':')
        ?: "uncategorized"

    private fun catalogTags(asset: StructureAsset): String = asset.tags
        .map { it.value }
        .filter { it.startsWith("family:") || it.startsWith("dimension:") }
        .sorted()
        .joinToString()

    private fun state(player: Player): AssetWorkbenchState? = states[player.uniqueId]
        ?: null.also { player.sendMessage("Open the Workbench first: /mj workbench open [query]") }

    companion object {
        private const val PAGE_SIZE = 6
        private const val TRAY_DISPLAY_LIMIT = 20
        private const val MAX_SEQUENCE_ASSETS = 32
    }
}
