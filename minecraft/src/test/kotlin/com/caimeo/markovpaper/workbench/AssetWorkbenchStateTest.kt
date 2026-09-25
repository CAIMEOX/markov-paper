package com.caimeo.markovpaper.workbench

import com.caimeo.markovpaper.assemblage.AssetId
import com.caimeo.markovpaper.assemblage.AssetReference
import kotlin.test.Test
import kotlin.test.assertEquals

class AssetWorkbenchStateTest {
    @Test
    fun `page selection adds the chosen short reference to tray`() {
        val references = (1..8).map { index ->
            AssetReference(AssetId("test:asset_$index"), "asset_$index")
        }
        val state = AssetWorkbenchState(
            query = "asset",
            matches = references,
            pageSize = 6,
        )

        assertEquals((1..6).map { "asset_$it" }, state.page().map { it.shortest })
        state.nextPage()
        assertEquals(listOf("asset_7", "asset_8"), state.page().map { it.shortest })
        assertEquals(references[7], state.pick(2))
        state.addSelectedToTray()

        assertEquals(listOf(references[7].id), state.tray)
    }

    @Test
    fun `add all deduplicates a search while growth samples the whole tray deterministically`() {
        val references = (1..100).map { index ->
            AssetReference(AssetId("test:asset_$index"), "asset_$index")
        }
        val state = AssetWorkbenchState("asset", references)
        state.addToTray(references.first().id)

        assertEquals(99, state.addAllToTray(references.map { it.id }))
        assertEquals(0, state.addAllToTray(references.map { it.id }))
        assertEquals(100, state.tray.size)

        val first = state.growthPalette(maxAssets = 12, seed = 42)
        val repeated = state.growthPalette(maxAssets = 12, seed = 42)
        val different = state.growthPalette(maxAssets = 12, seed = 43)
        assertEquals(12, first.size)
        assertEquals(references.first().id, first.first())
        assertEquals(first, repeated)
        kotlin.test.assertNotEquals(first, different)
    }
}
