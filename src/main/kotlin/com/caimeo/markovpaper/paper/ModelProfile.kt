package com.caimeo.markovpaper.paper

import com.caimeo.markovpaper.assemblage.BlockStateSpec
import com.caimeo.markovpaper.xml.GridSize
import com.caimeo.markovpaper.xml.HeightProjection
import com.caimeo.markovpaper.xml.MarkovXmlCompiler
import com.caimeo.markovpaper.xml.ModelResources
import com.caimeo.markovpaper.xml.PreparedMarkovModel
import java.io.StringReader
import java.util.Properties

/** Paper presentation metadata, independent of a model's filename and of Bukkit. */
internal class ModelProfile private constructor(private val properties: Map<String, String>) {
    val defaultSize = positive("default-size", 15)
    val rewrites = positive("rewrites", 4)
    val hybrid = properties["hybrid"]?.let { requireNotNull(it.toBooleanStrictOrNull()) { "hybrid must be true or false" } } ?: false
    private val minimumSize = positive("min-size", 1)
    private val input = (properties["input"] ?: "size size size").split(Regex("\\s+"))
    private val up = properties["up"] ?: "auto"
    private val blocks = properties.filterKeys { it.startsWith("block.") }.mapKeys { (key, _) ->
        require(key.removePrefix("block.").length == 1) { "Block keys need exactly one symbol: $key" }
        key.last()
    }.mapValues { (_, state) -> BlockStateSpec(state) }
    private val columns = buildMap {
        for ((key, value) in properties.filterKeys { it.startsWith("column.") }) {
            val symbols = key.removePrefix("column.")
            require(symbols.isNotEmpty()) { "Column keys need source symbols" }
            for (symbol in symbols) require(put(symbol, value) == null) { "Duplicate projection column '$symbol'" }
        }
    }
    private val defaultHeight = properties["height"]?.let { positive("height", 1) }
    private val minimumHeight = positive("min-height", 1)
    private val outputSymbols = properties["output-symbols"]?.toList()

    init {
        require(properties.keys.all { it in KEYS || it.startsWith("block.") || it.startsWith("column.") }) {
            "Unknown model profile keys: ${properties.keys.filterNot { it in KEYS || it.startsWith("block.") || it.startsWith("column.") }}"
        }
        require(input.size == 3 && input.all { it == "size" || (it.toIntOrNull() ?: 0) > 0 }) {
            "input needs three positive dimensions or 'size' placeholders"
        }
        require(defaultSize >= minimumSize) { "default-size must be at least min-size" }
        require(input.any { it == "size" } || defaultSize == input.first().toInt()) {
            "A fixed input requires default-size to equal its X dimension"
        }
        require(up in setOf("auto", "y", "z")) { "up must be auto, y or z" }
        if (columns.isEmpty()) require(defaultHeight == null && outputSymbols == null && "min-height" !in properties) {
            "Height settings require projection columns"
        } else {
            require(defaultHeight != null && defaultHeight >= minimumHeight && outputSymbols != null) {
                "Projection columns require output-symbols and height >= min-height"
            }
        }
    }

    fun prepare(xml: String, size: Int, height: Int?, resources: ModelResources, maxCells: Long): PreparedPaperModel {
        require(size >= minimumSize) { "Model size must be at least $minimumSize" }
        require(input.any { it == "size" } || size == input.first().toInt()) {
            "This model uses fixed input ${input.joinToString("×")}; request size ${input.first()}"
        }
        val dimensions = input.map { if (it == "size") size else it.toInt() }
        val source = MarkovXmlCompiler.prepare(xml, dimensions[0], dimensions[1], dimensions[2], resources, maxCells)
        val projected = if (columns.isEmpty()) {
            require(height == null) { "Height requires a model profile with projection columns" }
            source
        } else {
            val requestedHeight = height ?: requireNotNull(defaultHeight)
            require(requestedHeight >= minimumHeight) { "Projection height must be at least $minimumHeight" }
            HeightProjection.prepare(source, requestedHeight, requireNotNull(outputSymbols), columns, maxCells)
        }
        val palette = projected.symbols.mapIndexed { index, symbol ->
            index.toByte() to (blocks[symbol] ?: blocks[symbol.uppercaseChar()] ?: if (index == 0) AIR else
                DEFAULT_BLOCKS[symbol.uppercaseChar().toString()]?.let(::BlockStateSpec) ?: FALLBACK[(index - 1) % FALLBACK.size])
        }.toMap()
        return PreparedPaperModel(projected, palette, when (up) {
            "y" -> false
            "z" -> true
            else -> columns.isNotEmpty() || projected.size.z > 1
        })
    }

    private fun positive(key: String, default: Int): Int = (properties[key]?.let {
        requireNotNull(it.toIntOrNull()) { "$key must be an integer" }
    } ?: default).also { require(it > 0) { "$key must be positive" } }

    companion object {
        private val KEYS = setOf("default-size", "min-size", "rewrites", "input", "up", "hybrid", "height", "min-height", "output-symbols")
        private val AIR = BlockStateSpec("minecraft:air")
        private val DEFAULT_BLOCKS = readProfileProperties(requireNotNull(ModelProfile::class.java.getResource("/model-profiles/palette.properties")).readText())
        private val FALLBACK = DEFAULT_BLOCKS.getValue("fallback").split(' ').map(::BlockStateSpec)
        fun parse(text: String): ModelProfile = ModelProfile(readProfileProperties(text))
    }
}

internal data class PreparedPaperModel(
    val execution: PreparedMarkovModel,
    val palette: Map<Byte, BlockStateSpec>,
    val modelZIsUp: Boolean,
) {
    val size: GridSize get() = execution.size
}

private fun readProfileProperties(text: String): Map<String, String> {
    val properties = object : Properties() {
        override fun put(key: Any, value: Any): Any? {
            require(!containsKey(key)) { "Duplicate model profile key '$key'" }
            return super.put(key, value)
        }
    }
    properties.load(StringReader(text))
    return properties.stringPropertyNames().associateWith { properties.getProperty(it).trim() }
}
