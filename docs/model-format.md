# Model format and resources

[Overview](../README.md) · [CLI](cli.md) · [Core embedding](core.md) · [Paper](paper.md) · [Fabric](fabric.md)

## Tiled WFC and external resources

`<wfc tileset="...">` and nested `<map>` run through `MarkovXmlCompiler`,
including the bundled Paths, SeaVilla, ModernHouse, and CarmaTower models.
Custom models are loaded directly from XML.

Minecraft sizes, axes, palettes and height projection live in optional
[model profiles](model-profiles.md).

For example, save `models/my-wfc.xml` in the backend's data folder:

```xml
<sequence values="BN" origin="True" symmetry="(xy)">
  <wfc tileset="Paths" values="BYDAWPRFUENC" tries="1000" shannon="True">
    <rule in="N" out="Up|Down"/>
    <prl in="Y" out="W"/>
  </wfc>
</sequence>
```

```text
/mj bound my-wfc 4
/mj preview my-wfc 4 123 4
/mj materialize my-wfc 4 123 4
```

Each command resolves the files again. Model XML lives in `models/`. Resources
are resolved **first from the backend's data folder**, then from the JAR. Paper
uses `plugins/markov-paper/`; Fabric uses `config/markov/`:

```text
plugins/markov-paper/
  models/my-wfc.xml
  tilesets/MyTiles.xml
  tilesets/MyTiles/Empty.vox
  tilesets/MyTiles/Room.vox
  rules/MyFolder/SomeRule.vox
```

`tilesets/MyTiles.xml` contains `<tiles>` and `<neighbors>`, for example:

```xml
<tileset>
  <tiles>
    <tile name="Empty" weight="3.0"/>
    <tile name="Room" weight="1.0"/>
  </tiles>
  <neighbors>
    <neighbor left="Empty" right="Empty"/>
    <neighbor left="Empty" right="Room"/>
    <neighbor left="Room" right="Room"/>
    <neighbor top="Empty" bottom="Empty"/>
    <neighbor top="Empty" bottom="Room"/>
    <neighbor top="Room" bottom="Empty"/>
    <neighbor top="Room" bottom="Room"/>
  </neighbors>
</tileset>
```

Supply the VOX files, then use `tileset="MyTiles"` and the appropriate `values`
alphabet in the model. As in MJ, VOX color ordinals are assigned in first-seen
order across tiles, and index into `values`. You can copy a bundled tileset from `MarkovJunior/resources/tilesets`
into this directory and edit its weights or adjacency. Individual overridden
files take precedence; missing files use bundled resources. Malformed
overrides produce an error. Resource paths
must stay within the data folder; external symlink targets and `..` are rejected.

Supported tiled WFC attributes:

- `tileset`, `values`, and `<rule in="N" out="Room|Corner"/>` constraints on the
  parent grid. Unspecified input values permit all variants.
- Positive finite tile `weight` values (default 1), used for each orientation.
- `tiles="Other/Subfolder"` to select a different VOX directory.
- `overlap` / `overlapz` (default 0), with stride = tile size minus overlap;
  negative overlap creates gaps for subsequent rules.
- `tries` (default 1000), incremental solution search followed by animation.
- `periodic="True"` for wrapping grid adjacency; `shannon="True"` for weighted
  entropy when choosing the next cell.
- Horizontal rotations/reflections, explicit `z`, `zz`, `zzz` neighbor rotations,
  and `<neighbor top="..." bottom="..."/>` for model-Z vertical adjacency.

Ordinary MJ tilesets must have identical extents and square X/Y footprints;
the shared Z extent may differ from X/Y. Mixed sizes use the NUT extension below.
Position-dependent soft weights are not supported.
`fullSymmetry="True"` and overlapping/sample WFC (`sample=...`) fail explicitly.

WFC/map children operate on the new grid and may contain further WFC/map nodes.
Unions belong to their grid's alphabet; the same union symbol can be reused in
another grid. A grid-changing node must be the final child of each enclosing
branch; place its subsequent rules inside it. Multiple alternative output grids
and returning to a parent grid are rejected. Intermediate frames are projected
into the final preview extent; the final grid uses its exact output alphabet.
All stage sizes are validated before grid allocation, so custom WFC/map models
use the same bounds, world-height checks, and 2,000,000-cell limit as bundled models.
WFC waves are additionally limited to 32 million cell/variant candidates.
The compiler shares a seeded RNG across XML stages.
VOX resources must contain a single model, have dimensions within 1–256, and
contain at most 2,000,000 cells; malformed or oversized resources are rejected.

Supported MarkovJunior XML:

