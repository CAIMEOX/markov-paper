# Concepts

[Overview](../README.md)

## Models and generation

**Model**: An MJ XML program that rewrites or solves a finite symbol grid.

**Prepared model**: A compiled definition containing resolved resources, input
and output dimensions, and alphabets. Each execution has its own state and seed.

**Model profile**: Paper presentation settings paired with a model: dimensions,
axis mapping, block palette, height columns and command defaults.

**Tile**: A fixed-size voxel pattern with declared adjacency rules, selected by WFC.

**Non-uniform module**: A rigid arrangement of one or more solver cells with
an owned-space mask, voxel payload and permitted orientations.

**Atom**: One solver cell within a particular module and orientation. Internal
bonds preserve the module's shape and identity.

**Owned air**: Empty voxel content that belongs to a module or authored structure.

**Unowned space**: Coordinates outside an ownership mask. Other modules or the
existing Minecraft world may occupy them.

## Scenes and Structure Assets

**Structure Asset**: An authored Minecraft block volume used as a source fragment.

**Asset Reference**: A canonical asset id or an unambiguous path suffix that
resolves to one Structure Asset in a catalog.

**Vanilla Structure Catalog**: The versioned inventory of Minecraft NBT Structure
Templates distributed with the plugin.

**Source Instance**: One occurrence of a Structure Asset in a composition.

**Scene**: A Minecraft-Y-up collection of block states and their contributing sources.

**Scene provenance**: The origin of a cell contribution: an authored asset and
source coordinate, a procedural model and seed, or an inferred seam role.

**Scene Program**: An incremental generator exposing an initial Scene, Scene
changes and a current Scene.

**Architectural Assemblage**: A composition formed by overlapping, cutting and
joining Structure Assets while preserving their provenance.

**Assemblage Trace**: An ordered sequence of source introductions and placements,
aligned in the final Scene's coordinates.

**Assemblage Plan**: A graph of transformed Structure Asset instances and their
parent-child attachments.

**Structural-Face Frontier**: A planned asset face available for a child attachment.

**Local Coherence**: Local doorway, floor and support connections at a composition seam.

**Inferred Seam Cell**: A cell introduced by a Local Coherence operation.

## Minecraft tools

**Asset Workbench**: A per-player catalog gallery and composition tray.

**Jigsaw Marker**: An authored jigsaw control position and orientation.

**Asset Debug World**: A persistent void world displaying the catalog in labeled plots.

**Preview**: A client-side block overlay visible to the invoking player.

**Materialization**: The block-writing phase that commits a generated result to the world.
