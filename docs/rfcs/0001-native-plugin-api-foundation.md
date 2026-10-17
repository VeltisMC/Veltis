# RFC: Veltis Native Plugin API — Multithreading by Design

**Status: DRAFT — MAINTAINER REVIEW REQUIRED**  
**Target branch:** `api-dev`  
**Baseline reviewed:** `dfdca140eeafe44c6193e65f26c07b978ea9c5ec`

> This document is a proposal for discussion. The API shown here is conceptual and is not implemented.

## 1. Motivation

Veltis is defining its public API from scratch. This first contribution focuses on a small foundation that maintainers can review without freezing unfinished runtime internals or trying to model every Minecraft concept at once.

Veltis is multithreaded, so a plugin API built around a universal main thread would hide important correctness constraints. The design instead makes execution context, ownership, lifecycle, cancellation, and failure explicit at the API boundary.

## 2. Confirmed requirements

This RFC follows the current project direction:

- Veltis will define a native API rather than emulate an existing plugin API.
- Bukkit, Spigot, and Paper compatibility are explicitly out of scope.
- Multithreading and ownership must be foundational properties.
- The API should be practical and clear for native Veltis developers.
- The first contribution is documentation for maintainer review, not implementation authorization.
- Frontman and WatermanMC are intended reviewers before production API work begins.

## 3. Goals

The foundation should:

1. make execution context visible at API boundaries;
2. route mutable work to its owner;
3. treat migration, cancellation, shutdown, and failure as normal states;
4. scope tasks and subscriptions to a plugin lifecycle;
5. keep potentially slow work asynchronous and result-bearing;
6. provide actionable wrong-context diagnostics;
7. prove a small end-to-end MVP before expanding the API;
8. permit compatible evolution through an explicit public API version.

## 4. Non-goals

This proposal does **not**:

- provide Bukkit, Spigot, or Paper compatibility;
- load existing Bukkit or Paper plugins unchanged;
- define a drop-in compatibility layer;
- implement the complete Minecraft API;
- change the runtime, NMS bridge, scheduler, or world engine;
- add dependencies or modify Shulker patches;
- claim superior performance without benchmarks;
- freeze a complete world, chunk, entity, or event taxonomy in the first version.

Paper and Folia are comparison references only. They are not compatibility targets or implementation sources.

## 5. Current Veltis architecture

The reviewed commit contains useful internal foundations, but internal classes are not automatically public API contracts.

### 5.1 Existing API surface

The `veltis-api` module is currently small, including `Server`, `Player`, and `CommandSender`:

- `veltis-api/src/main/java/org/veltismc/api/Server.java`
- `veltis-api/src/main/java/org/veltismc/api/entity/Player.java`
- `veltis-api/src/main/java/org/veltismc/api/command/CommandSender.java`

### 5.2 Runtime and lifecycle

`ServerRuntime` and `DefaultServerRuntime` expose lifecycle state, startup and shutdown, scheduling, world services, events, and metrics:

- `server/src/main/java/org/veltismc/runtime/ServerRuntime.java`
- `server/src/main/java/org/veltismc/runtime/DefaultServerRuntime.java`
- `server/src/main/java/org/veltismc/runtime/VeltisBootstrap.java`

The current implementation performs startup and shutdown synchronously on the caller thread while returning completed futures. A future public contract must document actual execution semantics rather than infer asynchronous behavior from a future-shaped return type.

### 5.3 Region, world, and chunk foundations

The world engine is region-oriented:

- `server/src/main/java/org/veltismc/world/api/WorldEngine.java`
- `server/src/main/java/org/veltismc/world/api/World.java`
- `server/src/main/java/org/veltismc/world/api/RegionScheduler.java`
- `server/src/main/java/org/veltismc/world/core/WorldImpl.java`
- `server/src/main/java/org/veltismc/world/region/RegionImpl.java`
- `server/src/main/java/org/veltismc/world/scheduler/RegionSchedulerImpl.java`

The existing `RegionScheduler` models global migratable jobs and region-bound jobs. It provides evidence for owner routing, worker ownership, cancellation/status handles, and shutdown. Worker queues, inboxes, ownership maps, and state-machine internals must remain private.

`World.chunk(...)` returns a handle, while `ChunkHandle.load()` and `unload()` return futures. This supports an asynchronous direction but does not settle public persistence, generation, cancellation, or failure semantics.

### 5.4 Tasks and events

The current task and event implementations are useful references:

- `server/src/main/java/org/veltismc/runtime/scheduler/TaskScheduler.java`
- `server/src/main/java/org/veltismc/runtime/scheduler/TaskHandle.java`
- `server/src/main/java/org/veltismc/runtime/scheduler/DefaultTaskScheduler.java`
- `server/src/main/java/org/veltismc/runtime/event/EventBus.java`
- `server/src/main/java/org/veltismc/runtime/event/SimpleEventBus.java`

The current event bus dispatches synchronously on the publishing thread. That is implementation evidence, not a universal public guarantee. The public API must specify affinity, ordering, reentrancy, listener failure, and cleanup.

## 6. Native multithreading principles

### 6.1 Execution contexts

The initial vocabulary should distinguish:

- **Global context:** execution for state explicitly owned by the server-wide global domain.
- **Region context:** execution for mutable spatial state owned by one region.
- **Entity context:** execution for an entity's current owner; the owner may change after migration.
- **Async context:** CPU or I/O work that must not access non-thread-safe live world state.

A global job is work submitted to the global context. It is not permission to access arbitrary regions. An async task is not a world context. A callback must explicitly re-enter a region or entity context before mutating live state.

### 6.2 Ownership before mutation

A region-owned value may be mutated only by its owner context. Cross-region work is a routed command or asynchronous operation. Entity work targets an entity identity and resolves its current owner when it executes; it must not capture a worker thread permanently.

### 6.3 No blocking cross-region waits

Region workers must not block while waiting for another region, entity migration, or persistence that requires a region worker. Composition should use asynchronous stages, routed commands, or explicit owner callbacks.

### 6.4 Explicit cost

Disk access, generation, network access, cross-region coordination, and expensive queries must not hide behind a cheap-looking getter. A handle identifies a target; it does not claim that the target is loaded.

### 6.5 Boundary-safe data

Snapshots, event payloads, positions, identifiers, and results crossing execution boundaries should be immutable or defensively copied. Live mutable implementation objects, backing collections, worker queues, and NMS objects must not escape their owner.

### 6.6 Claims are subject to implementation

These are proposed contracts. They become guarantees only after implementation and conformance tests establish them. The RFC does not claim that the current runtime already satisfies every proposed guarantee.

## 7. Plugin identity and lifecycle

The public API should eventually provide a plugin identity and lifecycle context:

```java
// PROPOSED API — NOT IMPLEMENTED
public interface VeltisPlugin {
    PluginInfo info();
    void onEnable(PluginContext context);
    void onDisable(DisableReason reason);
}
```

A minimal plugin must provide identity metadata and implement both lifecycle methods. For example:

