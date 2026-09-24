package com.caimeo.markovpaper.xml

import com.caimeo.markovpaper.engine.AllNode
import com.caimeo.markovpaper.engine.ConvolutionKernel
import com.caimeo.markovpaper.engine.ConvolutionNode
import com.caimeo.markovpaper.engine.ConvolutionRule
import com.caimeo.markovpaper.engine.CubeSymmetry
import com.caimeo.markovpaper.engine.FieldGuidedOneNode
import com.caimeo.markovpaper.engine.FieldSpec
import com.caimeo.markovpaper.engine.InferenceOneNode
import com.caimeo.markovpaper.engine.MarkovNode
import com.caimeo.markovpaper.engine.MappingNode
import com.caimeo.markovpaper.engine.MappingRule
import com.caimeo.markovpaper.engine.Scale
import com.caimeo.markovpaper.engine.StepDelta
import com.caimeo.markovpaper.engine.TileWfcNode
import com.caimeo.markovpaper.engine.ObservationSpec
import com.caimeo.markovpaper.engine.OneNode
import com.caimeo.markovpaper.engine.ParallelNode
import com.caimeo.markovpaper.engine.PathNode
import com.caimeo.markovpaper.engine.RewriteNode
import com.caimeo.markovpaper.engine.Rule
import com.caimeo.markovpaper.engine.SequenceNode
import com.caimeo.markovpaper.engine.VoxelGrid
import org.w3c.dom.Element
import java.util.Random
import javax.imageio.ImageIO

object MarkovXmlCompiler {
    fun compile(
        xml: String,
        sizeX: Int,
        sizeY: Int,
        sizeZ: Int,
        seed: Long,
        resources: ModelResources = ModelResources(),
        maxCells: Long = 2_000_000L,
        initialState: ByteArray? = null,
    ): CompiledMarkovModel = prepare(xml, sizeX, sizeY, sizeZ, resources, maxCells).create(seed, initialState)

    fun prepare(
        xml: String,
        sizeX: Int,
        sizeY: Int,
        sizeZ: Int,
        resources: ModelResources = ModelResources(),
        maxCells: Long = 2_000_000L,
    ): PreparedMarkovModel {
        require(maxCells in 1..Int.MAX_VALUE.toLong())
        val root = XmlDocuments.parse(xml)
        val grids = ArrayList<GridDefinition>()
        fun grid(element: Element, size: GridSize, folder: String?): GridDefinition {
            require(size.cellCount <= maxCells) { "Grid $size exceeds $maxCells cells" }
            return gridDefinition(element, size, folder).also { grids += it }
        }
        val initial = grid(root, GridSize(sizeX, sizeY, sizeZ), null)
        val plan = planNode(root, null, initial, resources, ::grid)
        val origin = root.booleanAttribute("origin", false)
        require(!origin || initial.symbols.size >= 2) { "origin requires at least two values" }
        return PreparedMarkovModel(plan.output.size, plan.output.symbols, initial.size, initial.symbols) { seed, input ->
            val random = Random(seed)
            val contexts = grids.associateWith { definition ->
                val size = definition.size
                CompilerContext(VoxelGrid(size.x, size.y, size.z), random)
            }
            if (input != null) {
                val target = contexts.getValue(initial).grid
                for (z in 0 until sizeZ) for (y in 0 until sizeY) for (x in 0 until sizeX) {
                    target[x, y, z] = input[target.index(x, y, z)]
                }
            } else if (origin) {
                contexts.getValue(initial).grid[sizeX / 2, sizeY / 2, sizeZ / 2] = 1
            }
            val runtime = ModelRuntime(contexts, initial)
            val node = plan.build(runtime)
            val activeState = {
                val definition = runtime.active
                ModelState(contexts.getValue(definition).grid.readOnly(), definition.symbols)
            }
            if (plan.output === initial) {
                CompiledMarkovModel(contexts.getValue(initial).grid, node, initial.symbols, activeState)
            } else {
                val size = plan.output.size
                val display = VoxelGrid(size.x, size.y, size.z)
                CompiledMarkovModel(display, ModelFrameNode(node, display, plan.output.symbols,
                    resetActive = { runtime.active = initial }) {
                    contexts.getValue(runtime.active).grid to runtime.active.symbols
                }, plan.output.symbols, activeState)
            }
        }
    }

