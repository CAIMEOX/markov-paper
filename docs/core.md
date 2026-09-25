# Core library

[Overview](../README.md) · [CLI](cli.md) · [Model format](model-format.md)

`core` is a Kotlin/JVM library for Java 21+.

The CLI depends directly on `core`. Paper and Fabric depend on the platform-free
`minecraft` module, which supplies model profiles, scene data, coordinate mapping
and the preview/commit lifecycle and depends on `core`. Each backend implements
its own game-facing transport and command registration.

## Compile a model

`MarkovXmlCompiler.prepare(xml, sizeX, sizeY, sizeZ, resources, maxCells)`
resolves static rules and resources and returns a `PreparedMarkovModel`:

- `inputSize` / `inputSymbols`: root grid dimensions and alphabet.
- `size` / `symbols`: final output dimensions and alphabet.
- `create(seed, initialState = null)`: a fresh execution with independent grids,
  node counters and random state.

Preparation validates supported options, dimensions, symbols and resources.
Runtime constraints may make an individual WFC seed unsatisfiable.
Resource contents are compiled during preparation; prepare again after editing them.

## Initial state

Initial cells are a copied, x-fastest `ByteArray`, indexed into `inputSymbols`.
They must exactly match `inputSize` and replace the XML's origin seed.

```kotlin
val plan = MarkovXmlCompiler.prepare(xml, sizeX = 3, sizeY = 1, sizeZ = 1)
val initial = ByteArray(plan.inputSize.cellCount.toInt())
val red = plan.inputSymbols.indexOf('R')
require(red >= 0)
initial[0] = red.toByte()
val model = plan.create(seed, initialState = initial)
```

`MarkovXmlCompiler.compile(...)` also accepts the optional `initialState`.

## State and animation

`model.state` exposes the active stage's read-only grid and alphabet.
Re-read this property after advancing, since grid-changing nodes can switch stages.

`model.grid` and `model.symbols` expose the animation view in the prepared
final dimensions and alphabet. The completed view is the final result.
`copyState()` returns an owned snapshot.

`model.node.advance()` returns a `StepDelta` for the animation view.
Apply each change to a copy of its initial grid to replay the animation.
Intermediate symbols absent from the final alphabet use display-only substitutions.

`null` marks completion; subsequent calls return `null`. A failed execution
rethrows its recorded failure. Use `plan.create(seed, initialState)` for a fresh
run. The low-level `node.reset()` re-enters control flow over the current grids
and random state; it preserves their contents.

## Height projection

`HeightProjection.prepare(source, height, outputSymbols, columns, maxCells)`
wraps a prepared model with a final 2D grid. Columns map each output symbol to
one constant symbol or four floor/lower-half/upper-half/ceiling symbols.

The returned plan preserves `inputSize` and `inputSymbols`, accepts the same
initial state, and emits projected column deltas. `model.state` exposes the
underlying generator. `model.grid` exposes the extruded output.
Coverage, alphabet and volume are checked before allocating runtime grids.

## Execution limits

Each execution operates on a finite grid. Solver advances have variable
computational cost; a step-count limit does not impose a time deadline.
Memory use includes grids, rule data, WFC candidates and adjacency tables.

The [Paper](paper.md#execution-stopping-and-materialization) and
[Fabric](fabric.md#preview-and-commit-behavior) adapters schedule generation in
background workers and batch client and world updates through the shared runtime.
