# Veltis Parallel Redstone — Concurrency Guarantees

This document is the contract for the parallel redstone implementation
(`server/Shulker/code/019-Parallel-Redstone.patch`,
`org/veltismc/redstone/ParallelRedstone.java`). It records what runs in parallel, why
audited redstone state transitions are equivalent to vanilla serial ticking, which shared
state was made thread-safe, and exactly where documented divergences remain.

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
| `-Dveltis.parallelRedstoneMinTicks` | `16` | Below this many collected ticks the phase runs serial (`serialSmall`). Unparsable/`≤ 0` values fall back to the default. |
| `-Dveltis.parallelRedstoneStats` | `0` | `N > 0` logs one summary line every N block-tick phases (counted across all levels) to the `VeltisParallelRedstone` logger: parallel/serial-fallback counts (including `serialUnauditedTick` and `serialUnsafeNeighbourhood`), average pending ticks, average/max regions, and per-phase timings (collect/group/cluster/work-list/dispatch/work/barrier/drain in µs, average phase total, average deferred). Off by default; when off the cost is one field read per phase. |

The pool is a lazily created fixed pool of daemon threads named `Veltis-ParallelRedstone`;
it is created on first use and lives for the process lifetime.

## 3. Per-tick algorithm

1. **Collect (main thread, vanilla):** `LevelTicks.collectTicks` fills `toRunThisTick` in
   exact vanilla order. Unchanged code.
2. **Admission audit and serial fallback.** Before any region is formed, the phase is
   admitted only if every scheduled tick type is on the exact-class allowlist and every
   chunk in the reachable window passes the block audit. Otherwise the phase runs through
   `runCollectedTicks(level::tickBlock)`, i.e. the byte-for-byte vanilla serial path:
   - parallelism disabled, or fewer than `parallelRedstoneMinTicks` (default 16) collected
     ticks;
   - fewer than 2 distinct pending chunks;
   - any collected tick whose exact runtime block class is not in
     `AUDITED_TICK_CLASSES` (`serialUnauditedTick`);
   - any chunk in the 25-chunk window (Chebyshev ≤ 2) of a pending chunk, or any chunk
     reached while walking a propagator component, is not loaded (see §4);
   - any chunk in that window contains a block that overrides a reactive callback from a
     non-platform code source, or cannot be verified (`serialUnsafeNeighbourhood`);
   - a propagator component could not be verified or exceeds 131072 positions.

   The allowlist is matched by exact runtime class, not `instanceof`: a subclass of a
   listed vanilla block is not audited, so an overridden `tick` can never enter the
   parallel path. See §4 for why unknown workloads fall back instead of being assumed
   safe.
3. **Grouping:** collected ticks are grouped per chunk (`LinkedHashMap`, insertion order =
   vanilla drain order). A global list in vanilla order is kept as the ordering authority.
4. **Clustering (main thread):** union-find over pending chunks (§4) yields independent
   regions. Each region's tick list is built by iterating the *global* list, so the ticks
   inside one region keep their exact vanilla relative order, and region membership never
   changes any tick's contents.
5. **Dispatch:** regions are sorted largest-first (workers pull from a shared cursor, so
   starting the biggest region minimises the tail where only the largest region is still
   running; cross-region order is already nondeterministic and intra-region order comes
   from the global list, so the sort cannot change observable behavior). An
   `AtomicInteger` cursor then hands regions to the worker pool *and* the server
   thread (the main thread participates as a worker). Each region runs on one thread:
   `level.tickBlock(pos, type)` per entry — identical to vanilla's per-tick call.
6. **Barrier:** workers and main signal a `CountDownLatch`; the main thread waits with
   `chunkSource.mainThreadProcessor.managedBlock(() -> latch.getCount() == 0)` (deadlock
   analysis in §6).
7. **Post-barrier (main thread, in order):** leave the phase, restore the exact
   post-`runCollectedTicks` bookkeeping (clear `toRunThisTick`, clear `toRunThisTickSet`,
   append every executed tick to `alreadyRunThisTick` in execution order), then drain
   deferred shared-state operations (§5) in enqueue order and rethrow the first worker
   failure if any. Restoring the bookkeeping *before* the drain means a deferred callback
   that queries `willTickThisTick` observes vanilla's post-run answer (nothing still
   queued), not the stale collected set. `cleanupAfterTick()` then runs as vanilla does.