    private fun gridDefinition(root: Element, size: GridSize, inheritedFolder: String?): GridDefinition {
        val symbols = root.requiredAttribute("values")
            .filterNot(Char::isWhitespace)
            .toList()
        require(symbols.isNotEmpty()) { "values must not be empty" }
        require(symbols.distinct().size == symbols.size) { "values must be unique" }
        require(symbols.size <= 31) { "At most 31 values are supported" }

        val values = symbols.withIndex().associate { (index, symbol) -> symbol to index.toByte() }
        val waves = values.mapValues { (_, value) -> 1 shl value.toInt() }.toMutableMap()
        waves['*'] = (1 shl symbols.size) - 1
        for (union in root.gridUnions()) {
            val symbolText = union.requiredAttribute("symbol")
            require(symbolText.length == 1) { "union symbol must be one character" }
            val symbol = symbolText.single()
            require(symbol !in waves) { "Duplicate value or union '$symbol'" }
            waves[symbol] = union.requiredAttribute("values").fold(0) { mask, value ->
                mask or (1 shl requireNotNull(values[value]) {
                    "Unknown union value '$value'"
                }.toInt())
            }
        }

        return GridDefinition(
            size, symbols, values, waves, root.optionalAttribute("folder") ?: inheritedFolder,
        )
    }

    private fun planNode(
        element: Element,
        inheritedSymmetry: String?,
        input: GridDefinition,
        resources: ModelResources,
        grid: (Element, GridSize, String?) -> GridDefinition,
    ): NodePlan {
        val symmetry = element.optionalAttribute("symmetry") ?: inheritedSymmetry
        return when (element.tagName) {
            "sequence", "markov" -> planChildren(element, symmetry, input, resources, grid)
            "wfc" -> {
                require(element.optionalAttribute("sample") == null) {
                    "Overlapping WFC <wfc sample=...> is not supported; use tileset=..."
                }
                val periodic = element.booleanAttribute("periodic", false)
                val shannon = element.booleanAttribute("shannon", false)
                val name = element.requiredAttribute("tileset")
                val set = TileSetResource.load(name, element.requiredAttribute("values").filterNot(Char::isWhitespace),
                    resources, element.optionalAttribute("tiles") ?: name)
                val overlap = element.intAttribute("overlap", 0)
                val overlapZ = element.intAttribute("overlapz", 0)
                require(!set.nonUniform || !periodic && overlap == 0 && overlapZ == 0) {
                    "NUT tilesets currently require periodic=False and overlap=overlapz=0"
                }
                val strides = intArrayOf(
                    Math.subtractExact(set.sizeX, overlap), Math.subtractExact(set.sizeY, overlap),
                    Math.subtractExact(set.sizeZ, overlapZ),
                )
                require(strides.all { it > 0 }) { "WFC overlap must be smaller than the tile size" }
                fun extent(count: Int, stride: Int, tile: Int) = Math.toIntExact((count - 1L) * stride + tile)
                val output = grid(element, GridSize(
                    extent(input.size.x, strides[0], set.sizeX),
                    extent(input.size.y, strides[1], set.sizeY),
                    extent(input.size.z, strides[2], set.sizeZ),
                ), input.folder)
                require(input.size.cellCount * set.variants.size <= 32_000_000L) {
                    "WFC wave exceeds 32 million cell/variant candidates"
                }
                val allowed = linkedMapOf<Byte, BooleanArray>()
                for (rule in element.directChildren("rule")) {
                    val symbol = rule.requiredAttribute("in").single()
                    val value = requireNotNull(input.values[symbol]) { "Unknown WFC input '$symbol'" }
                    require(value !in allowed) { "Duplicate WFC constraint for '$symbol'" }
                    val names = rule.requiredAttribute("out").split('|').map(String::trim).toSet()
                    require(names.all { candidate -> set.variants.any { it.name == candidate } }) {
                        "Unknown tile in WFC constraint '${rule.requiredAttribute("out")}' for '$name'"
                    }
                    allowed[value] = BooleanArray(set.variants.size) { set.variants[it].name in names }
                }
                val tries = element.intAttribute("tries", 1_000)
                require(tries > 0) { "WFC tries must be positive" }
                val children = planChildren(element, symmetry, output, resources, grid)
                NodePlan(children.output) { runtime ->
                    val wfc = TileWfcNode(runtime.context(input).grid, runtime.context(output).grid,
                        set, allowed, runtime.context(input).random,
                        strides[0], strides[1], strides[2], name, tries,
                        periodic = periodic, shannon = shannon)
                    SequenceNode(listOf(activate(wfc, output, runtime), children.build(runtime)))
                }
            }
            "map" -> {
                val scales = element.requiredAttribute("scale").trim().split(Regex("\\s+")).map { text ->
                    val parts = text.split('/')
                    require(parts.size in 1..2) { "Invalid map scale '$text'" }
                    Scale(parts[0].toInt(), parts.getOrNull(1)?.toInt() ?: 1)
                }
                require(scales.size == 3) { "map scale must contain three components" }
                fun scaled(value: Int, scale: Scale) = Math.toIntExact(value.toLong() * scale.numerator / scale.denominator)
                val output = grid(element, GridSize(scaled(input.size.x, scales[0]),
                    scaled(input.size.y, scales[1]), scaled(input.size.z, scales[2])), input.folder)
                val rules = compileMappingRules(element, input, output, symmetry, resources)
                val children = planChildren(element, symmetry, output, resources, grid)
                NodePlan(children.output) { runtime ->
                    val map = MappingNode(runtime.context(input).grid, runtime.context(output).grid,
                        rules, scales[0], scales[1], scales[2])
                    SequenceNode(listOf(activate(map, output, runtime), children.build(runtime)))
                }
            }
            in ruleNodeNames -> {
                val create = compileRuleNode(element, inheritedSymmetry,
                    RuleContext(input.size, input.values, input.waves, input.folder, resources))
                NodePlan(input) { runtime -> create(runtime.context(input)) }
            }
            else -> error("Unsupported MarkovJunior node <${element.tagName}>")
        }
    }