```java
// PROPOSED API — NOT IMPLEMENTED
public final class WelcomePlugin implements VeltisPlugin {
    private PluginContext context;

    @Override
    public PluginInfo info() {
        return PluginInfo.of("welcome", "0.1.0");
    }

    @Override
    public void onEnable(PluginContext context) {
        this.context = context;
        context.logger().info("Enabled " + context.plugin().id());
    }

    @Override
    public void onDisable(DisableReason reason) {
        if (context != null) {
            context.logger().info("Disabled: " + reason);
        }
    }
}
```

Suggested lifecycle states are `CREATED`, `ENABLING`, `ENABLED`, `DISABLING`, `DISABLED`, and `FAILED`. Enable failure should prevent normal work from being submitted. Disable should be idempotent and should retire plugin-owned resources before the plugin becomes `DISABLED`.

`PluginContext` should expose only scoped capabilities:

- identity and logger;
- plugin-scoped scheduler views;
- event subscriptions;
- server and world capabilities;
- lifecycle state and cancellation scope.

The discovery and packaging mechanism is intentionally deferred. The lifecycle contract is the MVP concern.

## 8. Execution contexts and plugin-scoped scheduling

The scheduler must make context explicit. An unqualified `execute(Runnable)` is not acceptable unless its context is defined. The preferred MVP is to omit such a method and expose named operations:

```java
// PROPOSED API — NOT IMPLEMENTED
public interface PluginScheduler {
    TaskHandle<Void> executeGlobal(Consumer<GlobalContext> task);
    TaskHandle<Void> executeAsync(Consumer<AsyncContext> task);
    TaskHandle<Void> executeAt(RegionKey region, Consumer<RegionContext> task);
    TaskHandle<Void> executeFor(EntityKey entity, Consumer<EntityContext> task);
}
```

Responsibilities are distinct:

- `executeGlobal` runs work for explicitly global-owned state.
- `executeAsync` runs CPU or I/O work with no live-world access.
- `executeAt` routes work to the region owning the supplied region key.
- `executeFor` resolves the entity's current owner when the operation runs.

A world capability may offer a convenience method for the same region operation, but it must use the same ownership and completion contract:

```java
// PROPOSED API — NOT IMPLEMENTED
public interface WorldOperations {
    OperationHandle<Void> at(BlockPosition position,
        Consumer<RegionContext> operation);
}
```

`WorldOperations.at(...)` is a world-oriented facade that derives the owning region from a position. `PluginScheduler.executeAt(...)` is a general scheduling primitive for callers that already have a region key. They must not implement contradictory semantics.

The task handle should make terminal outcomes observable:

```java
// PROPOSED API — NOT IMPLEMENTED
public interface TaskHandle<T> {
    CompletionStage<TaskOutcome<T>> completion();
    boolean cancel();
    TaskState state();
}
```

Scheduling after plugin disable or server shutdown should be rejected. Cancellation is best effort and has distinct states: a request may be issued, accepted, or refused; acceptance does not mean a running callback has stopped. Queued work can be retired where possible, while running work must cooperate. A callback that ignores cancellation cannot be forcibly stopped safely by this API. If it does not finish, the implementation needs a deadline and documented policy for marking it timed out, isolating it from later callbacks, and completing shutdown without claiming that arbitrary code terminated. A task or operation that already committed its mutation may complete successfully even if cancellation is requested afterward; retirement, callback completion, and mutation commit must remain separately observable.

The API must define queue limits, rejection, repeating-task limits, deadlines, timeout outcomes, and fairness before exposing unbounded submission. Plugin-owned tasks are automatically retired during disable, with explicit handles for earlier cancellation. Shutdown must distinguish cancellation requested, cancellation accepted, callback still running, callback completed, mutation committed, and resource retired; it must not promise forced termination of arbitrary plugin code.

## 9. Operation and error semantics

The RFC uses `TaskHandle<Void>` for scheduler submissions and `OperationHandle<T>` for world/entity operations. Both expose completion, cancellation, and state; their outcome types distinguish task termination from world/entity retirement and operational failure. The examples use explicit `Optional` handling for absent targets. The exact rejected-operation representation remains open:

```java
// PROPOSED API — NOT IMPLEMENTED
public interface OperationHandle<T> {
    CompletionStage<OperationOutcome<T>> completion();
    boolean cancel();
    OperationState state();
}
```

`TaskHandle<T>` is the scheduler-specific form; `OperationHandle<T>` is for world/entity operations that may involve routing, loading, migration, or persistence. Both use a completion stage plus cancellation and state; their distinct outcome types preserve the different meanings of task termination versus world/entity retirement. The exact type hierarchy is pending review and should not multiply abstractions unnecessarily.

An outcome should distinguish at least:

- successful completion;
- cancellation before the operation committed;
- entity or region retirement;
- plugin or server shutdown;
- operational failure such as persistence failure;
- rejection before submission.

A completion callback must handle `CompletionException` or another wrapper used by the chosen future implementation. Examples should inspect the cause or use a documented outcome type rather than compare only the wrapper.

Cancellation is not the same as failure. A cancellation request is not proof that cancellation was accepted, that a callback stopped, that a resource was retired, or that a previously committed mutation was rolled back. The implementation must document the commit boundary, cooperation rules, deadlines, timeout policy, and the terminal representation for work that does not cooperate. Shutdown must reject new work and report accepted work without claiming forced termination of arbitrary callbacks.

## 10. Event subscriptions

Events should be scoped to the plugin and should not assume that every event has an entity owner:

```java
// PROPOSED API — NOT IMPLEMENTED
public interface EventRegistration<E extends VeltisEvent> extends AutoCloseable {
    Class<E> type();
    boolean isActive();
    @Override void close();
}

// PROPOSED API — NOT IMPLEMENTED
public interface Events {
    <E extends VeltisEvent> EventRegistration<E> subscribe(
        Class<E> type,
        DispatchContext context,
        Consumer<? super E> listener
    );
}
```

The event type or subscription policy must define whether `DispatchContext` may be `PUBLISHER`, `GLOBAL`, `REGION_OWNER`, `ENTITY_OWNER`, or `ASYNC_OBSERVER`. `ENTITY_OWNER` is valid only for events that carry a stable entity identity and define behavior when the entity is removed. A server lifecycle or configuration event must not be forced through an entity context.

Event payloads should be immutable. Listener exceptions should be isolated and diagnosed unless a specific event contract says otherwise. Registration is plugin-owned and is automatically retired during disable; explicit `close()` supports early removal.

The current event bus publishes synchronously on the publisher thread. The proposed API must not promise ordering or affinity beyond what each event type documents.

## 11. Minimal world and entity model

The first implementation should prove one safe world flow rather than freeze a complete world API. The model must preserve these distinctions:

| Operation | Meaning | Proposed result |
|---|---|---|
| Resolve world | Identify a configured world | `Optional<WorldRef>` or an explicit missing-world outcome; never a null-like hidden failure |
| Observe loaded chunk | Read only already-loaded state | `OperationHandle<Optional<ChunkSnapshot>>`; owner-routed, no creation, disk read, or generation |
| Create handle | Identify a coordinate or entity | Cheap stable handle; no loaded-state claim |
| Load/generate chunk | Establish loaded state | `OperationHandle<ChunkView>`; explicit mode and failure |
| Mutate block | Change live state | `OperationHandle<Void>` routed to the owning region |
| Entity action | Act on current owner | `OperationHandle<T>` with migration and retirement outcomes |
| Save/unload | Persist and retire state | Explicit operation with save policy and failure |

The following conceptual interfaces show absence and ownership explicitly:

```java
// PROPOSED API — NOT IMPLEMENTED
public interface WorldDirectory {
    Optional<WorldRef> byName(String name);
}

// PROPOSED API — NOT IMPLEMENTED
public interface WorldRef {
    OperationHandle<Optional<ChunkSnapshot>> observeLoadedChunk(ChunkPosition position);
    OperationHandle<ChunkView> loadChunk(ChunkPosition position, LoadMode mode);
    OperationHandle<Void> at(BlockPosition position,
        Consumer<RegionContext> operation);
    OperationHandle<Void> at(ChunkPosition position,
        Consumer<RegionContext> operation);
}
```

`byName` returns an empty optional when the world is absent. `observeLoadedChunk` completes with an empty optional when the chunk is unavailable under the observation policy. Neither operation loads, generates, blocks, or hides an operational failure. A failure during a requested operation is reported by its `OperationHandle`.

A snapshot is immutable and represents a documented point or interval of observation. It is not a live mutation capability and must not be used after its validity contract expires. Because a safe cross-thread publication mechanism for snapshots is not yet an approved Veltis guarantee, the MVP should prefer an owner-routed asynchronous read; a synchronous snapshot view is acceptable only after immutable publication and its visibility contract are proved. A world or chunk handle does not guarantee that the target remains loaded.

## 12. Corrected conceptual examples

Every example in this section is conceptual and not implemented.

### A. Region-owned task

```java
// PROPOSED API — NOT IMPLEMENTED
TaskHandle<Void> handle = context.scheduler().executeAt(regionKey, region -> {
    region.blocks().set(blockPosition, blockType);
});

handle.completion().whenComplete((outcome, failure) -> {
    if (failure != null) {
        context.logger().error("Region task failed", failure);
    } else {
        context.logger().debug("Region task outcome: " + outcome);
    }
});
```

This uses `executeAt(RegionKey, ...)`, not the entity-oriented `executeFor(EntityKey, ...)`. The region callback is the owner-safe mutation boundary.

### B. Plugin lifecycle with identity

```java
// PROPOSED API — NOT IMPLEMENTED
public final class WelcomePlugin implements VeltisPlugin {
    private PluginContext context;

    @Override
    public PluginInfo info() {
        return PluginInfo.of("welcome", "0.1.0");
    }

    @Override
    public void onEnable(PluginContext context) {
        this.context = context;
        context.logger().info("Enabled " + info().id());
    }

    @Override
    public void onDisable(DisableReason reason) {
        if (context != null) {
            context.logger().info("Disabled: " + reason);
        }
    }
}
```

The example implements the `info()` method required by `VeltisPlugin`.

### C. Owner-region block mutation

```java
// PROPOSED API — NOT IMPLEMENTED
Optional<WorldRef> world = context.worlds().byName("spawn");
if (world.isEmpty()) {
    context.logger().debug("World is unavailable");
} else {
    OperationHandle<Void> mutation = world.get().at(
        blockPosition,
        region -> region.blocks().set(blockPosition, blockType));
    mutation.completion().whenComplete((outcome, failure) -> {
        if (failure != null) {
            context.logger().error("Block mutation failed", failure);
        } else {
            context.logger().debug("Block mutation outcome: " + outcome);
        }
    });
}
```

The explicit `Optional` branch prevents a missing world from being hidden behind a null-like value. `world.at(...)` derives the owner region from the position and is equivalent in ownership semantics to `scheduler().executeAt(...)` when the caller already has a `RegionKey`.

### D. Entity operation across migration

```java
// PROPOSED API — NOT IMPLEMENTED
Optional<EntityRef> entity = context.entities().byId(entityId);
if (entity.isEmpty()) {
    context.logger().debug("Entity is unavailable");
} else {
    OperationHandle<Void> greeting = entity.get().execute(
        currentOwner -> currentOwner.sendMessage("Hello"));
    greeting.completion().whenComplete((outcome, failure) -> {
        if (failure != null) {
            context.logger().error("Entity operation failed", failure);
        } else {
            context.logger().debug("Entity operation outcome: " + outcome);
        }
    });
}
```

`entity.execute(...)` returns an `OperationHandle<Void>`. It resolves the entity's current owner when it runs, so migration is not an error by itself. If the entity is removed before the operation commits, the terminal outcome is `RETIRED` or the equivalent documented result. No callback assumes that the original region remains the owner. `EntityRef` is an identity-based, non-mutable reference; it is not a reference to a recycled live object.

### E. Loaded chunk observation without implicit loading

```java
// PROPOSED API — NOT IMPLEMENTED
Optional<WorldRef> world = context.worlds().byName("spawn");
if (world.isEmpty()) {
    context.logger().debug("World is unavailable");
} else {
    OperationHandle<Optional<ChunkSnapshot>> observation =
        world.get().observeLoadedChunk(chunkPosition);
    observation.completion().whenComplete((outcome, failure) -> {
        if (failure != null) {
            context.logger().error("Chunk observation raised an exception", failure);
        } else if (outcome == null) {
            context.logger().error("Chunk observation completed without an outcome",
                new IllegalStateException("Missing operation outcome"));
        } else if (outcome.isCancelled() || outcome.isRetired()
                || !outcome.isSuccess()) {
            context.logger().debug("Chunk observation did not succeed: " + outcome);
        } else if (outcome.value().isPresent()) {
            context.logger().debug("Observed chunk "
                + outcome.value().get().position());
        } else {
            context.logger().debug("Chunk is not loaded");
        }
    });
}
```

The MVP prefers an owner-routed asynchronous observation because the current runtime does not yet prove a public, thread-safe publication contract for synchronous snapshots. `observeLoadedChunk(...)` does not create a region, read disk, generate terrain, or expose live mutable state. It completes with `Optional.empty()` for an unloaded chunk. A snapshot is immutable and read-only.

### F. Asynchronous chunk load with controlled continuation

