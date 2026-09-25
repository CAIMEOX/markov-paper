# Markov Paper

Markov Paper generates animated voxel structures from MarkovJunior XML,
tiled Wave Function Collapse, and non-uniform tile modules.

- **Paper plugin:** client-side previews, animated world materialization,
  configurable model palettes and height, and composition of Minecraft Structure Assets.
- **Fabric mod:** Minecraft 26.3 model commands, native previews and animated materialization.
- **Standalone CLI:** model validation, JSON/VOX/PNG export, and animation traces.
- **Core library:** an embeddable Kotlin/JVM compiler and incremental generator.

## Requirements and installation

The Paper plugin targets **Paper 26.2 and Java 25**. Copy
`markov-paper-0.1.0-SNAPSHOT.jar` into the server's `plugins/` directory and
restart the server. Models and profiles are installed into `plugins/markov-paper/models/`.

The Fabric mod targets **Minecraft 26.3 and Java 25**. Install its JAR together
with Fabric API and Fabric Language Kotlin in `mods/`; see the [Fabric guide](docs/fabric.md).

The CLI and core library require **Java 21+**:

```sh
java -jar markov-cli-0.1.0-SNAPSHOT.jar --help
```

To build the artifacts from source, use **JDK 25**:

```sh
./gradlew :jar :fabric:jar :cli:standaloneJar :cli:installDist
```

The plugin JAR is in `build/libs/`, the executable CLI JAR in `cli/build/libs/`,
the Fabric mod in `fabric/build/libs/`, and the CLI application distribution
in `cli/build/install/markov/`.

## Quick start

In Minecraft:

```text
/mj setpos ~ ~ ~
/mj bound backrooms2d 41 8
/mj preview backrooms2d 41 2405 4 8
/mj stop
```

From the CLI:

```sh
markov compile nut-rooms2d --size 24 24 1
markov run nut-bricks --size 8 8 8 --output bricks.vox
```

World materialization writes real blocks, including air. Read the
[commit and stop behavior](docs/paper.md#execution-stopping-and-materialization)
before using `/mj materialize`.

## Documentation

- [Paper guide](docs/paper.md): installation, commands, permissions and world operations.
- [Fabric guide](docs/fabric.md): Minecraft 26.3 setup, commands and world operations.
- [CLI guide](docs/cli.md): commands, exports, animation traces and exit codes.
- [Model format](docs/model-format.md): MJ XML, tiled WFC, NUTs and resource files.
- [Model profiles](docs/model-profiles.md): input dimensions, axes, materials and height columns.
- [Core library](docs/core.md): compilation, initial state and execution.
- [Concepts](docs/concepts.md): models, modules, scenes and source provenance.

Bundled resources are pinned and checksum-verified.
See [third-party notices](THIRD_PARTY_NOTICES.md) for attribution and licensing.