    private fun planChildren(
        element: Element, symmetry: String?, input: GridDefinition,
        resources: ModelResources, grid: (Element, GridSize, String?) -> GridDefinition,
    ): NodePlan {
        val children = element.directElements().filter { child ->
            when (child.tagName) {
                "union" -> false
                "rule" -> { require(element.tagName in setOf("wfc", "map")); false }
                else -> true
            }
        }.map { planNode(it, symmetry, input, resources, grid) }
        val transition = children.indexOfFirst { it.output !== input }
        require(transition < 0 || transition == children.lastIndex) {
            "A grid-changing <wfc> or <map> must be the final child of its branch; put later rules inside it"
        }
        return NodePlan(children.lastOrNull()?.output ?: input) { runtime ->
            val nodes = children.map { it.build(runtime) }
            if (element.tagName == "markov") {
                // Once a grid transition starts, MJ never returns to the parent grid.
                if (transition >= 0) SequenceNode(listOf(MarkovNode(nodes.dropLast(1)), nodes.last()))
                else MarkovNode(nodes)
            } else SequenceNode(nodes)
        }
    }

    private fun activate(node: RewriteNode, output: GridDefinition, runtime: ModelRuntime) = object : RewriteNode {
        override fun advance(): StepDelta? {
            runtime.active = output
            return node.advance()
        }
        override fun reset() = node.reset()
    }

    private fun compileRuleNode(
        element: Element,
        inheritedSymmetry: String?,
        context: RuleContext,
    ): (CompilerContext) -> RewriteNode {
        val symmetry = element.optionalAttribute("symmetry") ?: inheritedSymmetry
        val steps = element.intAttribute("steps", 0)
        require(steps >= 0) { "steps must be non-negative" }
        return when (element.tagName) {
            "one" -> compileOne(element, symmetry, steps, context)
            "all" -> parseRules(element, symmetry, context).let { rules ->
                { runtime -> AllNode(runtime.grid, rules, runtime.random, steps) }
            }
            "prl" -> parseRules(element, symmetry, context).let { rules ->
                { runtime -> ParallelNode(runtime.grid, rules, runtime.random, steps) }
            }
            "path" -> compilePath(element, context)
            "convolution" -> compileConvolution(element, context)
            else -> error("Unsupported MarkovJunior node <${element.tagName}>")
        }
    }