```java
// PROPOSED API — NOT IMPLEMENTED
Optional<WorldRef> world = context.worlds().byName("spawn");
if (world.isEmpty()) {
    context.logger().debug("World is unavailable");
} else {
    OperationHandle<ChunkView> load = world.get()
        .loadChunk(chunkPosition, LoadMode.READ_OR_GENERATE);

    load.completion().whenComplete((outcome, failure) -> {
        if (failure != null) {
            context.logger().error("Chunk load raised an exception", failure);
            return;
        }
        if (outcome == null) {
            context.logger().error("Chunk load completed without an outcome",
                new IllegalStateException("Missing operation outcome"));
            return;
        }
        if (outcome.isCancelled()) {
            context.logger().debug("Chunk load was cancelled");
            return;
        }
        if (outcome.isRetired()) {
            context.logger().debug("Chunk load ended because its target retired");
            return;
        }
        if (!outcome.isSuccess()) {
            context.logger().debug("Chunk load failed: " + outcome);
            return;
        }

        ChunkView view = outcome.value();
        world.get().at(chunkPosition, region -> region.inspect(view))
            .completion().whenComplete((inspection, inspectionFailure) -> {
                if (inspectionFailure != null) {
                    context.logger().error("Region inspection raised an exception",
                        inspectionFailure);
                } else if (inspection == null) {
                    context.logger().error("Region inspection completed without an outcome",
                        new IllegalStateException("Missing operation outcome"));
                } else if (inspection.isCancelled()) {
                    context.logger().debug("Region inspection was cancelled");
                } else if (inspection.isRetired()) {
                    context.logger().debug("Region inspection target retired");
                } else if (!inspection.isSuccess()) {
                    context.logger().debug("Region inspection failed: " + inspection);
                }
            });
    });
}
```

The completion callback does not use `thenAcceptAsync` with an implicit common pool. It routes through `WorldRef.at(ChunkPosition, ...)`, so the current region owner is resolved from the immutable world and chunk position when the continuation is submitted. The load completion and the later inspection completion are separate outcomes; observing the first does not imply that the mutation or inspection succeeded. Between them, the chunk may unload or become unavailable. The API must not promise residency across the gap unless a separate retention/pin contract is approved. The `ChunkView` is treated as a boundary-safe value; it is not assumed to be a live mutable chunk.

### G. Event subscription without universal entity affinity

```java
// PROPOSED API — NOT IMPLEMENTED
EventRegistration<ServerStarted> registration = context.events().subscribe(
    ServerStarted.class,
    DispatchContext.GLOBAL,
    event -> context.logger().info("Server started")
);

context.lifecycle().onDisable(registration::close);
```

A global lifecycle event uses `GLOBAL`, not `ENTITY_OWNER`. An entity event may use `ENTITY_OWNER` only when its contract supplies a stable entity identity and defines removal behavior.

### H. Cancellation, completion, and shutdown

```java
// PROPOSED API — NOT IMPLEMENTED
TaskHandle<Void> operation = context.scheduler()
    .executeAsync(async -> async.performCpuWork());

operation.completion().whenComplete((outcome, failure) -> {
    Throwable cause = failure instanceof CompletionException
        && failure.getCause() != null ? failure.getCause() : failure;

    if (cause instanceof ServerStoppingException
        || cause instanceof PluginDisabledException) {
        context.logger().debug("Operation received shutdown retirement");
    } else if (cause != null) {
        context.logger().error("Operation failed", cause);
    } else if (outcome == null) {
        context.logger().error("Operation completed without an outcome",
            new IllegalStateException("Missing task outcome"));
    } else if (outcome.isCancelled()) {
        context.logger().debug("Operation cancellation was accepted before commit");
    } else {
        context.logger().debug("Operation completed: " + outcome);
    }
});
```

The conceptual outcome distinguishes an exception, cancellation requested or accepted, shutdown retirement, and operational failure. A cancellation request does not prove that a callback stopped, that a resource retired, or that a mutation was rolled back. If cancellation is accepted after a mutation has committed, the operation must not claim rollback unless rollback is explicitly part of the contract; deadlines and non-cooperation policy remain implementation decisions.

## 13. MVP scope and later decisions

The first implementation should prove one useful end-to-end path rather than freeze every possible API.

| Stage | MVP responsibility | Runtime dependencies | `veltis-api` changes | Acceptance criteria | Proposed tests | Main risk | Deferred scope |
|---|---|---|---|---|---|---|---|
| 1. Identity/lifecycle | Load one native plugin and enable/disable it | Loader and lifecycle state machine | Plugin identity, metadata, lifecycle callbacks, scope ownership | Identity, ordering, failure, and cleanup are observable | lifecycle ordering, enable failure, idempotent disable | lifecycle ordering becomes hard to change | packaging ecosystem |
| 2. Execution contexts | Represent global, region, entity, and async boundaries | Region owner lookup and async executor boundaries | Context capability types and diagnostics | Callbacks can verify their context | owner assertions, wrong-context diagnostics | exposing unstable runtime details | arbitrary context hopping |
| 3. Plugin scheduler | Scoped global, async, and region task submission | Region scheduler, lifecycle registry, admission policy | `PluginScheduler`, `TaskHandle`, outcomes, cancellation state | Completion, rejection, cancellation, and shutdown are observable | task cancellation, shutdown, queue limits, fairness | quota policy may reject useful bursts | full job graph, repeating complexity |
| 4. Minimal event flow | Register one documented event and remove it | Event bus affinity and listener failure policy | Registration handle, dispatch context, immutable event contract | Registration cleanup and dispatch context are deterministic | listener removal, exception isolation, ordering | reentrancy and ordering become public behavior | complete event catalogue |
| 5. One world operation | Execute one owner-routed operation safely | Region routing, immutable boundary value, operation state | `WorldRef`, `OperationHandle`, missing/failure outcomes | No foreign mutation or hidden blocking | correct owner, missing world, failure, cancellation | incomplete internal world adapter | complete chunk/entity API |
| 6. Demo plugin | Use stages 1–5 end to end | All MVP stages plus test fixture | No additional public surface unless required by the vertical slice | Useful plugin works without internal access | smoke/conformance run | demo can accidentally define premature contracts | compatibility adapters |

World loading, generation, persistence, teleportation, broad entity queries, and a full event taxonomy remain later decisions. They should be added only after ownership and completion contracts are approved.

## 14. Reliability, limits, and diagnostics

The proposed foundation should eventually provide:

- ownership checks before live mutation;
- immutable snapshots and event payloads;
- no live mutable collections from world/entity containers;
- queue limits or documented backpressure;
- explicit deadline and timeout policy;
- rejection after plugin disable and server shutdown;
- automatic cleanup of plugin-owned resources;
- exception isolation for event listeners;
- structured diagnostics containing plugin, operation, expected context, actual context, owner, and lifecycle state;
- API versioning independent of internal class names;
- no exposure of NMS, worker queues, region inboxes, pooled mutable objects, or state-machine internals.

These are proposed requirements, not claims about the current runtime. They become guarantees only after implementation and conformance tests establish them.

## 15. Conformance test strategy

The first tests should be deterministic and contract-focused:

- lifecycle ordering, enable failure, disable cleanup, and idempotence;
- plugin task scope, rejection after disable, cancellation, and shutdown;
- correct global, region, entity, and async context reporting;
- region routing without foreign-region mutation;
- entity migration and removal outcomes;
- event registration cleanup, documented affinity, immutable payloads, and listener exception isolation;
- loaded observation without loading or generation;
- explicit operation failure, cancellation, completion, and commit-boundary behavior;
- diagnostic content for wrong-context access;
- bounded submission and backpressure behavior.