- nodes: `one`, `all`, `prl`, `sequence`, `markov`, `path`, `convolution`, tiled `wfc`, `map`;
- grid attributes: `values`, `origin`;
- rules written with `in` and `out`, including 3D space-separated layers;
- input wildcard `*`, output unchanged `*`, and `union` symbols;
- `steps`, parallel-rule `p`, and `periodic` convolution;
- 2D square and 3D cube symmetry groups, with structural deduplication;
- convolution neighborhoods: `VonNeumann`, `Moore`, and `NoCorners`.
- the bundled `Paths` 3D tile-WFC, including rotated `Up` and `Down` stair
  voxels and the original horizontal/vertical adjacency table.
- greedy backward-potential inference for `observe` elements inside `one`.
- pointwise `field` guidance, including inversed `from` fields used by
  CrossCountry.
- staged `map` transitions with rational scale factors and differing alphabets;
- bundled or data-folder PNG and MagicaVoxel rule resources;
- bundled `MarchingHills`, `Partition`, and `PartitionedEdges` WFC tilesets.

Unsupported nodes such as search-based inference, overlapping/sample WFC,
and `convchain` produce compilation errors.

## Non-uniform tiles (NUT): rigid multi-cell modules

The NUT extension implements atomized Simple Tiled WFC. Enable it in the
**tileset manifest**, while keeping an ordinary model entry point:

```xml
<!-- models/my-nut.xml -->
<wfc values="BRY" tileset="MyNUT" shannon="True"/>
```

```xml
<!-- tilesets/MyNUT.xml -->
<tileset nonUniform="True" atomSize="1 1 1">
  <tiles>
    <tile name="Air" voxels="B" weight="0.1"/>
    <tile name="Beam" voxels="RRR" rotations="z"/>
    <tile name="Corner" voxels="YY/YB" mask="XX/X." rotations="z"/>
  </tiles>
  <neighbors>
    <neighbor left="*" right="*" directions="x y z"/>
  </neighbors>
</tileset>
```

- `atomSize` is the shared voxel size of one solver cell, default `1 1 1`.
  A room can span several such cells. Coarse atoms reduce the number of solver
  states required for large modules.
- Each tile supplies `voxels` inline (rows separated by `/`, Z layers by spaces),
  or `vox="Room.vox"` relative to `tilesets/<tiles folder>/`. Without either,
  `<tile name="Room"/>` loads `Room.vox`. Dimensions must be multiples of atomSize.
- NUT VOX uses explicit palette-index mapping: `legend="SL"` maps palette index
  1 to S, 2 to L. The default legend
  is the output alphabet minus its first symbol. Missing VOX voxels map to the
  first symbol (normally B/air), and remain owned by the module.
- `mask` is optional and specified at **atom resolution**: `X` belongs to the
  module, `.` is unowned space that another module may occupy. Omission owns the
  entire bounding box, **including interior air**. Masks must be nonempty and
  face-connected; unused outer padding is trimmed.
- `rotations="z"` enables four horizontal orientations (deduplicated by shape
  and payload); default `none`. Quarter turns require square atom X/Y dimensions.
  Each local atom has a distinct identity within its module.
- `weight` is positive/finite, normalized across the module's orientations and
  atoms. It controls selection preference; output frequencies depend on constraints.
- `neighbor left/right` defaults to model directions `x y`; `bottom/top` defaults
  to `z`. `directions` can explicitly list `x y z -x -y -z`. Names select all
  orientations of a module; `*` selects all modules. NUT names use the module's
  declared name. Reverse adjacency is derived automatically.
- Optional `offset="dx dy dz"` fixes the second module's minimum atom corner
  relative to the first; omitted offsets enumerate all touching, nonintersecting
  placements. For concave modules a legal placement may contact along multiple
  directions: all those contacts are compiled, while internal bonds stay exclusive.

The compiler creates distinct states for every module/orientation/local atom,
then feeds them to the **same** `TileWfcNode`. Internal bonds force the complete
module; boundary filtering removes candidates whose full extent does not fit.
Propagation reaches a fixed point before a frame is emitted, so a module is
revealed as a whole. Owned air retains its module identity.

NUTs operate on finite grids. Sample learning, periodic wrapping and tile
overlap/gaps are unsupported. Global path connectivity requires additional
constraints. `periodic=True` or nonzero `overlap`/`overlapz` fail at preparation.
NUT adjacency uses a dense table capped at 64 million entries before allocation; the
32-million cell/state candidate budget also applies.

Bundled demonstrations (installed into each backend's model folder at startup):

```text
/mj preview nut-bricks 8
/mj preview nut-rooms2d 24
/mj materialize nut-rooms2d 24
```

`nut-bricks` includes beams, concave corners, vertical pillars and 3D cubes.
`nut-rooms2d` combines a hall, L-shaped hall and two room sizes, then a normal
XML `map` builds a six-block sandstone shell, upper-half partitions and ceiling
lights. Module placement preserves owned cells; doorway alignment and global
reachability require additional constraints. Edit the map Z scale and output
columns together to adjust its height.
Both samples support preview, bounds, setpos and final materialization;
height is model Z and is displayed toward Minecraft Y+.