    private fun compilePath(element: Element, context: RuleContext): (CompilerContext) -> RewriteNode {
        val from = element.requiredAttribute("from")
        val colorText = element.optionalAttribute("color") ?: from.first().toString()
        require(colorText.length == 1) { "Path color must be one value" }
        val start = valuesMask(from, context)
        val finish = valuesMask(element.requiredAttribute("to"), context)
        val substrate = valuesMask(element.requiredAttribute("on"), context)
        val color = requireNotNull(context.values[colorText.single()]) { "Unknown path color '$colorText'" }
        val inertia = element.booleanAttribute("inertia", false)
        val longest = element.booleanAttribute("longest", false)
        val edges = element.booleanAttribute("edges", false)
        val vertices = element.booleanAttribute("vertices", false)
        return { runtime -> PathNode(runtime.grid, start, finish, substrate, color, runtime.random,
            inertia, longest, edges, vertices) }
    }

    private fun compileOne(
        element: Element,
        symmetry: String?,
        steps: Int,
        context: RuleContext,
    ): (CompilerContext) -> RewriteNode {
        val rules = parseRules(element, symmetry, context)
        val observationElements = element.directChildren("observe")
        val fieldElements = element.directChildren("field")
        require(observationElements.isEmpty() || fieldElements.isEmpty()) {
            "Combining field and observe in one node is not supported yet"
        }
        if (observationElements.isEmpty() && fieldElements.isEmpty()) {
            return { runtime -> OneNode(runtime.grid, rules, runtime.random, steps) }
        }
        if (fieldElements.isNotEmpty()) {
            val fields = fieldElements.map { field ->
                val forText = field.requiredAttribute("for")
                require(forText.length == 1)
                val forValue = requireNotNull(context.values[forText.single()]) {
                    "Unknown field value '$forText'"
                }
                val from = field.optionalAttribute("from")
                val zeroValues = from ?: field.requiredAttribute("to")
                FieldSpec(
                    forValue = forValue,
                    zeroMask = valuesMask(zeroValues, context),
                    substrateMask = valuesMask(field.requiredAttribute("on"), context),
                    inversed = from != null,
                    recompute = field.booleanAttribute("recompute", false),
                    essential = field.booleanAttribute("essential", false),
                )
            }
            require(fields.map(FieldSpec::forValue).distinct().size == fields.size) {
                "Each field value may only be declared once"
            }
            return { runtime -> FieldGuidedOneNode(
                grid = runtime.grid,
                rules = rules,
                fields = fields,
                valueCount = context.values.size,
                random = runtime.random,
                maxSteps = steps,
            ) }
        }
        require(!element.booleanAttribute("search", false)) {
            "search=True inference is not supported yet"
        }
        require(element.doubleAttribute("temperature", 0.0) == 0.0) {
            "temperature-based inference is not supported yet"
        }
        val observations = observationElements.map { observation ->
            val valueText = observation.requiredAttribute("value")
            require(valueText.length == 1)
            val value = requireNotNull(context.values[valueText.single()]) {
                "Unknown observed value '$valueText'"
            }
            val fromText = observation.optionalAttribute("from") ?: valueText
            require(fromText.length == 1)
            val from = requireNotNull(context.values[fromText.single()]) {
                "Unknown observation source '$fromText'"
            }
            val toMask = observation.requiredAttribute("to").fold(0) { mask, symbol ->
                mask or (1 shl requireNotNull(context.values[symbol]) {
                    "Unknown observation target '$symbol'"
                }.toInt())
            }
            ObservationSpec(value, from, toMask)
        }
        require(observations.map(ObservationSpec::value).distinct().size == observations.size) {
            "Each observed value may only be declared once"
        }
        return { runtime -> InferenceOneNode(
            grid = runtime.grid,
            rules = rules,
            observations = observations,
            valueCount = context.values.size,
            random = runtime.random,
            maxSteps = steps,
        ) }
    }

    private fun valuesMask(symbols: String, context: RuleContext): Int =
        symbols.fold(0) { mask, symbol ->
            mask or (1 shl requireNotNull(context.values[symbol]) {
                "Unknown value '$symbol'"
            }.toInt())
        }

