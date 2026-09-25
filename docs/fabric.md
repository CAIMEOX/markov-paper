# Fabric guide

[Overview](../README.md) · [Model profiles](model-profiles.md) · [Model format](model-format.md)

## Installation

Requirements:

- Minecraft **26.3** and Java **25**.
- Fabric Loader **0.19.5** or newer.
- Fabric API **0.161.0+26.3** or a compatible newer 26.3 release.
- Fabric Language Kotlin **1.13.10+kotlin.2.3.20** or newer.

Install `markov-fabric-0.1.0-SNAPSHOT.jar`, Fabric API and Fabric Language Kotlin
in the Fabric server's `mods/` directory. The mod also supports the integrated
server when installed in a Fabric client. Generation and world operations run
on the server; previews use native Minecraft block-update packets.

Build the mod with JDK 25:

```sh
./gradlew :fabric:jar
```

The output is `fabric/build/libs/markov-fabric-0.1.0-SNAPSHOT.jar`. It includes
the shared generator and Minecraft runtime libraries. Paper and Fabric are
separate server backends; use the artifact matching the server platform.

## Commands

Commands require Minecraft gamemaster permission (operator level 2).

```text
/mj models
/mj preview [model] [size] [seed] [rewritesPerTick] [height]
/mj materialize [model] [size] [seed] [rewritesPerTick] [height]
/mj setpos [x y z]
/mj bound [model] [size] [height]
/mj clearpos
/mj stop
```

`models` works from the console. Placement, preview and materialization commands
require a player. Model names have tab completion. Dimensions and height must
be positive; explicit rewrite speed is 1–64 advances per compute batch.

```text
/mj setpos ~ ~ ~
/mj bound backrooms2d 41 8
/mj preview backrooms2d 41 2405 4 8
/mj stop
/mj materialize nut-rooms2d 24
```

`setpos` selects the bottom corner in the player's dimension. It supports
Minecraft absolute, relative and local coordinates. Without a custom position,
generation is placed in front of the player. `bound` shows the compiled volume
for ten seconds. Profiles map model Z to Minecraft Y for bundled 3D models.

## Models and resources

The mod installs missing bundled files under the instance's config directory:

```text
config/markov/
  models/backrooms2d.xml
  models/backrooms2d.properties
  tilesets/MyTiles.xml
  tilesets/MyTiles/Room.vox
  rules/MyRules/Wall.vox
```

Existing files are preserved. XML, profile and resource resolution use the same
compiler and settings as Paper. See [model profiles](model-profiles.md) for
dimensions, materials and adjustable height, and [model format](model-format.md)
for WFC/NUT configuration. A prepared generation is limited to 2,000,000 voxels
and must fit Minecraft's coordinate and height bounds.

Fabric provides model generation, previews and materialization. Structure Asset,
assemblage, hybrid, workbench and debug-world commands are provided by the
[Paper backend](paper.md).

## Preview and commit behavior

Two background workers advance generators. Native block updates are grouped by
chunk section, with at most 512 cell changes per session per tick. Restoration
and world commits each process up to 512 cells per session per tick.
Chunk tickets load commit targets asynchronously and are released after use.

- Preview changes only the invoking player's view.
- Before commit, `stop` cancels generation and restores the overlay. Wait for
  restoration before starting another preview.
- A started commit continues after stop, disconnect or dimension changes.
- Explicit air clears real blocks during materialization.
- Failed write batches are retained for retry.
- Server shutdown finishes accepted commits synchronously and restores previews.
  This can delay shutdown; errors can leave partial results.

**Materialization has no automatic undo or crash recovery.** Back up worlds
before writing structures. Per-session cell limits do not impose a global TPS
budget; model preparation and allocation run on the server thread, and an
individual solver advance may be expensive.
