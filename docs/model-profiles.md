# Minecraft model profiles

[Paper guide](paper.md) · [Fabric guide](fabric.md) · [MJ XML format](model-format.md) · [Core](core.md)

Both game backends read `models/<name>.xml` and its optional presentation
profile `models/<name>.properties`. Paper uses `plugins/markov-paper/` as its
data folder; Fabric uses `config/markov/`. Profiles use UTF-8 Java properties
syntax. The standalone
CLI uses explicit `--size` dimensions and exports MJ axes and symbolic colors.

Each backend installs missing bundled XML/profile files at startup without
overwriting existing ones. Copy **both files** when renaming a bundled model.
Each command reads its profile again; editing it affects the next generation,
not an already-running preview. Missing profiles use the defaults below.
Malformed or misspelled settings produce an error. Symlinks outside the model
directory are rejected.

## Input, placement and defaults

```properties
default-size=31
min-size=3
rewrites=4
input=size size 1
up=z
hybrid=false
```

| Setting | Default | Meaning |
| --- | --- | --- |
| `default-size` | `15` | Command default for the size argument |
| `min-size` | `1` | Minimum allowed size argument |
| `rewrites` | `4` | Maximum advances per compute batch |
| `input` | `size size size` | Three MJ input dimensions; each is a positive integer or `size` |
| `up` | `auto` | `z` maps MJ Z to Minecraft Y; `y` leaves axes unchanged |
| `hybrid` | `false` | Enable Paper hybrid Scene generation within its volume/work limits |

`auto` uses MJ Z-up for a height projection or output depth greater than one;
otherwise the XY plane stays vertical. Use `up=z` to place a 2D model horizontally.
XML rules use model coordinates. Output bounds come from the compiler, including
WFC/map expansion.

Fixed-size authored examples use, for example, `input=10 10 4` and
`default-size=10`. If no dimension contains `size`, the command size must equal
the input X dimension. This prevents scaling an authored coarse grid accidentally.

For a 2D input, set `input=size size 1`. The default input is cubic.

## Block palette

```properties
block.R=minecraft:gray_stained_glass
block.g=minecraft:oak_leaves[persistent=true]
```

Keys contain exactly one output symbol and support exact-case overrides. Lookup
tries the exact symbol, then its uppercase override, then the default palette.
Without an override, output index zero is air; other symbols use the usual MJ
color palette, with a deterministic color fallback for unknown symbols.
Explicit `block.B=...` may override index zero (the `fill` demo does this).
The selected backend validates Minecraft block states when displaying them.

## Height columns

Projection takes the compiler's **final 2D grid**, before Minecraft axis mapping.
Each source symbol must have exactly one column; no extra/missing source symbols
are accepted. `output-symbols` declares the projected alphabet in index order.
Each column is either a single repeated symbol or four symbols describing
**floor, lower half, upper half, ceiling**. Grouped keys share a column.

The bundled Backrooms profile contains:

```properties
input=size size 1
default-size=41
min-size=15
height=6
min-height=5
output-symbols=BSL
column.B=B
column.WHV=S
column.FR=SBBS
column.DQ=SBSS
column.L=SBBL
block.S=minecraft:smooth_sandstone
block.L=minecraft:sea_lantern
```

Here `B` is air, `S` sandstone and `L` light. `FR` produces floor and ceiling
around an empty room; `DQ` adds an upper-half wall; `L` places its lamp at the
ceiling. At height 7, `SBSS` expands to `SBBBSSS`. The split starts at
`ceil(height / 2)`, floor is level 0 and ceiling is level `height - 1`.
Floor wins when height is 1; at height 2 only floor and ceiling occur.

The command's final height argument overrides `height`, subject to `min-height`
(default 1). Models without columns reject a height argument. Profile compilation
validates the alphabet, columns and projected volume before allocating runtime
grids. Animation updates only the affected columns.
