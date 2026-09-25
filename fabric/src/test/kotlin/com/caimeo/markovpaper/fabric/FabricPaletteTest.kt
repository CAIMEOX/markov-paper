package com.caimeo.markovpaper.fabric

import com.caimeo.markovpaper.minecraft.ModelCatalog
import net.minecraft.SharedConstants
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class FabricPaletteTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `all bundled model palettes resolve to native Minecraft block states`() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
        val catalog = ModelCatalog(directory).apply { installBundled() }
        val states = catalog.names().flatMap { name ->
            catalog.prepare(name, catalog.profile(name).defaultSize).palette.values
        }.toSet()
        assertTrue(states.size > 20)
        for (state in states) {
            val parsed = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, state.canonical, false).blockState()
            assertTrue(BlockStateParser.serialize(parsed).startsWith(state.canonical.substringBefore('[')), state.canonical)
        }
    }
}
