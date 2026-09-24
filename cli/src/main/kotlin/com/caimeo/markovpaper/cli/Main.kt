package com.caimeo.markovpaper.cli

import com.caimeo.markovpaper.xml.GridSize
import com.caimeo.markovpaper.xml.MarkovXmlCompiler
import com.caimeo.markovpaper.xml.ModelResources
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.util.Random
import kotlin.system.exitProcess

fun main(args: Array<String>) { exitProcess(runCli(args, System.out, System.err)) }

/** Paper and this adapter use exactly the same prepared compiler and incremental node. */
fun runCli(args: Array<String>, out: PrintStream, err: PrintStream): Int {
    var runSeed: Long? = null
    return try {
        if (args.isEmpty() || args.singleOrNull() in setOf("--help", "help", "-h")) {
            out.println(HELP)
            return 0
        }
        require(args[0] in setOf("compile", "run")) { "Expected 'compile' or 'run'; see --help" }
        require(args.size >= 2 && !args[1].startsWith("--")) { "A model XML path or bundled model name is required" }
        val command = args[0]
        val options = linkedMapOf<String, String>()
        var size: GridSize? = null
        var index = 2
        while (index < args.size) {
            val key = args[index++]
            if (key == "--size") {
                require(size == null && index + 2 < args.size) { "--size requires X Y Z once" }
                size = GridSize(args[index++].toInt(), args[index++].toInt(), args[index++].toInt())
            } else {
                require(key in setOf("--data-folder", "--seed", "--output", "--trace", "--max-steps", "--max-cells", "--slice", "--scale")) {
                    "Unknown option '$key'"
                }
                require(key !in options && index < args.size) { "Missing value or duplicate option '$key'" }
                options[key] = args[index++]
            }
        }
        val inputSize = requireNotNull(size) { "Specify the input grid with --size X Y Z (MJ axes: Z is vertical)" }
        if (command == "compile") require(options.keys.none { it in setOf("--seed", "--trace", "--max-steps", "--slice", "--scale") }) {
            "compile does not execute the model; run-only options are not accepted"
        }
        val folder = options["--data-folder"]?.let(Path::of)
        val resources = ModelResources(folder)
        val path = Path.of(args[1])
        val xml = if (Files.isRegularFile(path)) Files.readString(path) else {
            require(args[1].matches(Regex("[a-z0-9_-]+"))) { "Model file does not exist: $path" }
            val external = folder?.resolve("models/${args[1]}.xml")
            if (external != null && Files.exists(external)) {
                // Use the resource resolver's traversal/symlink checks for data-folder models too.
                resources.open("models/${args[1]}.xml").bufferedReader().use { it.readText() }
            } else {
                requireNotNull(MarkovXmlCompiler::class.java.getResourceAsStream("/models/${args[1]}.xml")) {
                    "Unknown bundled model '${args[1]}'"
                }.bufferedReader().use { it.readText() }
            }
        }
        val plan = MarkovXmlCompiler.prepare(xml, inputSize.x, inputSize.y, inputSize.z,
            resources, options["--max-cells"]?.toLong() ?: 2_000_000L)
        val metadata = "\"inputSize\":${inputSize.json()},\"size\":${plan.size.json()}," +
            "\"symbols\":${plan.symbols.joinToString("").json()},\"axes\":\"MJ: X,Y horizontal; Z up\""
        val output = options["--output"]?.let(Path::of)
        if (command == "compile") {
            val description = "{\"compiled\":true,$metadata}"
            if (output != null) Files.writeString(output, description + "\n", CREATE_NEW)
            out.println(description)
            return 0
        }
        requireNotNull(output) { "run requires --output result.json|result.vox|result.png" }
        require(!Files.exists(output)) { "Output already exists: $output (choose a new file)" }
        val format = ModelExports.format(output)
        val slice = options["--slice"]?.toInt() ?: plan.size.z / 2
        val scale = options["--scale"]?.toInt() ?: 1
        require(options.keys.none { it in setOf("--slice", "--scale") } || format == "png") {
            "--slice and --scale are only used with PNG output"
        }
        require(scale in 1..64) { "PNG scale must be in 1..64" }
        if (format == "png") require(plan.size.x.toLong() * plan.size.y * scale * scale <= 64_000_000L) {
            "PNG output exceeds 64 million pixels"
        }
        require(slice in 0 until plan.size.z) { "Slice Z must be within the output grid" }
        if (format == "vox") require(listOf(plan.size.x, plan.size.y, plan.size.z).all { it <= 256 }) {
            "VOX supports at most 256 cells per axis; use JSON for larger output"
        }
        val seed = options["--seed"]?.toLong() ?: Random().nextLong()
        runSeed = seed
        val maxSteps = options["--max-steps"]?.toInt() ?: 1_000_000
        require(maxSteps > 0) { "--max-steps must be positive" }
        val tracePath = options["--trace"]?.let(Path::of)
        require(tracePath == null || tracePath.toAbsolutePath().normalize() != output.toAbsolutePath().normalize()) {
            "Trace and output must be different files"
        }
        val model = plan.create(seed)
        var steps = 0
        var frames = 0
        var complete = false
        tracePath?.let { require(!Files.exists(it)) { "Trace already exists: $it" } }
        val trace = tracePath?.let { Files.newBufferedWriter(it, CREATE_NEW) }
        trace.use { writer ->
            writer?.appendLine("{\"event\":\"start\",$metadata,\"seed\":$seed,\"cells\":${model.grid.copyState().json()}}")
            try {
                while (steps < maxSteps) {
                    val delta = model.node.advance()
                    if (delta == null) { complete = true; break }
                    steps++
                    if (delta.changes.isEmpty()) continue
                    frames++
                    writer?.appendLine("{\"event\":\"frame\",\"step\":$steps,\"frame\":$frames,\"changes\":" +
                        delta.changes.joinToString(prefix = "[", postfix = "]") {
                            "[${it.x},${it.y},${it.z},${it.before},${it.after}]"
                        } + "}")
                }
            } catch (exception: Exception) {
                writer?.appendLine("{\"event\":\"error\",\"message\":${(exception.message ?: "Generation failed").json()}}")
                throw exception
            } finally {
                writer?.appendLine("{\"event\":\"end\",\"complete\":$complete,\"steps\":$steps,\"frames\":$frames}")
            }
        }
        if (!complete) {
            err.println("Stopped after $maxSteps advances; no final output written. seed=$seed")
            return 3
        }
        ModelExports.write(model, output, format, slice, scale, seed)
        out.println("{\"complete\":true,$metadata,\"seed\":$seed,\"steps\":$steps,\"frames\":$frames," +
            "\"output\":${output.toString().json()}}")
        0
    } catch (exception: Exception) {
        err.println("markov: ${exception.message ?: exception.javaClass.simpleName}" +
            (runSeed?.let { " (seed=$it)" } ?: ""))
        1
    }
}