Concurrency stress tests should follow deterministic contract tests. This RFC adds no test files and does not claim that the proposal has been compiled.

## 16. Competitive evidence and design opportunities

This section compares documented behavior rather than making performance claims. Folia and Minestom already solve several problems that a multithreaded API must address. Veltis should learn from those designs and compete on the clarity, ownership boundaries, lifecycle guarantees, and testability of its native contracts—not by claiming that familiar scheduler categories are unique.

### 16.1 Evidence matrix

| Platform | Documented strength | Trade-off or developer cost | Veltis design lesson | Classification |
|---|---|---|---|---|
| Bukkit/Spigot scheduler | Clear synchronous versus asynchronous task entry points, plugin task cancellation, and task inspection | Async tasks must not access Bukkit APIs; the developer must manually marshal work back to the server thread; the traditional model assumes one main server thread | Keep the explicit separation, but replace a universal main-thread assumption with owner-aware contexts and typed re-entry | DOCUMENTED TRADE-OFF |
| Paper/Folia | Global, region, async, and entity schedulers; entity scheduling follows an entity across regions | The correct scheduler must be selected for each operation; declaring Folia support does not make a plugin compatible; region and entity work are distinct | Retain context-specific routing, but make illegal context use diagnosable and resources lifecycle-scoped | DOCUMENTED TRADE-OFF |
| Folia | Region-local ownership, per-region scheduling, global-region state, and location/entity routing | Developers must reason about region ownership, migration, deadlines, and non-global tick state | Treat ownership and migration as first-class contract concepts, with explicit terminal outcomes and deterministic conformance tests | DESIGN OPPORTUNITY |
| Minestom | `Acquirable<T>` makes uncertain ownership visible and offers `sync`, `trySync`, `local`, and manual `lock` paths; `unwrap()` is explicitly unsafe; its event nodes support typed filtering, priorities, expiry, cancellation, and removable listeners | Safe acquisition can block or add scheduling overhead; ownership assertions and strict modes can reject incorrect access; scheduler behavior differs between tick, end-of-tick, shutdown, and caller-thread submission paths | Prefer safe owner-routed operations by default, keep escape hatches explicit, and specify submission versus execution semantics instead of relying on scheduler names | DESIGN OPPORTUNITY |
| SpongeAPI 12 | Explicit sync/async schedulers, plugin-associated listener registrations, and documented cancellation of plugin tasks on disable | Cleanup remains a responsibility that plugin authors must perform through scheduler/event APIs | Make plugin resource ownership automatic while retaining explicit early cancellation and unregistration | DESIGN OPPORTUNITY |

Sources: BukkitScheduler Javadocs and Paper scheduler documentation (retrieved 2026-10-11); Paper/Folia support documentation last updated September 18, 2026; Folia overview last updated April 19, 2025; Minestom Acquirable API, scheduler, and Events documentation (moving `master` documentation and current Javadocs; exact version/commit must be pinned before implementation); SpongeAPI 12 autodocs.

Minestom's scheduler documentation also illustrates why scheduler names must not be treated as universal semantics: tick scheduling, end-of-tick observation, shutdown tasks, and caller-thread submission details differ by API and version. Veltis should specify execution and submission semantics in its own contract rather than infer them from a scheduler label.

The following distinctions are important:

- The four scheduler categories are not a Veltis invention; Folia already documents the same broad separation.
- Folia already addresses entity migration through an entity scheduler. Veltis should not present migration-aware routing alone as a unique differentiator.
- Minestom already exposes an explicit safety spectrum for acquired objects, cancellable scheduler tasks, and lifecycle-oriented event/chunk hooks. Veltis should learn from those models while choosing whether its public default should favor nonblocking owner-routed operations over blocking acquisition.
- Traditional Bukkit/Paper synchronous APIs and Folia are different targets. A problem caused by a universal main-thread assumption must not be attributed to Folia after Folia has introduced region and entity schedulers.
- The Minestom documentation and Javadocs are moving/current-version material. Exact release or commit pinning is required before using a signature or behavior as an implementation dependency.

### 16.2 Three defensible Veltis opportunities

#### Opportunity A — Lifecycle-scoped resource ownership

**Developer pain:** A plugin may need one coherent view of tasks, listeners, chained world operations, and explicitly registered external resources while it is being disabled. Independent cleanup mechanisms make ordering, terminal state, and cancellation cooperation easy to misunderstand.

**Existing approaches:** Bukkit's `SimplePluginManager` cancels the plugin's scheduler tasks and unregisters its event handlers during disable. Sponge documents cancellation of synchronous and asynchronous tasks on plugin disable, and Paper lifecycle registrations carry phase and plugin context. These are real cleanup capabilities; this RFC does not claim that Bukkit leaves its tasks or listeners registered after disable.

**Proposed Veltis experience:** A Veltis plugin scope would unify scheduler tasks, event registrations, chained asynchronous/world operations, and external resources explicitly registered with that scope. Disable would reject new operations, request cooperative cancellation, retire registrations/resources according to a documented order, and expose terminal results. This is a proposed integration contract, not a claim about the current runtime or API. Explicit handles would remain available for early cleanup.

**Contract sketch:**

```java
// PROPOSED API — NOT IMPLEMENTED
public interface PluginScope {
    ScopeState state();
    CompletionStage<ScopeCloseResult> close();
}
```

**Trade-off:** Automatic cleanup is safer, but it can surprise plugins that expect work to survive disable. The contract must make ownership visible and define close ordering.

**Feasibility:** High for scheduler and event resources; world operations require runtime cancellation and terminal-state integration.

**Acceptance criteria:** After disable begins, new plugin submissions are rejected, registered listeners no longer receive events, and every accepted resource reaches a documented terminal outcome.

**Conformance tests:** Disable during queued work, disable during a running callback, listener cleanup, repeated disable, and shutdown racing with plugin disable.

#### Opportunity B — Owner-routed composition without hidden blocking

**Developer pain:** A developer often receives a result on an asynchronous completion thread and then accidentally touches world state from the wrong context. Blocking to recover the expected context can deadlock or add invisible latency.

**Existing approaches:** Paper/Folia provide region and entity schedulers, and Minestom provides acquisition APIs with blocking, nonblocking, local-only, and explicitly unsafe paths. Those designs establish the importance of routing, but each still requires the developer to understand when a result is safe to use and what submission thread versus execution thread means.

**Proposed Veltis experience:** World operations return immutable results or operation handles. A completion callback must explicitly re-enter the owner through a world/position or entity identity route. No completion thread is treated as a world owner by convention.

**Contract sketch:**

```java
// PROPOSED API — NOT IMPLEMENTED
OperationHandle<Void> at(ChunkPosition position,
    Consumer<RegionContext> operation);
```