    private fun compileConvolution(
        element: Element,
        context: RuleContext,
    ): (CompilerContext) -> RewriteNode {
        val ruleElements = element.directChildren("rule").ifEmpty { listOf(element) }
        val rules = ruleElements.map { ruleElement ->
            val inputText = ruleElement.requiredAttribute("in")
            val outputText = ruleElement.requiredAttribute("out")
            require(inputText.length == 1 && outputText.length == 1) {
                "Convolution in/out must be single values"
            }
            val valuesText = ruleElement.optionalAttribute("values")
            val sumsText = ruleElement.optionalAttribute("sum")
            require((valuesText == null) == (sumsText == null)) {
                "Convolution values and sum must be specified together"
            }
            val valuesMask = valuesText?.fold(0) { mask, symbol ->
                mask or (1 shl requireNotNull(context.values[symbol]) {
                    "Unknown convolution value '$symbol'"
                }.toInt())
            }

            ConvolutionRule(
                input = requireNotNull(context.values[inputText.single()]) {
                    "Unknown convolution input '$inputText'"
                },
                output = requireNotNull(context.values[outputText.single()]) {
                    "Unknown convolution output '$outputText'"
                },
                valuesMask = valuesMask,
                sums = sumsText?.let(::parseSums),
                probability = ruleElement.doubleAttribute("p", 1.0),
            )
        }

        val kernel = ConvolutionKernel.fromXml(element.requiredAttribute("neighborhood"), context.size.z > 1)
        val periodic = element.booleanAttribute("periodic", false)
        val steps = element.intAttribute("steps", 0)
        return { runtime -> ConvolutionNode(runtime.grid, rules, kernel, periodic, runtime.random, steps) }
    }

    private fun parseSums(text: String): Set<Int> = buildSet {
        for (part in text.split(',')) {
            val bounds = part.trim().split("..")
            when (bounds.size) {
                1 -> add(bounds.single().toInt())
                2 -> addAll(bounds[0].toInt()..bounds[1].toInt())
                else -> error("Invalid convolution sum '$part'")
            }
        }
    }

    private fun parseRules(
        node: Element,
        inheritedSymmetry: String?,
        context: RuleContext,
    ): List<Rule> {
        val unsupportedChildren = node.directElements().filter {
            it.tagName != "rule" && it.tagName != "observe" && it.tagName != "field"
        }
        require(unsupportedChildren.isEmpty()) {
            "Unsupported <${unsupportedChildren.first().tagName}> in <${node.tagName}>"
        }
        val ruleElements = node.directChildren("rule").ifEmpty { listOf(node) }
        return ruleElements.flatMap { element ->
            val (input, output) = parseRulePatterns(element, context)
            require(input.dimensions == output.dimensions) { "Rule input and output sizes differ" }

            val inputMasks = IntArray(input.cells.size) { index ->
                val symbol = input.cells[index]
                requireNotNull(context.waves[symbol]) { "Unknown input symbol '$symbol'" }
            }
            val outputValues = ByteArray(output.cells.size) { index ->
                val symbol = output.cells[index]
                if (symbol == '*') Rule.UNCHANGED else {
                    requireNotNull(context.values[symbol]) { "Unknown output symbol '$symbol'" }
                }
            }
            val baseRule = Rule.masked(
                input = inputMasks,
                output = outputValues,
                sizeX = input.sizeX,
                sizeY = input.sizeY,
                sizeZ = input.sizeZ,
                probability = element.doubleAttribute("p", 1.0),
            )

            CubeSymmetry.expand(
                rule = baseRule,
                is3D = context.size.z > 1,
                group = element.optionalAttribute("symmetry") ?: inheritedSymmetry,
            )
        }
    }

    private fun parseRulePatterns(
        element: Element,
        context: RuleContext,
    ): Pair<Pattern, Pattern> = readPatterns(element, context.size.z == 1, context.folder, context.resources)

