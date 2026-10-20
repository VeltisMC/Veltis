# Veltis Parallel Redstone — Concurrency Guarantees

This document is the contract for the parallel redstone implementation
(`server/Shulker/code/019-Parallel-Redstone.patch`,
`org/veltismc/redstone/ParallelRedstone.java`). It records what runs in parallel, why the
results are identical to vanilla serial ticking, which shared state was made thread-safe,
and exactly where documented divergences remain.

The governing rule of the feature: **redstone logic is never modified.** Patch
`017-Alternate-Current.patch` and `018-Alternate-Current-Integration.patch` are byte-identical
to their pre-parallel state; every Minecraft file change in 019 is concurrency plumbing only
(access, ordering, synchronization, deferral). Two plumbing adjustments to Alternate
Current's own sources also live in 019 — a config-sharing `WireHandler` constructor for the
per-thread handlers and a volatile `updateOrder` field — the wire-update algorithm itself is
untouched. Every individual block tick executes the same vanilla code with the same inputs
it would receive serially.

---

## 1. What is parallelized

The only phase replaced is the *run* stage of the block-tick loop:

```
ServerLevel.tick(...)
  └─ blockTicks phase:
       collectTicks(...)      ← vanilla, serial, main thread (unchanged)
       RUN COLLECTED TICKS    ← ParallelRedstone: serial or clustered parallel (this is the only fork)
       cleanupAfterTick()     ← vanilla, serial, main thread (unchanged)
```

Fluid ticks, entity ticks, block-entity ticks, chunk source ticking, scheduled-tick
*collection* and *cleanup* are untouched and stay on the server thread.

The fork point is a single line in `ServerLevel.tick`:
`this.blockTicks.tick(tick, 65536, this::tickBlock)` becomes
`ParallelRedstone.tickBlockTicks(this, tick, 65536)`, and the helper reproduces the
vanilla `LevelTicks.tick` profiler pushes (`collect` / `run` / `cleanup`) verbatim.

Because all parallelism happens *inside* one `ServerLevel.tick` call, `/tick freeze`,
`/tick step` and the other `/tick` subcommands keep working unchanged: the server still
advances one whole game-tick per step, and queries issued between ticks observe the same
state vanilla would present at that boundary. No `/tick` command was disabled.

## 2. Configuration

| System property | Default | Meaning |
|---|---|---|
| `-Dveltis.parallelRedstone` | `true` | `false` forces the exact vanilla serial path every tick. |
| `-Dveltis.parallelRedstoneThreads` | `availableProcessors - 1` | Worker pool size. `≤ 0` disables parallelism. |
| `-Dveltis.parallelRedstoneStats` | `0` | `N > 0` logs one summary line every N block-tick phases (counted across all levels) to the `VeltisParallelRedstone` logger: parallel/serial-fallback counts, average pending ticks, average regions, and average clustering/phase times. Off by default; when off the cost is one field read per phase. |

The pool is a lazily created fixed pool of daemon threads named `Veltis-ParallelRedstone`;
it is created on first use and lives for the process lifetime.

## 3. Per-tick algorithm

1. **Collect (main thread, vanilla):** `LevelTicks.collectTicks` fills `toRunThisTick` in
   exact vanilla order. Unchanged code.
2. **Serial fallback** if any of these hold — each falls through to
   `runCollectedTicks(level::tickBlock)`, i.e. the byte-for-byte vanilla path:
   - parallelism disabled, or fewer than 16 collected ticks;
   - fewer than 2 distinct pending chunks;
   - any chunk in the 25-chunk window (Chebyshev ≤ 2) of a pending chunk, or any chunk
     reached while walking a propagator component, is not loaded (see §4);
   - a propagator component could not be verified or exceeds 131072 positions.
3. **Grouping:** collected ticks are grouped per chunk (`LinkedHashMap`, insertion order =
   vanilla drain order). A global list in vanilla order is kept as the ordering authority.
4. **Clustering (main thread):** union-find over pending chunks (§4) yields independent
   regions. Each region's tick list is built by iterating the *global* list, so the ticks
   inside one region keep their exact vanilla relative order, and region membership never
   changes any tick's contents.
5. **Dispatch:** an `AtomicInteger` cursor hands regions to the worker pool *and* the server
   thread (the main thread participates as a worker). Each region runs on one thread:
   `level.tickBlock(pos, type)` per entry — identical to vanilla's per-tick call.
