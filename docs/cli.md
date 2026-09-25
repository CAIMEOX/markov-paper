# CLI guide

[Overview](../README.md) · [Model format](model-format.md) · [Core embedding](core.md) · [Paper](paper.md)

The CLI runs the same MJ/WFC/NUT compiler as the Paper and Fabric backends.
It validates models, generates voxel grids, and exports visible animation deltas.

## Install and run

The runtime requires **Java 21 or newer**. A standalone JAR needs no other files:

```sh
java -jar markov-cli-0.1.0-SNAPSHOT.jar --help
```

An application distribution provides `bin/markov` (`bin/markov.bat` on Windows),
its runtime libraries, and this documentation. Run that script from any directory.
The examples below call the script `markov`; substitute its path or `java -jar …`.

To build from the source checkout, install **JDK 25** and make it discoverable
to Gradle, for example by setting `JAVA_HOME` in your shell:

```sh
./gradlew :cli:standaloneJar
./gradlew :cli:installDist
```

Outputs:

- `cli/build/libs/markov-cli-0.1.0-SNAPSHOT.jar`: self-contained executable JAR.
- `cli/build/install/markov/`: script distribution.
- `./gradlew :cli:distZip :cli:distTar`: distributable archives.

The `-thin.jar` is not standalone; the distribution supplies its dependencies.
Builds use vendored resources. Each build
checks their SHA-256 manifest. See `core/vendor/markovjunior.sha256` and the
[third-party notices](../THIRD_PARTY_NOTICES.md) for the pinned upstream revision.

## Quick examples

```sh
mkdir -p out

# Validate the model and discover its expanded dimensions, without generating it.
markov compile nut-rooms2d --size 24 24 1

# Export a floor-level view and its animation; use new output paths on every run.
markov run nut-rooms2d --size 24 24 1 --output out/rooms.png --slice 2 --scale 12 --trace out/rooms.jsonl

# Genuine three-dimensional modules, exportable to MagicaVoxel.
markov run nut-bricks --size 8 8 8 --output out/bricks.vox

# A multi-stage architectural model.
markov run modern-house --size 9 9 4 --seed 31 --output out/house.vox
```

`nut-rooms2d` expands the input to `24×24×6`. The room example preserves modules,
but does not guarantee global doorway alignment or reachability.

## Commands

```text
markov compile MODEL --size X Y Z [--data-folder DIR] [--output plan.json] [--max-cells N]
markov run MODEL --size X Y Z --output FILE
    [--data-folder DIR] [--seed N] [--trace FILE] [--slice Z] [--scale N]
    [--max-steps N] [--max-cells N]
markov --help
```

### compile

Parses all static rules and resources, validates supported options and symbols,
and reports output dimensions and alphabet. Ordinary rules, WFC, and map are
validated at the same preparation stage. No execution grids are allocated and
no generation seed is sampled.

Example result:

```json
{"compiled":true,"inputSize":[24,24,1],"size":[24,24,6],"symbols":"BSL","axes":"MJ: X,Y horizontal; Z up"}
```

`--output` optionally saves this compilation summary. WFC satisfiability is
determined during generation. Run-only options are rejected by `compile`.

### run

Prepares the model, creates a fresh seeded execution, advances it to completion,
and exports its final grid. The output format follows the filename extension.

Parent output directories must already exist. Existing output and trace files
are never overwritten. Confirm successful export with exit code 0; an interrupted
or failed write may leave a partial file.

### Options

| Option | Commands | Meaning / default |
| --- | --- | --- |
| `--size X Y Z` | Both | Required positive **input** dimensions, not final expanded bounds |
| `--data-folder DIR` | Both | External model/resource root; absent means bundled resources only |
| `--output FILE` | Both | Required for `run`, optional compilation-summary file for `compile` |
| `--max-cells N` | Both | Maximum cells per grid, including intermediate grids; default 2,000,000 |
| `--seed N` | Run | Signed 64-bit seed; omitted means a new random seed, reported in the result |
| `--trace FILE` | Run | Stream projected animation frames as NDJSON |
| `--max-steps N` | Run | Positive node-advance limit, including planning; default 1,000,000 |
| `--slice Z` | Run, PNG | Zero-based output Z slice; default is `outputDepth / 2` |
| `--scale N` | Run, PNG | Integer pixels per voxel, 1–64; default 1 |