**Trade-off:** Explicit routing adds a line of code and makes ownership visible. In return, the API avoids hidden waits and gives diagnostics a precise operation and owner to report.

**Feasibility:** High for location-based routing because the current Veltis runtime already routes region jobs through region ownership; public immutable result boundaries and cancellation still require runtime work.

**Acceptance criteria:** A completion callback can safely submit a follow-up operation without using the completion thread as an owner; the owner is resolved from the target identity at submission/execution time.

**Conformance tests:** Completion on an arbitrary executor, owner migration before continuation submission, wrong-context mutation, no blocking cross-region waits, and follow-up failure propagation.

#### Opportunity C — Bounded plugin scheduling with observable outcomes

**Developer pain:** A plugin that submits unbounded work can consume queues, delay other plugins, or create a shutdown tail that is difficult to diagnose.

**Existing approaches:** Bukkit and Paper expose task handles and cancellation; Folia schedules regions by deadline; Minestom distinguishes safe acquisition modes and documents tick/end-of-tick/shutdown scheduling distinctions. These are valuable primitives, but a native API still needs a plugin-level admission and fairness policy.

**Proposed Veltis experience:** Plugin-scoped schedulers expose admission limits, rejection reasons, cancellation state, and queue metrics. The API does not promise that every submitted task will run; it makes rejection and retirement observable.

**Trade-off:** Limits can reject legitimate bursts and require configuration. Without limits, one plugin's behavior can become an operational problem for the whole server.

**Feasibility:** Medium. The current runtime exposes pending-job and worker metrics, but per-plugin accounting, admission control, and fairness are not yet public contracts.

**Acceptance criteria:** A configured plugin cannot exceed its admission policy indefinitely; rejection identifies the plugin and limit; other plugins continue to make progress under the test load.

**Conformance tests:** Burst submission, weighted or quota fairness, cancellation of queued work, shutdown under backlog, and isolation between two plugins.

### 16.3 Developer journeys

The following journeys illustrate intended ergonomics. Veltis snippets are conceptual and marked accordingly; platform examples describe documented patterns rather than compatibility targets.

#### A. Simple plugin with an event

A minimal plugin should only need identity, lifecycle, and one event registration. The commands API is not part of this MVP and is omitted here. Event delivery before registration is not implied: whether `ServerStarted` has already fired depends on an initialization order that this RFC does not define. The lifecycle and resource ownership contract is the point of the example.

```java
// PROPOSED API — NOT IMPLEMENTED
public final class WelcomePlugin implements VeltisPlugin {
    @Override
    public PluginInfo info() {
        return PluginInfo.of("welcome", "0.1.0");
    }

    @Override
    public void onEnable(PluginContext context) {
        context.events().subscribe(
            ServerStarted.class,
            DispatchContext.GLOBAL,
            event -> context.logger().info("Observed server start"));
        // Command registration is intentionally omitted from this MVP example.
    }

    @Override
    public void onDisable(DisableReason reason) {
        // Plugin scope retires registrations automatically.
    }
}
```

#### B. Modify blocks from an async context

Async work may calculate data or perform I/O, but it must re-enter a region context before mutation. The async callback cannot access live world state directly.

```java
// PROPOSED API — NOT IMPLEMENTED
TaskHandle<Void> task = context.scheduler().executeAsync(async -> {
    BlockType replacement = loadReplacementFromStorage();
    Optional<WorldRef> world = context.worlds().byName("spawn");
    if (world.isEmpty()) {
        throw new WorldUnavailableException("spawn");
    }

    OperationHandle<Void> mutation = world.get().at(blockPosition,
        region -> region.blocks().set(blockPosition, replacement));
    mutation.completion().whenComplete((outcome, failure) -> {
        if (failure != null) {
            context.logger().error("Region mutation raised an exception", failure);
        } else if (outcome == null) {
            context.logger().error("Region mutation completed without an outcome",
                new IllegalStateException("Missing operation outcome"));
        } else if (outcome.isCancelled()) {
            context.logger().debug("Region mutation was cancelled");
        } else if (outcome.isRetired()) {
            context.logger().debug("Region mutation target retired");
        } else if (!outcome.isSuccess()) {
            context.logger().debug("Region mutation failed: " + outcome);
        }
    });
});
task.completion().whenComplete((taskOutcome, taskFailure) -> {
    if (taskFailure != null) {
        context.logger().error("Async calculation or submission failed", taskFailure);
    } else if (taskOutcome == null) {
        context.logger().error("Async task completed without an outcome",
            new IllegalStateException("Missing task outcome"));
    } else {
        context.logger().debug("Async task outcome: " + taskOutcome);
    }
});
```

The async task's completion reports only calculation/submission failure; it is not the region mutation result. The mutation has its own `OperationHandle<Void>` and completion path, observed independently below. These are two independent operations, not one structured composition. If Veltis later adds a composition primitive that propagates both outcomes and cancellation, that would be a future design opportunity rather than an approved MVP feature. `WorldUnavailableException` and the exact operation outcome types are conceptual. The example intentionally leaves the missing-world policy visible; a production contract must define how operation ownership is linked to the plugin scope.

#### C. Load a chunk and mutate it safely

The load completion thread is not assumed to own the chunk. The follow-up is routed by world and position.

```java
// PROPOSED API — NOT IMPLEMENTED
Optional<WorldRef> world = context.worlds().byName("spawn");
if (world.isPresent()) {
    OperationHandle<ChunkView> load = world.get()
        .loadChunk(chunkPosition, LoadMode.READ_OR_GENERATE);
    load.completion().whenComplete((outcome, failure) -> {
        if (failure != null) {
            context.logger().error("Chunk load raised an exception", failure);
            return;
        }
        if (outcome == null) {
            context.logger().error("Chunk load completed without an outcome",
                new IllegalStateException("Missing operation outcome"));
            return;
        }
        if (outcome.isCancelled() || outcome.isRetired()
                || !outcome.isSuccess()) {
            context.logger().debug("Chunk load did not reach success: " + outcome);
            return;
        }
        // This is a new operation. The chunk may unload before it runs.
        world.get().at(chunkPosition,
            region -> region.blocks().set(blockPosition, blockType))
            .completion().whenComplete((mutation, mutationFailure) -> {
                if (mutationFailure != null) {
                    context.logger().error("Mutation raised an exception",
                        mutationFailure);
                } else if (mutation == null) {
                    context.logger().error("Mutation completed without an outcome",
                        new IllegalStateException("Missing operation outcome"));
                } else if (mutation.isCancelled() || mutation.isRetired()
                        || !mutation.isSuccess()) {
                    context.logger().debug("Mutation did not reach success: " + mutation);
                }
            });
    });
}
```

The complete outcome distinctions and non-null diagnostic handling are shown in section 12.F and are intentionally not duplicated here. A successful load does not guarantee continuous chunk residency until the mutation. The example is not a claim that the current Veltis API compiles this code; it demonstrates the required routing and independent failure boundaries.

#### D. Entity migration