internal fun String.json(): String = buildString {
    append('"')
    for (char in this@json) when (char) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> if (char.code < 32) append("\\u" + char.code.toString(16).padStart(4, '0')) else append(char)
    }
    append('"')
}
internal fun GridSize.json() = "[$x,$y,$z]"
internal fun ByteArray.json() = joinToString(prefix = "[", postfix = "]")

private val HELP = """
    Markov — standalone MJ XML / NUT generator (Java 21+, no Minecraft required)
    markov compile MODEL --size X Y Z [--data-folder DIR] [--output plan.json]
    markov run MODEL --size X Y Z --output result.json|result.vox|result.png
        [--data-folder DIR] [--seed N] [--trace frames.jsonl] [--slice Z] [--scale N]
        [--max-steps N] [--max-cells N]

    MODEL is a file path or bundled model name. --size is the input grid, not the
    expanded output. MJ coordinates are X/Y horizontal and Z up; Paper maps Z to Y.
    compile validates XML/resources and reports final bounds without generation.
    run writes a final grid; PNG is an XY slice (default: middle Z). JSON stores
    x-fastest cells and the symbol legend. VOX omits symbol index 0 as empty space.
    --trace streams an initial grid plus [x,y,z,before,after] frame deltas.
    New files only: outputs are never overwritten. Exit 3 means the step budget
    was reached (trace retained, no final grid). Other errors exit 1.
    Full documentation: docs/cli.md in the source checkout or distribution.
""".trimIndent()