8. **Failure path:** a throwing region stops the dispatch of further regions; regions
   already in flight run to their own end. This crash-path divergence (vanilla stops at the
   first throw for *all* subsequent ticks) is documented and only observable when a tick
   already crashes the server.

## 4. Isolation proof — admission audit plus region geometry

Parallelism is admitted by an explicit workload audit first; distance is only the second
half of the proof. An unknown or unsafe workload never relies on the 16-block envelope:

**4.1 Tick audit.** Every collected tick's *exact runtime class* must be in
`AUDITED_TICK_CLASSES` (vanilla wire, repeater, comparator, torch, observer, piston,
rails, tripwire, fence/wall/bar, doors, buttons, levers, plates, target, note block, TNT,
falling blocks, anvil, dragon egg, ore, lightning rod, daylight detector). This is an
exact-class match, so a third-party subclass of an audited block is rejected
(`serialUnauditedTick`). Ticks not on the list — dispensers, fire, command blocks, any
plugin block — take the vanilla serial path for the whole phase.

**4.2 Neighbourhood audit.** A tick can synchronously reach blocks in its window through
`neighborChanged`, `updateShape`, `tick`, `onPlace` and `affectNeighborsAfterRemoval`.
Every block class is classified once by reflection over those five callbacks:

- `PASSIVE`: declares none of them (stone, air, most decoration) — any number is safe;
- `AUDITED`: declares one and comes from the platform code source (the widened server
  jar or the patched runtime classes directory);
- `UNAUDITED`: declares one and comes from any other code source (a plugin jar, a test
  class, or a class that merely reuses a `net.minecraft.*` package name).

The verdict is cached per block class in a `ClassValue`; the per-chunk answer is a
tri-state byte on `LevelChunk` (`0` unknown, `1` safe, `2` unsafe). A scan first uses the
section palette (`maybeHas`) and, on a hit, confirms against all 4096 cells of that
section. The confirmation is required because a palette may retain a class after its last
block is removed: without it, one plugin block would make a chunk permanently serial.
The cache is invalidated only by a write that crosses the unaudited boundary
(`neighbourhoodAuditChanged`), so power/state changes never force a rescan. If any chunk
in the 5×5 window around a pending chunk, or in the 5×5 halo around a component chunk, is
unsafe or cannot be verified, the whole phase falls back to serial
(`serialUnsafeNeighbourhood`). This is what makes unknown third-party behaviour degrade
safely instead of being assumed compatible.