    private fun readPatterns(
        element: Element,
        is2D: Boolean,
        folder: String?,
        resources: ModelResources,
    ): Pair<Pattern, Pattern> {
        val file = element.optionalAttribute("file")
        if (file == null) {
            fun pattern(inline: String, resource: String): Pattern {
                val text = element.optionalAttribute(inline)
                require(text == null || element.optionalAttribute(resource) == null) {
                    "A rule cannot specify both '$inline' and '$resource'"
                }
                return text?.let(::parsePattern) ?: loadPattern(
                    element.requiredAttribute(resource), element.requiredAttribute("legend"), is2D, folder, resources,
                )
            }
            return pattern("in", "fin") to pattern("out", "fout")
        }
        require(
            element.optionalAttribute("in") == null &&
                element.optionalAttribute("out") == null &&
                element.optionalAttribute("fin") == null &&
                element.optionalAttribute("fout") == null
        ) { "A file rule cannot also declare in/out/fin/fout" }
        val resource = loadPattern(file, element.requiredAttribute("legend"), is2D, folder, resources)
        require(resource.sizeX % 2 == 0) { "Glued rule resource '$file' must have even width" }
        val halfWidth = resource.sizeX / 2
        fun half(offset: Int): Pattern {
            val cells = CharArray(halfWidth * resource.sizeY * resource.sizeZ)
            for (z in 0 until resource.sizeZ) for (y in 0 until resource.sizeY) for (x in 0 until halfWidth) {
                cells[x + y * halfWidth + z * halfWidth * resource.sizeY] =
                    resource.cells[x + offset + y * resource.sizeX + z * resource.sizeX * resource.sizeY]
            }
            return Pattern(cells, halfWidth, resource.sizeY, resource.sizeZ)
        }
        return half(0) to half(halfWidth)
    }