An entity reference identifies an entity; it does not capture its current worker. The operation resolves the owner when it executes and returns a retirement outcome if the entity disappears first.

#### E. Disable with pending resources

Disable should stop new submissions, retire plugin resources, and make completion observable. A plugin should not need to maintain a separate list of every task and listener merely to avoid leaks.

#### F. Illegal cross-region access

A direct live-state access from the wrong context should fail with a structured diagnostic containing plugin identity, operation, expected context, actual context, target owner, and lifecycle state. The RFC proposes this behavior; the current runtime does not yet expose this public diagnostic contract.

#### G. Excessive task submission

The scheduler should reject or defer work once a plugin reaches its configured admission policy. The rejection must be observable through a typed outcome or documented exception, and metrics should identify queue depth and plugin scope without exposing internal queues.

#### H. Multi-region operation

A multi-region operation must not imply atomicity unless the runtime provides a transaction protocol. The default composition should report per-region outcomes, timeout, cancellation, and partial completion explicitly. It must not block one region while waiting for another.

## 17. Concurrency and lifecycle contract boundaries

Each statement in this section is classified to prevent proposed behavior from being mistaken for current support.

| Topic | Confirmed current Veltis implementation | Proposed public API contract | Requires runtime change | Maintainer decision required |
|---|---|---|---|---|
| Region ownership | `Region` documents one worker owner; cross-region work uses inbox/message routing | Public callbacks run only in their declared owner context | Public capability wrappers and diagnostics | Whether owner identity is exposed directly |
| Region inboxes | Current inbox is MPSC; producers do not block while posting | Plugins submit commands, never inbox objects | Admission and plugin attribution | Queue and fairness policy |
| Entity migration | Runtime has entity/region machinery, but stable public identity semantics are not approved | Entity handles resolve current owner and return retirement outcomes | Identity generation and migration-safe routing | Retired versus exceptional outcome |
| Snapshot publication | Internal snapshot/value types exist, but public cross-thread publication is not established | Only immutable, documented snapshots cross contexts | Publication/freshness guarantees | Snapshot lifetime and consistency |
| Lifecycle | Lifecycle manager validates transitions and isolates participant failures | Plugin scope rejects new work and retires owned resources on disable | Plugin resource registry and cancellation integration | Ordering between callbacks and resource retirement |
| Events | Event bus dispatches synchronously on publisher thread; listener failures can be isolated or propagated | Event-specific dispatch context, ordering, and cleanup are documented | Public registration scope and affinity routing | Cancellation and reentrancy policy |
| Scheduling | Region scheduler supports global/region jobs, handles, shutdown, and metrics | Typed task handles, admission limits, cancellation, and diagnostics | Per-plugin accounting and public outcomes | Fairness and quota defaults |
| Shutdown | World engine stops worlds and scheduler; current futures do not define all public terminal states | Accepted work reaches observable terminal outcomes | Cross-service cancellation propagation | Failure versus cancellation representation |
| Multi-region operations | No public transaction protocol is established | Composition reports partial completion unless atomicity is explicitly provided | Correlation, timeout, and result aggregation | Whether any atomic operation is needed in MVP |

## 18. Performance evaluation plan

No performance result is claimed by this RFC. Any comparison must use reproducible versions, hardware, configuration, workload, and warm-up rules.

The future evaluation should separate:

1. **Micro-API measurements:** submission overhead, owner-check cost, handle allocation, cancellation latency, and snapshot publication cost.
2. **Conformance tests:** correctness under migration, cancellation, shutdown, wrong-context access, and partial failure.
3. **Integration scenarios:** independent regions, cross-region routing, plugin disable under load, and event bursts.
4. **End-to-end benchmarks:** server tick time, p95/p99 task latency, queue depth, memory allocation, and behavior with intentionally abusive plugins.

Measurements should include:

- tick time and wall-clock scheduling separately;
- p95 and p99 latency, not only averages;
- queue contention and worker utilization;
- scaling as independent region count increases;
- cancellation and shutdown tail latency;
- ownership-check overhead;
- allocation and memory pressure;
- plugin isolation under burst submission.

Comparisons across Bukkit, Paper, Folia, Minestom, and Veltis must acknowledge different world engines, schedulers, workloads, and API assumptions. A benchmark can demonstrate a particular scenario; it cannot establish universal superiority.

## 19. Trade-offs and rejected alternatives

### Bukkit-shaped compatibility

Rejected by project direction. It would import assumptions about legacy lifecycle, packaging, and a universal main thread before Veltis defines its own ownership model.

### Unrestricted shared mutable state

Rejected because it transfers synchronization responsibility to every plugin and makes correctness depend on undocumented implementation details.

### Blocking synchronous facade

Rejected as the default because it can deadlock region workers and hide latency. A narrow owner-local read may be considered later only with a formal safety contract.

### Giant initial API

Rejected because speculative interfaces would freeze names and semantics before the runtime contracts are validated. The MVP should prove one complete path first.

## 20. Decisions for Frontman and WatermanMC

| Decision | Options | Proposed direction | Main risk | Impact |
|---|---|---|---|---|
| Execution context | implicit thread, explicit context, callback-only | explicit context and owner callbacks | visible ceremony | all stages |
| Entity migration | captured thread, current-owner routing, global serialization | current-owner routing | retired outcome must be defined | entity operations |
| Cross-region work | blocking, routed command, composed stage | routed command/composed stage | more asynchronous composition | world operations |
| Completion model | `CompletionStage`, custom handles, both | one completion model with small cancellable handles where needed | type complexity | scheduler/world |
| Plugin cancellation | caller-managed, automatic scope, mixed | automatic plugin scope plus explicit handles | work retires during disable | lifecycle |
| Event dispatch | publisher thread, owner context, async only | event-specific documented context | event authors must classify events | events |
| Ownership diagnostics | silent race, exception, structured diagnostic | structured diagnostic plus predictable failure | diagnostic cost | reliability |
| MVP scope | full API, scheduler-first, world-first | lifecycle → contexts → scheduler → event → one world flow → demo | delayed breadth | delivery |
| API versioning | internal names, semantic version, none | explicit public API version | compatibility policy needed | releases |
| Internal promotion | expose runtime types, wrappers, snapshots/commands | wrappers over immutable data and owner commands | adapter effort | public safety |

The compatibility decision is **not open**: project direction explicitly rejects Bukkit, Spigot, and Paper compatibility.

## 21. Open questions

1. What is the smallest loader contract for one native plugin without freezing packaging details?
2. Should contexts be capability objects, callback types, or both?
3. Should `TaskHandle` and `OperationHandle` share one public abstraction or remain separate?
4. What entity identity and retired outcome survive migration and despawn?
5. Which event types may use `ENTITY_OWNER`, and which are global or publisher-affine?
6. What queue limits, backpressure, deadlines, and timeout defaults protect the server?
7. What is the commit boundary for cancellation-sensitive mutations?
8. Which immutable snapshot is sufficient for the demonstration plugin?
9. What public API versioning and deprecation policy begins with the first release?
10. Which internal contracts may be promoted after conformance tests pass?