Duplicate options and unknown options are errors. Limits are safeguards, not
wall-clock deadlines: a single advance may do substantial work. `--max-cells`
does not bound the total JVM heap, and does not override the separate WFC
candidate/adjacency or VOX-format limits described in the [model guide](model-format.md).

## Model and resource resolution

`MODEL` can be a local XML file path or a bundled model name such as `nut-bricks`.
For a name, an external `models/<name>.xml` under `--data-folder` takes precedence
over the bundled model. Resource files are resolved individually: external
files first, then bundled files. Malformed overrides produce an error.

```text
my-models/
  models/custom.xml
  tilesets/MyTiles.xml
  tilesets/MyTiles/Room.vox
  rules/MyRules/Wall.vox
```

```sh
markov compile custom --size 12 12 1 --data-folder my-models
markov run my-models/models/custom.xml --size 12 12 1 --data-folder my-models --output out/custom.json
```

Passing a model path does not change the resource root: provide `--data-folder`
when it references external resources. Resource paths cannot escape that root
through `..` or symlinks. See the [model format](model-format.md) for complete
WFC/NUT manifests, palette legends and masks.

CLI generation uses the XML's dimensions and symbolic palette. Game backends apply
additional [model profiles](model-profiles.md). For example, `backrooms2d` is a 2D grid in CLI, while
`nut-rooms2d` creates height through XML and therefore has the same dimensions
in all backends. Use explicit CLI dimensions to reproduce a Minecraft model's
unprojected input grid.

## Coordinates and exports

Core coordinates follow MJ: **X/Y horizontal, Z vertical**. Paper maps model Z
to Minecraft Y. `--size` refers to the starting grid; query `compile` for final
dimensions after WFC and map expansion.

### JSON

A JSON export contains `size`, `symbols`, `seed`, `order: "x-fastest"`, and
numeric `cells`. Indexing is `x + y * sizeX + z * sizeX * sizeY`; each cell is an
index into the symbol string. JSON is the lossless symbol-grid export.

### VOX

A single MagicaVoxel model with an embedded diagnostic palette. Each axis must
fit in 1–256 cells; use JSON for larger grids. Symbol index 0 is omitted as air,
regardless of its letter. Other symbol indices become VOX palette indices.

The export stores final voxels. To reimport
an authored NUT, supply its mask and palette legend as described in the model guide.

### PNG

An XY slice at `--slice`, enlarged without interpolation by `--scale`. Largest
model Y is at the top of the image. Colors use a diagnostic symbol palette;
index 0 is a dark background. Maximum image size is 64 million pixels.

## Animation trace format

`--trace` produces one JSON object per line. It records the same stable-sized
**projected output view** used for animation.
Grid transitions may change coordinates/alphabet internally; their projected
frame deltas remain in the final output dimensions and symbol alphabet.

Events:

- `start`: `inputSize`, final `size`, final `symbols`, `axes`, `seed`, initial `cells`.
- `frame`: `step`, consecutive visible `frame` number, and `changes`.
- `error`: diagnostic `message` if advancing the execution throws.
- `end`: generation `complete`, `steps` and visible `frames` counts.

Each change is `[x,y,z,before,after]`. Apply changes in order. Before applying a
change, a replay consumer can verify that its cell equals `before`.
Advances with no visible changes count toward `steps` but do not produce frames.

A frame example:

```json
{"event":"frame","step":7,"frame":2,"changes":[[3,2,0,0,1],[4,2,0,0,1]]}
```

`end.complete` describes generation. The process exit code confirms the final-file
write. An abruptly terminated process may
leave a trace without an `end` event; it is not a completed result.

## Exit codes and failure handling

| Code | Meaning |
| --- | --- |
| 0 | Help, successful compilation, or completed generation and export |
| 1 | Argument, XML/resource, generation, or I/O failure; diagnostic on stderr |
| 3 | Step budget reached; incomplete trace retained, no final grid exported |

WFC retries are local to one node. Earlier XML stages are not backtracked when
a later WFC receives contradictory input. A contradiction returns a failure
with the selected seed. Reproducible results require the same model, resources,
dimensions, seed and runtime version.
