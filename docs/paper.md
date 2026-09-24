# Paper guide

[Overview](../README.md) · [CLI](cli.md) · [Model format](model-format.md) · [Model profiles](model-profiles.md)

## Installation

Use Paper 26.2 with Java 25. Place the plugin JAR in `plugins/` and restart the
server. The plugin installs missing bundled XML and profile files in
`plugins/markov-paper/models/`, preserving existing files.

Structure Asset commands use the bundled Minecraft 26.2 catalog. If the catalog,
Asset Workbench or Asset Debug World fails to initialize, the server log reports
the cause and the affected commands are unavailable. XML model generation
remains available.

## Permissions

All permissions default to operators.

| Permission | Operations |
| --- | --- |
| `markov-paper.use` | Model listing, position, bounds and stop |
| `markov-paper.preview` | Client-only previews, Structure Asset inspection and animation |
| `markov-paper.materialize` | Model, assemblage, hybrid and workbench world writes |
| `markov-paper.admin` | Asset Debug World management |

Each specialized permission includes `markov-paper.use`. Grant both preview
and materialize for a complete authoring workflow. Tab completion follows the
same permissions as command execution.

## Models and placement

```text
/mj models
/mj preview [model] [size] [seed] [rewritesPerTick] [height]
/mj materialize [model] [size] [seed] [rewritesPerTick] [height]
/mj setpos [x y z]
/mj bound [model] [size] [height]
/mj clearpos
/mj stop
```

`setpos` stores a bottom corner in the current world. The generated result
extends toward positive X, Y and Z. With no arguments it uses the player's
block position; coordinates accept absolute and Minecraft-style relative values.
Without a custom position, the plugin places the preview in front of the player.

```text
/mj setpos ~ ~12 ~-8
/mj bound backrooms2d 41 8
/mj preview backrooms2d 41 2405 4 8
```

`bound` displays the compiled output volume as a cyan wireframe for ten seconds.
Dimensions, default speed, axis mapping, materials and height projection come
from the model's [profile](model-profiles.md). XML can expand its input through
WFC and map nodes. The final volume is limited to 2,000,000 voxels and must fit
the world's vertical build range.

MJ uses Z as its vertical axis. Profiles select how model axes map to Minecraft;
bundled 3D and extruded models grow along Minecraft Y+. Custom 2D models use
`input=size size 1` in their profiles.

### Bundled models

| Model | Description |
| --- | --- |
| `fill` | Animated black-to-white voxel fill |
| `maze`, `backtracker`, `backtracker-cycle` | Maze growth and traversal |
| `dungeon`, `snake`, `life2d` | Room growth, paths and cellular automata |
| `growth3d`, `cave3d`, `no-dead-ends3d` | Three-dimensional growth and passages |
| `river3d`, `counting3d`, `maze-trail3d`, `noise3d` | Volumetric rewrite examples |
| `apartemazements` | Paths WFC architecture with gray glass windows |
| `stairs3d`, `stairs-path-rules3d` | Three-dimensional stair structures |
| `cross-country2d` | Field-guided path growth |
| `wfc-partition` | Tiled partition generation |
| `nut-bricks` | Rigid beams, concave corners, pillars and cubes |
| `nut-rooms2d` | Non-uniform rooms mapped to a six-block-high sandstone volume |
| `backrooms2d` | Sandstone rooms, upper-half partitions and ceiling lights |
| `nystrom-dungeon2d`, `dungeon-growth2d` | Height-adjustable dungeon floorplans |
| `carma-tower` | Fixed 12×12×18 input, 48×48×72 output |
| `sea-villa` | Fixed 10×10×4 input, 95×95×24 output |
| `modern-house` | Fixed 9×9×4 input, 51×51×24 output |

For `apartemazements` and `stairs3d`, the size argument is the coarse WFC
grid size. A size of 4 produces a 20×20×20 volume.

Backrooms defaults to 41×41×6, with minimum plan size 15 and minimum height 5.
Open rooms have a sandstone floor and ceiling. Walls fill their columns;
door paths have an open lower half and a sandstone upper half.
Lamps occupy ceiling cells. Dungeon projections default to height 4.

```text
/mj preview backrooms2d
/mj materialize backrooms2d 41 2405 4 8
/mj preview nystrom-dungeon2d 31 123 2 6
/mj materialize dungeon-growth2d 31 123 2 5
/mj preview sea-villa 10 29 8
/mj preview modern-house 9 31 8
```

## Execution, stopping and materialization

Generation runs in two background workers. Each session has one compute batch
in flight and displays its results before requesting another.
`rewritesPerTick` caps the number of rewrites per compute batch. Model loading,
compilation and command-time Structure Asset planning run on the server thread.

Initial display and animation send up to 512 cell updates per session per tick.
Assemblage traces use 64 updates per tick. Restoration and materialization
process up to 512 cells per session per tick; materialization waits for
asynchronous chunk loading. These cell limits do not impose a global time or
TPS budget. Plan available memory and concurrency for large models.