## 22. Suggested implementation milestones

1. **Contract decision:** maintainers choose lifecycle ordering, context vocabulary, outcome states, admission limits, and the first world operation. No runtime implementation is implied by approval of this RFC.
2. **Vertical-slice test fixture:** add contract tests and a minimal native plugin fixture before broad API expansion. Dependencies: lifecycle test harness and deterministic region scheduler fixture. Risk: tests accidentally encode undocumented internals.
3. **Identity and scope:** implement one native plugin loading path and a plugin-owned resource scope. Dependencies: loader/lifecycle state machine. Acceptance: repeated disable is idempotent and owned resources are retired.
4. **Context and scheduler:** add context-aware scheduler views and cancellable task outcomes. Dependencies: region owner routing, async executor, admission accounting. Acceptance: no foreign mutation, observable rejection, and bounded shutdown tail.
5. **Event vertical slice:** add one event registration with explicit dispatch context, immutable payload, ordering, and automatic cleanup. Dependencies: event bus integration. Acceptance: listener failures are isolated and registration removal is deterministic.
6. **World vertical slice:** add one owner-routed observation or mutation with missing-target, cancellation, and failure outcomes. Dependencies: approved world adapter bridge and immutable boundary value. Acceptance: no hidden load, generation, or cross-region blocking.
7. **Adversarial validation:** run deterministic conformance tests, then stress migration, overload, shutdown races, and multi-region partial failure. Do not treat stress output as a benchmark claim without a documented methodology.
8. **Scope review:** review chunk loading, generation, persistence, entity queries, teleportation, and broader event taxonomy separately. Defer any surface whose ownership or completion semantics remain unsettled.
9. **Versioned publication:** version and document only the stable subset after maintainers approve the contracts and tests.

## 23. Source references

### Veltis

- `veltis-api/src/main/java/org/veltismc/api/Server.java`
- `veltis-api/src/main/java/org/veltismc/api/entity/Player.java`
- `veltis-api/src/main/java/org/veltismc/api/command/CommandSender.java`
- `server/src/main/java/org/veltismc/runtime/ServerRuntime.java`
- `server/src/main/java/org/veltismc/runtime/DefaultServerRuntime.java`
- `server/src/main/java/org/veltismc/runtime/event/EventBus.java`
- `server/src/main/java/org/veltismc/runtime/event/SimpleEventBus.java`
- `server/src/main/java/org/veltismc/runtime/scheduler/TaskScheduler.java`
- `server/src/main/java/org/veltismc/runtime/scheduler/TaskHandle.java`
- `server/src/main/java/org/veltismc/world/api/RegionScheduler.java`
- `server/src/main/java/org/veltismc/world/api/World.java`
- `server/src/main/java/org/veltismc/world/api/ChunkHandle.java`
- `server/src/main/java/org/veltismc/world/api/EntityHandle.java`
- `server/src/main/java/org/veltismc/world/core/WorldImpl.java`
- `server/src/main/java/org/veltismc/world/region/RegionImpl.java`
- `server/src/main/java/org/veltismc/world/scheduler/RegionSchedulerImpl.java`
- `server/src/main/java/org/veltismc/world/chunk/ChunkHandleImpl.java`

### External comparison references

- [Folia README](https://github.com/PaperMC/Folia/blob/ver/26.2.x/README.md)
- [Paper BukkitScheduler Javadoc](https://jd.papermc.io/paper/1.21.8/org/bukkit/scheduler/BukkitScheduler.html)
- [Folia RegionScheduler Javadoc](https://jd.papermc.io/folia/1.21.11/io/papermc/paper/threadedregions/scheduler/RegionScheduler.html)
- [Folia EntityScheduler Javadoc](https://jd.papermc.io/folia/1.21.11/io/papermc/paper/threadedregions/scheduler/EntityScheduler.html)
- [Paper scheduler guide](https://docs.papermc.io/paper/dev/scheduler/)
- [Paper 1.21.8 RegionScheduler Javadoc](https://jd.papermc.io/paper/1.21.8/io/papermc/paper/threadedregions/scheduler/RegionScheduler.html)
- [Paper 1.21.8 EntityScheduler Javadoc](https://jd.papermc.io/paper/1.21.8/io/papermc/paper/threadedregions/scheduler/EntityScheduler.html)
- [Paper 1.21.8 GlobalRegionScheduler Javadoc](https://jd.papermc.io/paper/1.21.8/io/papermc/paper/threadedregions/scheduler/GlobalRegionScheduler.html)
- [Paper 1.21.8 AsyncScheduler Javadoc](https://jd.papermc.io/paper/1.21.8/io/papermc/paper/threadedregions/scheduler/AsyncScheduler.html)
- [Paper 1.21.8 ScheduledTask Javadoc](https://jd.papermc.io/paper/1.21.8/io/papermc/paper/threadedregions/scheduler/ScheduledTask.html)
- [Paper Folia support guide](https://docs.papermc.io/paper/dev/folia-support/)
- [Folia reference overview](https://docs.papermc.io/folia/reference/overview/)
- [Paper lifecycle guide](https://docs.papermc.io/paper/dev/lifecycle/)
- [BukkitScheduler Javadoc](https://hub.spigotmc.org/javadocs/bukkit/org/bukkit/scheduler/BukkitScheduler.html)
- [Bukkit Plugin Javadoc](https://hub.spigotmc.org/javadocs/bukkit/org/bukkit/plugin/Plugin.html)
- [Minestom Acquirable API](https://minestom.net/docs/thread-architecture/acquirable-api)
- [Minestom scheduler documentation](https://github.com/minestom/minestom.net/blob/master/docs/feature/schedulers.md)
- [Minestom Events](https://minestom.net/docs/feature/events)
- [Minestom Acquirable Javadoc](https://javadoc.minestom.net/net.minestom.server/net/minestom/server/thread/Acquirable.html)
- [Minestom Scheduler Javadoc](https://javadoc.minestom.net/net.minestom.server/net/minestom/server/timer/Scheduler.html)
- [Minestom Instance Javadoc](https://javadoc.minestom.net/net.minestom.server/net/minestom/server/instance/Instance.html)
- [Minestom ChunkLoader Javadoc](https://javadoc.minestom.net/net.minestom.server/net/minestom/server/instance/ChunkLoader.html)
- [SpongeAPI 12 scheduler autodoc](https://github.com/spongepowered/sponge/blob/api-12/_autodocs/scheduler.md)
- [SpongeAPI 12 core API autodoc](https://github.com/spongepowered/sponge/blob/api-12/_autodocs/core-api.md)
- [SpongeAPI 12 event-system autodoc](https://github.com/spongepowered/sponge/blob/api-12/_autodocs/event-system.md)

## 24. Review status

**DRAFT — MAINTAINER REVIEW REQUIRED**

This RFC changes no runtime behavior and does not authorize implementation. It is ready for discussion by Frontman and WatermanMC before production API work begins.