6. **Barrier:** workers and main signal a `CountDownLatch`; the main thread waits with
   `chunkSource.mainThreadProcessor.managedBlock(() -> latch.getCount() == 0)` (deadlock
   analysis in §6).
7. **Post-barrier (main thread, in order):** leave the phase, drain deferred shared-state
   operations (§5) in enqueue order, clear `toRunThisTick`, clear `toRunThisTickSet`, append
   every tick to `alreadyRunThisTick` in drain order, rethrow the first worker failure if
   any. `cleanupAfterTick()` then runs as vanilla does.
8. **Failure path:** a throwing region stops the dispatch of further regions; regions
   already in flight run to their own end. This crash-path divergence (vanilla stops at the
   first throw for *all* subsequent ticks) is documented and only observable when a tick
   already crashes the server.

## 4. Isolation proof — how regions are formed

The proof works at **chunk-column granularity**, relying on one modelled bound (stated
explicitly below): every block a tick's own handler reads or writes stays within 16 blocks
of its tick position, and every block a propagator component reads or writes stays within
16 blocks of its component positions. (Signal reach ≤ 15, piston push ≤ 12, so 16 covers
all bounded effects; Alternate Current's wire recomputation writes only wire positions.)
With that bound, all read/write sets are confined to chunk halos of width 1, and pending
chunks are merged (union-find) when either rule fires:

**Rule A — proximity.** Two pending chunks within Chebyshev distance ≤ 2 merge. Their
±1-chunk halos can then share a column, so any two bounded side-effect zones that could
overlap are forced into the same region.

**Rule B — unbounded-propagator components.** Wire networks (Alternate Current), rail
lines, tripwire chains and fence/wall/iron-bar shape-update chains propagate same-tick
updates without bound — through *unbounded propagators* only (`isUnboundedPropagator`:
redstone wire, fences, walls, iron bars, rails, tripwire, tripwire hooks; the set is
deliberately over-inclusive, §5). Every other reactive block either schedules its state
change or reacts within a bounded radius, which Rule A already covers.

For each pending chunk, its 25-chunk window (Chebyshev ≤ 2) is scanned. Every propagator
position in the window seeds a 6-connected flood fill over the per-chunk propagator sets,
producing a *component* (the component is discovered once and reused). Each component is
*anchored* to every pending chunk whose window contains any of its positions, so any
pending tick that can reach a component joins that component's region. After anchoring,
two components whose position columns sit within Chebyshev distance ≤ 2 of each other are
merged: their ±16-block read/write halos then overlap, so they must never run apart.

The soundness argument is a T-set disjointness argument. A tick touches only
chunks(position) ± 1 (modelled bound). A component touches only chunks(positions) ± 1
(modelled bound). Therefore a conflict between the work of two pending ticks is captured
exactly when their chunks are within Chebyshev ≤ 2 (Rule A); a conflict between a pending
tick and a component is captured exactly when the component has a position inside the
pending chunk's ±2 window (Rule B anchoring); and a conflict between two components is
captured exactly when their position columns are within Chebyshev ≤ 2 (the final merge).
If two pending chunks end in different regions, no bounded effect can cross between them
and no unbounded chain connects them — running them on two threads cannot change any
observable outcome.

**Documented assumption (the modelled bound).** The argument assumes that any same-tick
update chain that does *not* pass through an unbounded propagator stays within 16 blocks of
its origin. The code audit found the longest such chain to be 2 blocks; all unbounded
propagation passes through a block that is in the propagator set. TNT needs no special
case: a primed TNT entity's fuse is a scheduled tick, and its explosion radius (~6 blocks)
is inside the bound above. Known remaining caveat: a *fall cascade* (e.g. vines or ladders
losing support and popping downward block by block) chains through blocks that are not
propagators; it is the same documented limitation as the pre-chunk-clustering
implementation and is bounded in practice by how far a cascade can travel in one tick.

All region scans (`getChunkNow`, per-chunk propagator sets, component walks) run on the
main thread *before* dispatch, so no mid-phase chunk load can occur (any unloaded chunk
inside the window or inside a component walk aborts to the serial path instead).

**Same-tick visibility of deferred effects** (§5): deferred operations drain after the
barrier but still inside `ServerLevel.tick`, before fluid ticks, the chunk-source tick,
and the entity phase — the same positions in the tick where vanilla would have performed
them. Per-position FIFO order among deferred operations is preserved by a single
concurrent queue in enqueue order.

## 5. Shared-state handling (the full inventory)

| State | Strategy |
|---|---|
| `Level.neighborUpdater` (`CollectingNeighborUpdater`) | The shared instance stays owned by the server thread. `Level.veltis$neighborUpdater()` returns it on the main thread and a per-thread `CollectingNeighborUpdater` (same `maxChainedNeighborUpdates`) on workers. Each updater's queue drains fully inside its top-level call, so no queued work leaks across threads. All four read sites switched (`Level.shapeUpdate`, three `ServerLevel` neighbor dispatches); the debug listener stays on the shared main-thread field (debug-only). |
| Alternate Current `WireHandler` | Per-thread: `alternate_current$getWireHandlerForThread()` returns the primary handler on the server thread, else lazily constructs `new WireHandler(level, config)` once per pool thread per level, sharing the primary handler's already-loaded `Config` (the config file is read exactly once, by the primary's `storage` constructor; `Config.Primary.updateOrder` is `volatile` so runtime `/alternatecurrent` changes are visible to every thread). The save path (018) keeps the primary handler. `RedstoneWireBlock`'s three call sites switched. |
| `LevelTicks` containers & schedule | Single-monitor design: `schedule`, `addContainer`, `removeContainer`, `updateContainerScheduling`, `hasScheduledTick` are `synchronized` (reentrant, so the `onTickAdded` → `updateContainerScheduling` path re-enters safely; container methods themselves are deliberately *not* synchronized to avoid lock-order inversion with `chunkScheduleUpdater`). Workers schedule, the barrier's chunk pump may register/unregister containers — both take the same monitor. |
| `LevelTicks.willTickThisTick` | First checks the calling thread's region set (`ParallelRedstone.currentRegionTicks()`): workers only ever see their own region's pending ticks, with each entry removed from the set immediately before it executes — reproducing vanilla's poll-before-run semantics for the four vanilla callers, all of which query their own position (⇒ own region ⇒ exact vanilla answer). The vanilla path is only reachable off-phase (single-threaded). |
| `Level.subTickCount` | `AtomicLong` (`getAndIncrement`); readers unchanged. |
| `RedstoneTorchBlock.RECENT_TOGGLES` (burnout) | The prune loop and the whole body of `isToggledTooFrequently` are `synchronized (RECENT_TOGGLES)`; `setBlockAndUpdate` stays outside the lock. Lock order is RECENT_TOGGLES → nothing (one-directional), no deadlock. Vanilla-visible semantics (60-tick window, 8-toggle cap) preserved under the lock. |
| `GameEventDispatcher.post` | `synchronized` (per-level dispatcher). |
| `ServerLevel.blockEvent` | `synchronized` (leaf lock: workers enqueue piston block events; `runBlockEvents` stays lock-free — post-barrier, latch happens-before). |
| `Level.addBlockEntityTicker` | `synchronized` (ArrayList mutation reachable from workers via piston block entities). |
| `ServerLevel.sendBlockUpdated` | Deferred while the phase is active (concurrent queue): avoids the `isUpdatingNavigations` re-entrancy flag race, navigation recompute races, `chunkSource.blockChanged` / `pathTypesByPosCache` races. Drained post-barrier, before the fluid/entity phases; per-position FIFO preserved. |
| `ServerLevel.addEntity` | Deferred while the phase is active (same queue, single FIFO): the entity manager is main-thread only. Returns `true` optimistically; the entity is registered before this tick's entity phase. (`addPlayer` stays direct — main-thread only.) POI writes already marshal through `getServer().execute`, which is *not* pumped mid-phase, so they run between ticks exactly as vanilla. |
| Worker chunk reads | `ServerChunkCache.getChunk` gains a behavior-identical fast path for non-main threads: consult the volatile copy-on-write `visibleChunkMap`, return only on a positive status hit, otherwise fall through to the vanilla main-thread roundtrip. A hit is exactly what the roundtrip would have returned (`GenerationChunkHolder.getChunkIfPresent`), minus the queue hop that would stall against the barrier. |
| Unbounded-propagator cache | `LevelChunk.veltisUnboundedPropagators` is a `volatile` fastutil `LongOpenHashSet` of packed `BlockPos`es (the chunk's unbounded-propagator positions), or `null` when not computed. It is computed on the server thread during clustering (`unboundedPropagatorsOf`: skip air-only sections, `section.maybeHas(predicate)`, else full section scan) and reused across ticks; the `setBlockState` hook resets it to `null` **only** when `propagatorMembershipChanged(old, new)` — i.e. only when a block change crosses the propagator boundary (air ↔ propagator, or propagator ↔ different propagator block). Power or state changes on existing propagators keep membership identical, so a ticking circuit never forces a rescan. Workers only ever write `null`; the main thread re-computes before the next phase. |
| Propagator predicate `isUnboundedPropagator` | Conservative and deliberately over-inclusive: redstone wire, fence, wall, iron bars, any `BaseRailBlock`, tripwire, tripwire hook. Exactly the blocks whose same-tick update chains are unbounded (wire network recomputation; shape/connectivity update chains). Every other reactive block schedules its state change or reacts within bounded reach. Over-inclusion only merges regions — always safe. |

**Iteration order within a region is vanilla order** (global drain order restricted to the
region), so intra-region behaviour is bit-for-bit serial behaviour.

## 6. Barrier and deadlock analysis

The main thread runs its own regions first, then waits via
`ServerChunkCache.mainThreadProcessor.managedBlock`. `managedBlock` bumps `blockingCount`,
forcing the chunk-source queue to run tasks regardless of `haveTime`.

- **Worker chunk reads that miss the fast path** queue `supplyAsync(...).join()` onto that
  same queue → the waiting main thread services them → latch drops → no deadlock. This is
  why the wait targets the *chunk-source* queue specifically.
- **`getChunkFutureMainThread`** drives ticket and distance updates inline (it is the code
  running on that queue), so progress never depends on the outer tick loop, which is
  suspended inside the barrier.
- **The `MinecraftServer` queue is deliberately *not* pumped.** Packet handlers and POI
  `execute()` tasks stay queued to the tick boundary — exactly vanilla's behavior during
  its own chunk-queue `managedBlock` calls — so player actions and POI updates never
  interleave with block ticking, same as serial vanilla.
- Light engine enqueues, `ChunkSource.onSectionEmptinessChanged`, and netty `Connection.send`
  are thread-safe or no-ops as-is.

## 7. Accepted nondeterminism (never affects redstone)

- `Level.random` / `Level.randValue` LCG: concurrent draws can tear; only grass/fire
  (and similar) targets consume randomness, never redstone.
- Cross-region interleaving of game-event, sound and level-event dispatch order.
- Deferred entity adds become visible post-blockTicks (same tick, before the entity phase).
- A `willTickThisTick` query from a region thread about a *foreign* position returns that
  region's local answer (all current callers query their own position).
- Debug-synchronizer broadcasts from workers (debug builds only).

## 8. Accepted residual risk (documented, monitored)

- **Entity removal** triggered *by block ticks* (e.g. an instant death inside a block tick)
  touches `PersistentEntitySectionManager` / `ChunkMap` main-thread structures. Normal
  death removal happens in the entity tick phase (main thread); only the exotic
  block-tick-driven instant death is at risk.
- Exotic on-demand chunk load + instant unload could race a container registration; the
  loaded-window check converts the common cases to serial fallback.
- `RandomSource` jitter across workers (see §7).
- A same-tick chain that leaves the unbounded-propagator set (documented assumption in §4,
  e.g. an exotic fall cascade) is not tracked; same accepted limitation as the previous
  implementation.

## 9. Validation status

- `applyVeltisPatches` applies 001..019 from pristine source; 017 and 018 remain
  byte-identical (verified via git — no diff).
- `buildVeltisMC` succeeds; the packaged server jar contains
  `org/veltismc/redstone/ParallelRedstone.class` (+ the `RegionFinder` inner class) and no
  `net/veltis` classes, and all three `ServerLevel` hooks `invokestatic` the
  `org.veltismc.redstone.ParallelRedstone` FQN (verified via `jar tf` and `javap -c`).
- The behavioral/E2E test suite and JFR benchmarks exist under
  `server/src/test/java/org/veltismc/redstone/` but are intentionally **not run** in this
  pass (implementation-first instruction; the maintainer benchmarks with a physical
  calculator build). Run them before enabling the feature in a release build if runtime
  evidence is required.