`preview` creates a client-side overlay. `materialize` plays the animation,
then writes the result to the world:

- Before commit, `/mj stop` cancels generation and restores the overlay in
  batches. Disconnecting or changing worlds discards an uncommitted preview.
- **A started commit continues to completion**, including after stop,
  disconnect or a world change. The same player can start another preview
  when restoration or commit finishes.
- Real writes appear in batches. Explicit air clears blocks; unspecified Scene
  coordinates preserve existing world content.
- Failed restoration or write batches are retained for retry. An unavailable
  world pauses its commit until the world is loaded.
- Plugin disable cancels generation, restores overlays and synchronously drains
  accepted commits. This can delay shutdown. Failures can leave partial results.

**World writes have no automatic undo or crash recovery.** Back up worlds before
materializing structures. WFC contradictions are reported with the selected seed.
Cancelling computation discards its output; an expensive kernel may finish its
current advance before releasing its worker.

## Structure Assets and assemblages

```text
/mj assets [search]
/mj asset inspect <asset-ref>
/mj asset preview <asset-ref>
/mj assemblage inspect <first-ref> <second-ref> [seed]
/mj assemblage preview <first-ref> <second-ref> [seed]
/mj assemblage trace [seed] <first-ref> <second-ref> [more-refs...]
/mj assemblage animate [seed] <first-ref> <second-ref> [more-refs...]
/mj assemblage materialize [seed] <first-ref> <second-ref> [more-refs...]
```

The catalog contains 1,212 Vanilla NBT Structure Templates, including Village,
Bastion, End City, Ancient City, Trial Chambers and the other template families.
An Asset Reference accepts a canonical id, a path without `minecraft:`, or a
unique trailing suffix such as `taiga_temple_1`. Ambiguities return candidate
references. Inspection reports dimensions, block counts, explicit air and controls.

Assemblages sample horizontal rotations and placements near 30% solid overlap.
A continuous cut plane selects the source of colliding solids. Authored air
preserves solids contributed by another source. Local seam operations add
doorways, connecting floors and supports with explicit inferred provenance.
Search uses palette index 0 and a total budget of one million solid-pair
evaluations; budget exhaustion returns `SEARCH_WORK_BUDGET_EXCEEDED`.

A trace records each introduced source, rotation and offset. Animation replays
those placements in the final coordinate system. Materialization writes the
final Scene after playback under the commit policy above.

## Hybrid generation

```text
/mj hybrid trace [seed] <model> <size> <asset-ref> [more-refs...]
/mj hybrid animate [seed] <model> <size> <asset-ref> [more-refs...]
/mj hybrid materialize [seed] <model> <size> <asset-ref> [more-refs...]
```

Hybrid generation runs an MJ model, turns its result into a procedural Scene,
and grafts authored Structure Assets onto it. The seed controls both stages.
The trace records procedural and authored provenance. Model profiles enable
this operation with `hybrid=true`; bundled `apartemazements` and `stairs3d`
have it enabled. Input and output limits include 20,000 model voxels and
20,000 explicit cells in the final hybrid Scene.

```text
/mj hybrid animate apartemazements 4 snowy_temple_1 well_bottom
/mj hybrid animate stairs3d 3 plains_stable_2
```

## Asset Workbench

```text
/mj workbench open [search]
/mj workbench next|prev|back
/mj workbench pick <slot>
/mj workbench ports
/mj workbench add [slot]
/mj workbench add all [search]
/mj workbench tray|clear
/mj workbench remove <index>
/mj workbench animate|materialize [seed]
/mj workbench grow [count] [seed]
```

The gallery shows six assets per page. Cyan marks bounds, gold marks origins
and magenta marks authored jigsaw orientations. Paper exposes jigsaw positions
and orientations; target/pool/name metadata is unavailable through this catalog.

Add assets to a tray and animate or materialize their assemblage. `add all`
searches the entire catalog. `grow` creates a client-only Macro Assemblage
of 2–100 instances (default 50), sampling at most 24 working assets from the tray.

Macro growth attaches instances along structural faces with authored solid
contact. It tests up to `count × 64` candidates and `count × 4096` contact
probes. Its output supports preview and stop/restoration.

## Asset Debug World

```text
/mj debugworld build|rebuild|status|tp|exit
```

`build` creates the persistent void world `markov-asset-debug`, describes the
catalog and places assets in labeled plots under a 2,000-cell-work-per-tick
budget. Description must complete successfully before placement starts.
`status` reports build progress and catalog validity.

`rebuild` moves the owned world into a timestamped backup and builds a new one.
Worlds with a different catalog or incomplete placement require a rebuild.
An unrelated world with the same name is protected by an ownership marker.

`tp` teleports to the debug world's platform and records the player's location.
`exit` returns there, using the primary Overworld spawn if needed.