The residual region proof then works at **chunk-column granularity**, relying on one
modelled bound (stated explicitly below): every block a tick's own handler reads or writes
stays within 16 blocks of its tick position, and every block a propagator component reads
or writes stays within 16 blocks of its component positions. (Signal reach ≤ 15, piston
push ≤ 12, so 16 covers all bounded effects; Alternate Current's wire recomputation writes
only wire positions.)
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
| `LevelTicks.willTickThisTick` | During a parallel phase, consults the phase-wide remaining set of the owning level (a `ConcurrentHashMap` keyed by position, updated as each tick starts), so queries for positions outside the current region still reflect whether that tick has run yet in this phase — preserving vanilla poll-before-run semantics for both same-region and cross-region queries. Off-phase, the vanilla set is used. |
| `Level.subTickCount` | `AtomicLong` (`getAndIncrement`); readers unchanged. |
| `RedstoneTorchBlock.RECENT_TOGGLES` (burnout) | The prune loop and the whole body of `isToggledTooFrequently` are `synchronized (RECENT_TOGGLES)`; `setBlockAndUpdate` stays outside the lock. Lock order is RECENT_TOGGLES → nothing (one-directional), no deadlock. Vanilla-visible semantics (60-tick window, 8-toggle cap) preserved under the lock. |
| `GameEventDispatcher.post` | `synchronized` (per-level dispatcher). |
| `ServerLevel.blockEvent` | `synchronized` (leaf lock: workers enqueue piston block events; `runBlockEvents` stays lock-free — post-barrier, latch happens-before). |
| `Level.addBlockEntityTicker` | `synchronized` (ArrayList mutation reachable from workers via piston block entities). |
| `ServerLevel.sendBlockUpdated` | Deferred while the phase is active (concurrent queue): avoids the `isUpdatingNavigations` re-entrancy flag race, navigation recompute races, `chunkSource.blockChanged` / `pathTypesByPosCache` races. Drained post-barrier, before the fluid/entity phases; per-position FIFO preserved. |
| `ServerLevel.addEntity` | Deferred while the phase is active (same queue, single FIFO): the entity manager is main-thread only. Returns `true` optimistically; the entity is registered before this tick's entity phase. (`addPlayer` stays direct — main-thread only.) POI writes already marshal through `getServer().execute`, which is *not* pumped mid-phase, so they run between ticks exactly as vanilla. |
| Worker chunk reads | `ServerChunkCache.getChunk` gains a behavior-identical fast path for non-main threads, but **only while `ParallelRedstone.isPhaseActiveFor(level)`**. During that window the main thread is executing a region or parked in the barrier pumping that same executor; it cannot run `ChunkMap.tick()`, `processUnloads()`, level unload or holder eviction, which are the only paths that demote or remove a published chunk. Region discovery also requires the requested chunk and its whole 5×5 audit window at `FULL`, and `FULL` publication is monotonic, so the returned `LevelChunk` object is stable for the whole phase. A hit is exactly what `getChunkIfPresent` would return; the path never creates, promotes, loads or stores, and any miss (mid-promotion, unloaded, outside a phase) falls through to the vanilla main-thread roundtrip, which remains the single writer for lifecycle state. |
| Unbounded-propagator cache | `LevelChunk.veltisUnboundedPropagators` is a `volatile` fastutil `LongOpenHashSet` of packed `BlockPos`es (the chunk's unbounded-propagator positions), or `null` when not computed. It is computed on the server thread during clustering (`unboundedPropagatorsOf`: skip air-only sections, `section.maybeHas(predicate)`, else full section scan) and reused across ticks; the `setBlockState` hook resets it to `null` **only** when `propagatorMembershipChanged(old, new)` — i.e. only when a block change crosses the propagator boundary (air ↔ propagator, or propagator ↔ different propagator block). Power or state changes on existing propagators keep membership identical, so a ticking circuit never forces a rescan. Workers only ever write `null`; the main thread re-computes before the next phase. |
| Neighbourhood-audit cache | `LevelChunk.veltisNeighbourhoodAudit` is a tri-state byte (`0` unknown, `1` safe, `2` unsafe) computed on the server thread during region discovery. It is cleared by the `setBlockState` hook only when `neighbourhoodAuditChanged(old, new)`, i.e. the block class crosses the `UNAUDITED` boundary; state-only changes keep the cached answer. A palette hit is confirmed by a 4096-cell scan, so stale palette entries cannot keep a chunk serial. The classification itself is a `ClassValue` keyed by exact block class. |
| Propagator predicate `isUnboundedPropagator` | Conservative and deliberately over-inclusive: redstone wire, fence, wall, iron bars, any `BaseRailBlock`, tripwire, tripwire hook. Exactly the blocks whose same-tick update chains are unbounded (wire network recomputation; shape/connectivity update chains). Every other reactive block schedules its state change or reacts within bounded reach. Over-inclusion only merges regions — always safe. |

**Iteration order within a region is vanilla order** (global drain order restricted to the
region), so intra-region behaviour is bit-for-bit serial behaviour.

**Deferred admission and drain.** `Phase.offer` and `Phase.closeAdmission` share one
monitor, so an operation is either enqueued before admission closes (and is guaranteed to
be drained by the post-barrier loop, exactly once, in enqueue order) or rejected and
executed immediately by its caller. The drain runs on the server thread outside the lock;
if a drained callback itself tries to defer work, the closed gate rejects it and the
callback's immediate fallback runs it inline on the server thread. Shutdown waits for the
in-flight phase to end (`LevelState.release`), and `endPhase` always unregisters the phase
even when draining throws, so a failure can never strand the lifecycle monitor.

## 6. Barrier and deadlock analysis

The main thread runs its own regions first, then waits via a hard quiescence loop
(`awaitQuiescence`), pumping `ServerChunkCache.mainThreadProcessor.managedBlock` until the
remaining-task counter reaches **zero**. The loop ignores and records interrupts and only
returns when every worker has finished; it then restores the interrupt status. Interrupting
the server thread therefore cannot make the caller proceed while workers still mutate the
level. A pump failure is retained as the primary failure (later pump failures attached as
suppressed) and the loop still waits for quiescence before cleanup and propagation.
`managedBlock` bumps `blockingCount`, forcing the chunk-source queue to run tasks
regardless of `haveTime`.

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

- `Level.random` is the thread-safe `RandomSource` variant (the same 48-bit LCG under a
  CAS retry loop instead of a ThreadingDetector trip). The serial path therefore keeps the
  exact vanilla draw sequence. In a parallel phase, draws from different regions interleave
  nondeterministically, so **no claim of a byte-identical RNG sequence is made**.
  Redstone decisions themselves do not consume randomness, but an audited RNG consumer
  (for example the dragon egg's teleport target) can produce a different choice across
  runs. If exact reproducibility matters, `-Dveltis.parallelRedstone=false` restores the
  vanilla sequence.
- Cross-region interleaving of game-event, sound and level-event dispatch order.
- Deferred entity adds become visible post-blockTicks (same tick, before the entity phase).
- A `willTickThisTick` query from a region thread about a *foreign* position is answered
  from the phase-wide remaining set of the owning level, so it reports whether that tick
  has already started anywhere in the phase (vanilla-equivalent poll-before-run).
- Debug-synchronizer broadcasts from workers (debug builds only).

## 8. Accepted residual risk (documented, monitored)

- **Entity removal** triggered *by block ticks* (e.g. an instant death inside a block tick)
  touches `PersistentEntitySectionManager` / `ChunkMap` main-thread structures. Normal
  death removal happens in the entity tick phase (main thread); only the exotic
  block-tick-driven instant death is at risk.
- Exotic on-demand chunk load + instant unload could race a container registration; the
  loaded-window check converts the common cases to serial fallback.
- `RandomSource` jitter across workers (see §7); parallel RNG draw order is not
  reproducible.
- The audit covers the five synchronous callbacks listed in §4.2. A third-party block that
  schedules work, mutates state from a block entity ticker, or reaches state through
  another mod's event bus is outside that model; any *reactive* class is still vetoed by
  the code-source rule, and anything not on the exact tick allowlist already runs serial.
  No arbitrary third-party compatibility is claimed.
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
- `rebuildVeltisPatches` is reproducible (re-running it regenerates nothing).
- The behavioral/E2E suite under `server/src/test/java/org/veltismc/redstone/` runs green
  in CI-equivalent `:server:test`: `AlternateCurrentBehaviorTest` (11 tests),
  `ParallelRedstoneSchedulingTest` (6), `ParallelRedstoneRegionFinderTest` (11, including
  the unsafe-neighbourhood veto), `ParallelRedstoneCacheInvalidationTest` (10, including
  the real `LevelChunk.setBlockState` audit-invalidation loop, the default/opt-out and
  exact-class allowlist checks, and a live forked-server dispenser phase that proves
  `serialUnauditedTick` and later recovery), `ParallelRedstoneConcurrencyTest` (4),
  `ParallelRedstoneDifferentialTest` (1 — serial/parallel same-tick equivalence over
  randomized worlds) and `ParallelRedstoneLifecycleTest` (18 server-free tests covering
  deferred admission/drain, per-tick failure policy, `willTickThisTick` identity, hard
  quiescence under interrupts and pump failures, and the shutdown protocol). The
  forked-server harness disables structure generation (`generate-structures=false`) so
  circuit placement is deterministic. The gated benchmark test is skipped (1) unless
  `-PveltisBench` is set.
- `RedstoneWorkloadBenchmark` (gated behind `-PveltisBench`) measures the four-cluster
  repeater-ring workload and reports `/tick query` percentiles plus a JFR per-thread and
  per-frame distribution; the child's worker count, stats interval and per-cluster grid
  size are selectable from Gradle (`-PveltisParallelRedstone=false`,
  `-PveltisParallelRedstoneThreads=N`, `-PveltisParallelRedstoneStats=N`,
  `-PveltisBenchGrid=N`).