    private fun loadPattern(
        file: String, legend: String, is2D: Boolean, folder: String?, resources: ModelResources,
    ): Pattern {
        val path = "rules/${folder?.let { "$it/" }.orEmpty()}$file.${if (is2D) "png" else "vox"}"
        val stream = resources.open(path)
        return if (is2D) {
            val image = stream.use { ImageIO.read(it) }
            requireNotNull(image) { "Could not decode rule image '$path'" }
            val ordinals = ArrayList<Int>()
            val characters = CharArray(image.width * image.height)
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    val color = image.getRGB(x, y)
                    var ordinal = ordinals.indexOf(color)
                    if (ordinal < 0) {
                        ordinal = ordinals.size
                        ordinals += color
                    }
                    require(ordinal < legend.length) {
                        "Rule image '$path' uses more colors than legend '$legend'"
                    }
                    characters[x + y * image.width] = legend[ordinal]
                }
            }
            Pattern(characters, image.width, image.height, 1)
        } else {
            val voxels = VoxelResource.read(stream, legend)
            Pattern(voxels.cells, voxels.sizeX, voxels.sizeY, voxels.sizeZ)
        }
    }

    private fun compileMappingRules(
        map: Element, input: GridDefinition, output: GridDefinition,
        symmetry: String?, resources: ModelResources,
    ): List<MappingRule> = map.directChildren("rule").flatMap { rule ->
        val (i, o) = readPatterns(rule, input.size.z == 1, output.folder, resources)
        CubeSymmetry.transforms(input.size.z > 1, rule.optionalAttribute("symmetry") ?: symmetry)
            .map { transform ->
                i.transformed(transform) to o.transformed(transform)
            }
            .distinctBy { (a, b) -> listOf(a.dimensions, b.dimensions, a.cells.toList(), b.cells.toList()) }
            .map { (a, b) ->
                MappingRule(
                    input = IntArray(a.cells.size) { index ->
                        requireNotNull(input.waves[a.cells[index]]) { "Unknown map input '${a.cells[index]}'" }
                    },
                    inputSizeX = a.sizeX, inputSizeY = a.sizeY, inputSizeZ = a.sizeZ,
                    output = ByteArray(b.cells.size) { index ->
                        val symbol = b.cells[index]
                        if (symbol == '*') Rule.UNCHANGED else
                            requireNotNull(output.values[symbol]) { "Unknown map output '$symbol'" }
                    },
                    outputSizeX = b.sizeX, outputSizeY = b.sizeY, outputSizeZ = b.sizeZ,
                )
            }
    }

    private fun parsePattern(text: String): Pattern {
        val layers = text.trim().split(Regex("\\s+")).asReversed()
        val rows = layers.map { it.split('/') }
        val sizeZ = rows.size
        val sizeY = rows.first().size
        val sizeX = rows.first().first().length
        require(sizeX > 0 && sizeY > 0)
        require(rows.all { layer ->
            layer.size == sizeY && layer.all { row -> row.length == sizeX }
        }) { "Pattern is not rectangular: $text" }

        val cells = CharArray(sizeX * sizeY * sizeZ)
        for (z in 0 until sizeZ) {
            for (y in 0 until sizeY) {
                for (x in 0 until sizeX) {
                    cells[x + y * sizeX + z * sizeX * sizeY] = rows[z][y][x]
                }
            }
        }
        return Pattern(cells, sizeX, sizeY, sizeZ)
    }

    private data class Pattern(
        val cells: CharArray,
        val sizeX: Int,
        val sizeY: Int,
        val sizeZ: Int,
    ) {
        val dimensions: Triple<Int, Int, Int> = Triple(sizeX, sizeY, sizeZ)

        fun transformed(transform: CubeSymmetry.AxisTransform): Pattern {
            val sourceSizes = intArrayOf(sizeX, sizeY, sizeZ)
            val sizes = IntArray(3)
            for (axis in 0..2) sizes[transform.permutation[axis]] = sourceSizes[axis]
            val result = CharArray(cells.size)
            for (z in 0 until sizeZ) for (y in 0 until sizeY) for (x in 0 until sizeX) {
                val from = intArrayOf(x, y, z)
                val to = IntArray(3)
                for (axis in 0..2) {
                    to[transform.permutation[axis]] = if (transform.signs[axis] > 0) from[axis]
                        else sourceSizes[axis] - 1 - from[axis]
                }
                result[to[0] + to[1] * sizes[0] + to[2] * sizes[0] * sizes[1]] =
                    cells[x + y * sizeX + z * sizeX * sizeY]
            }
            return Pattern(result, sizes[0], sizes[1], sizes[2])
        }
    }

    private class GridDefinition(
        val size: GridSize,
        val symbols: List<Char>,
        val values: Map<Char, Byte>,
        val waves: Map<Char, Int>,
        val folder: String?,
    )

    private class ModelRuntime(val contexts: Map<GridDefinition, CompilerContext>, var active: GridDefinition) {
        fun context(grid: GridDefinition) = contexts.getValue(grid)
    }

    private class NodePlan(val output: GridDefinition, val build: (ModelRuntime) -> RewriteNode)

    private data class CompilerContext(
        val grid: VoxelGrid,
        val random: Random,
    )

    private data class RuleContext(
        val size: GridSize,
        val values: Map<Char, Byte>,
        val waves: Map<Char, Int>,
        val folder: String?,
        val resources: ModelResources,
    )

    private val ruleNodeNames = setOf("one", "all", "prl", "path", "convolution")
}

private fun Element.requiredAttribute(name: String): String =
    optionalAttribute(name) ?: error("Missing '$name' on <$tagName>")

private fun Element.optionalAttribute(name: String): String? =
    getAttribute(name).takeIf(String::isNotEmpty)

private fun Element.booleanAttribute(name: String, default: Boolean): Boolean =
    when (val value = optionalAttribute(name)?.lowercase()) {
        null -> default
        "true" -> true
        "false" -> false
        else -> error("Invalid boolean '$value' for '$name' on <$tagName>")
    }

private fun Element.intAttribute(name: String, default: Int): Int =
    optionalAttribute(name)?.toInt() ?: default

private fun Element.doubleAttribute(name: String, default: Double): Double =
    optionalAttribute(name)?.toDouble() ?: default

private fun Element.directChildren(tagName: String): List<Element> = buildList {
    val children = childNodes
    for (index in 0 until children.length) {
        val child = children.item(index)
        if (child is Element && child.tagName == tagName) add(child)
    }
}

private fun Element.directElements(): List<Element> = buildList {
    val children = childNodes
    for (index in 0 until children.length) {
        val child = children.item(index)
        if (child is Element) add(child)
    }
}

private fun Element.gridUnions(): List<Element> = buildList {
    for (child in directElements()) {
        when (child.tagName) {
            "union" -> add(child)
            "sequence", "markov" -> addAll(child.gridUnions())
        }
    }
}
