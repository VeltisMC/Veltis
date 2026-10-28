package org.veltismc.redstone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Differential tests for region discovery. {@link ParallelRedstone#computeRegions} is
 * compared against an independently written reference - global component labeling over the
 * whole world plus the pairwise merge rules of the specification - on targeted topologies
 * and on randomized worlds with a fixed seed.
 *
 * <p>The reference deliberately shares no code and no data structures with production: it
 * labels every propagator component with a BFS over all positions, then unions pairs by
 * the spec's relations (pending chunks within Chebyshev 2, a component with any pending
 * chunk whose 2-chunk window reaches one of its positions, two components whose position
 * columns are within Chebyshev 2). Production instead floods components on demand from
 * per-pending windows; the two must always agree, including on the two fail-closed cases
 * (a window chunk that is not loaded, a component that reaches an unloaded chunk or the
 * 131072-position cap).
 */
class ParallelRedstoneRegionFinderTest {

    private static boolean bootstrapped;

    @BeforeAll
    static void bootstrapMinecraft() {
        // BlockPos/ChunkPos math needs no registries, but bootstrap keeps class
        // initialization of transitively loaded Minecraft classes side-effect free.
        if (bootstrapped) {
            return;
        }
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        bootstrapped = true;
    }

    // ------------------------------------------------------------- fixture

    /** A synthetic world: per-chunk propagator positions plus the unloaded chunks. */
    private static final class World {
        private static final LongOpenHashSet EMPTY = new LongOpenHashSet();

        final Set<Long> unloaded = new HashSet<>();
        final Map<Long, LongOpenHashSet> positionsByChunk = new HashMap<>();
        final Set<Long> allPositions = new HashSet<>();

        World put(int x, int y, int z) {
            long position = BlockPos.asLong(x, y, z);
            if (allPositions.add(position)) {
                positionsByChunk
                        .computeIfAbsent(ChunkPos.pack(x >> 4, z >> 4), key -> new LongOpenHashSet())
                        .add(position);
            }
            return this;
        }

        /** A horizontal line of positions from x0 to x1 inclusive. */
        World line(int x0, int x1, int y, int z) {
            for (int x = x0; x <= x1; x++) {
                put(x, y, z);
            }
            return this;
        }

        World unload(int chunkX, int chunkZ) {
            unloaded.add(ChunkPos.pack(chunkX, chunkZ));
            return this;
        }

        ParallelRedstone.PropagatorSource source() {
            return (chunkX, chunkZ) -> {
                long key = ChunkPos.pack(chunkX, chunkZ);
                if (unloaded.contains(key)) {
                    return null;
                }
                LongOpenHashSet set = positionsByChunk.get(key);
                return set == null ? EMPTY : set;
            };
        }
    }

    private static LongSet pending(long... chunkKeys) {
        LongSet pending = new LongOpenHashSet();
        for (long key : chunkKeys) {
            pending.add(key);
        }
        return pending;
    }

    private static long chunk(int x, int z) {
        return ChunkPos.pack(x, z);
    }

    /** Groups the returned chunk-key -> region-id map into a partition of chunk keys. */
    private static Set<Set<Long>> partitionOf(Long2IntMap regions) {
        Map<Integer, Set<Long>> groups = new HashMap<>();
        for (Long2IntMap.Entry entry : regions.long2IntEntrySet()) {
            groups.computeIfAbsent(entry.getIntValue(), key -> new HashSet<>()).add(entry.getLongKey());
        }
        return new HashSet<>(groups.values());
    }

    private static Set<Set<Long>> groups(long[]... groupsOfKeys) {
        Set<Set<Long>> groups = new HashSet<>();
        for (long[] keys : groupsOfKeys) {
            Set<Long> group = new HashSet<>();
            for (long key : keys) {
                group.add(key);
            }
            groups.add(group);
        }
        return groups;
    }

    private static Set<Set<Long>> singleGroup(long... keys) {
        return groups(keys);
    }

    private static void assertAllPendingPresent(LongSet pending, Long2IntMap regions) {
        assertEquals(pending.size(), regions.size(), "every pending chunk must have a region id");
        for (long key : pending) {
            assertTrue(regions.containsKey(key), "missing region for pending chunk " + key);
        }
    }

    // ------------------------------------------------------ reference oracle

    /**
     * Independent specification of region discovery. Returns the expected partition of the
     * pending chunk keys, or {@code null} when the tick must take the serial fallback.
     */
    private static Set<Set<Long>> referencePartition(Set<Long> pending, World world) {
        // Fail-closed condition 1: any 25-chunk window around a pending chunk reaches an
        // unloaded chunk.
        for (long key : pending) {
            int centerChunkX = ChunkPos.getX(key);
            int centerChunkZ = ChunkPos.getZ(key);
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (world.unloaded.contains(ChunkPos.pack(centerChunkX + dx, centerChunkZ + dz))) {
                        return null;
                    }
                }
            }
        }

        // Global 6-connected component labeling over every propagator position in the world.
        Map<Long, Integer> componentOfPosition = new HashMap<>();
        List<Set<Long>> components = new ArrayList<>();
        List<Set<Long>> columnsOfComponent = new ArrayList<>();
        for (long start : world.allPositions) {
            if (componentOfPosition.containsKey(start)) {
                continue;
            }
            int componentId = components.size();
            Set<Long> members = new HashSet<>();
            Set<Long> columns = new HashSet<>();
            ArrayDeque<Long> queue = new ArrayDeque<>();
            componentOfPosition.put(start, componentId);
            members.add(start);
            queue.add(start);
            while (!queue.isEmpty()) {
                long position = queue.poll();
                int x = BlockPos.getX(position);
                int y = BlockPos.getY(position);
                int z = BlockPos.getZ(position);
                columns.add(ChunkPos.pack(x >> 4, z >> 4));
                long[] neighbors = {
                    BlockPos.asLong(x + 1, y, z),
                    BlockPos.asLong(x - 1, y, z),
                    BlockPos.asLong(x, y + 1, z),
                    BlockPos.asLong(x, y - 1, z),
                    BlockPos.asLong(x, y, z + 1),
                    BlockPos.asLong(x, y, z - 1),
                };
                for (long neighbor : neighbors) {
                    if (!componentOfPosition.containsKey(neighbor) && world.allPositions.contains(neighbor)) {
                        componentOfPosition.put(neighbor, componentId);
                        members.add(neighbor);
                        queue.add(neighbor);
                    }
                }
            }
            components.add(members);
            columnsOfComponent.add(columns);
        }

        // A component is discovered exactly when one of its positions sits in a 2-chunk
        // window of some pending chunk - the only positions production ever floods from.
        boolean[] discovered = new boolean[components.size()];
        List<Long> pendingList = new ArrayList<>(pending);
        for (int componentId = 0; componentId < components.size(); componentId++) {
            for (long position : components.get(componentId)) {
                int positionChunkX = BlockPos.getX(position) >> 4;
                int positionChunkZ = BlockPos.getZ(position) >> 4;
                for (long pendingKey : pendingList) {
                    if (Math.abs(positionChunkX - ChunkPos.getX(pendingKey)) <= 2
                            && Math.abs(positionChunkZ - ChunkPos.getZ(pendingKey)) <= 2) {
                        discovered[componentId] = true;
                        break;
                    }
                }
                if (discovered[componentId]) {
                    break;
                }
            }
        }

        // Fail-closed condition 2: a discovered component reaches an unloaded chunk through
        // a 6-neighbor, or exceeds the position cap.
        for (int componentId = 0; componentId < components.size(); componentId++) {
            if (!discovered[componentId]) {
                continue;
            }
            if (components.get(componentId).size() > 131072) {
                return null;
            }
            for (long position : components.get(componentId)) {
                int x = BlockPos.getX(position);
                int y = BlockPos.getY(position);
                int z = BlockPos.getZ(position);
                long[] neighbors = {
                    BlockPos.asLong(x + 1, y, z),
                    BlockPos.asLong(x - 1, y, z),
                    BlockPos.asLong(x, y + 1, z),
                    BlockPos.asLong(x, y - 1, z),
                    BlockPos.asLong(x, y, z + 1),
                    BlockPos.asLong(x, y, z - 1),
                };
                for (long neighbor : neighbors) {
                    if (world.unloaded.contains(ChunkPos.pack(BlockPos.getX(neighbor) >> 4, BlockPos.getZ(neighbor) >> 4))) {
                        return null;
                    }
                }
            }
        }

        // Pairwise relations of the specification as one union-find over
        // pending chunks [0, n) and components [n, n + components).
        int pendingCount = pendingList.size();
        int[] parent = new int[pendingCount + components.size()];
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
        }
        java.util.function.IntUnaryOperator find = new java.util.function.IntUnaryOperator() {
            @Override
            public int applyAsInt(int i) {
                while (parent[i] != i) {
                    parent[i] = parent[parent[i]];
                    i = parent[i];
                }
                return i;
            }
        };
        java.util.function.BiConsumer<Integer, Integer> union = (a, b) -> {
            int rootA = find.applyAsInt(a);
            int rootB = find.applyAsInt(b);
            if (rootA != rootB) {
                parent[Math.max(rootA, rootB)] = Math.min(rootA, rootB);
            }
        };

        // Rule A: pending chunks within Chebyshev 2.
        for (int i = 0; i < pendingCount; i++) {
            for (int j = i + 1; j < pendingCount; j++) {
                long a = pendingList.get(i);
                long b = pendingList.get(j);
                if (Math.abs(ChunkPos.getX(a) - ChunkPos.getX(b)) <= 2
                        && Math.abs(ChunkPos.getZ(a) - ChunkPos.getZ(b)) <= 2) {
                    union.accept(i, j);
                }
            }
        }
        // Rule B: a component whose positions reach a pending chunk's window is anchored
        // to that chunk.
        for (int componentId = 0; componentId < components.size(); componentId++) {
            if (!discovered[componentId]) {
                continue;
            }
            for (long position : components.get(componentId)) {
                int positionChunkX = BlockPos.getX(position) >> 4;
                int positionChunkZ = BlockPos.getZ(position) >> 4;
                for (int i = 0; i < pendingCount; i++) {
                    long pendingKey = pendingList.get(i);
                    if (Math.abs(positionChunkX - ChunkPos.getX(pendingKey)) <= 2
                            && Math.abs(positionChunkZ - ChunkPos.getZ(pendingKey)) <= 2) {
                        union.accept(i, pendingCount + componentId);
                    }
                }
            }
        }
        // Rule B (components): two discovered components with columns within Chebyshev 2.
        for (int a = 0; a < components.size(); a++) {
            if (!discovered[a]) {
                continue;
            }
            for (int b = a + 1; b < components.size(); b++) {
                if (!discovered[b]) {
                    continue;
                }
                merge: for (long columnA : columnsOfComponent.get(a)) {
                    for (long columnB : columnsOfComponent.get(b)) {
                        if (Math.abs(ChunkPos.getX(columnA) - ChunkPos.getX(columnB)) <= 2
                                && Math.abs(ChunkPos.getZ(columnA) - ChunkPos.getZ(columnB)) <= 2) {
                            union.accept(pendingCount + a, pendingCount + b);
                            break merge;
                        }
                    }
                }
            }
        }

        Map<Integer, Set<Long>> groups = new HashMap<>();
        for (int i = 0; i < pendingCount; i++) {
            groups.computeIfAbsent(find.applyAsInt(i), key -> new HashSet<>()).add(pendingList.get(i));
        }
        return new HashSet<>(groups.values());
    }

    private static void assertMatchesReference(LongSet pending, World world) {
        Set<Set<Long>> expected = referencePartition(new HashSet<>(pending), world);
        Long2IntMap actual = ParallelRedstone.computeRegions(pending, world.source());
        if (expected == null) {
            assertNull(actual, "discovery must refuse to parallelize this world");
            return;
        }
        assertNotNull(actual, "discovery must produce a region map for this world");
        assertAllPendingPresent(pending, actual);
        assertEquals(expected, partitionOf(actual),
                "region partition must match the reference; pending=" + pending);
    }

    // ---------------------------------------------------------- fail-closed

    @Test
    public void unloadedWindowChunkFailsClosed() {
        World world = new World().unload(1, 0);
        assertNull(ParallelRedstone.computeRegions(pending(chunk(0, 0)), world.source()));
        assertEquals(null, referencePartition(new HashSet<>(pending(chunk(0, 0))), world));
    }

    @Test
    public void unloadedWindowChunkInsideFloodFailsClosed() {
        // A component in loaded chunks 0..2 whose easternmost position borders chunk 3,
        // which is not loaded and lies outside every 2-chunk window of the pending chunk.
        World world = new World()
                .line(0, 47, 64, 0)
                .unload(3, 0);
        assertNull(ParallelRedstone.computeRegions(pending(chunk(0, 0)), world.source()));
        assertEquals(null, referencePartition(new HashSet<>(pending(chunk(0, 0))), world));
    }

    @Test
    public void oversizedComponentFailsClosed() {
        // A single line of 131073 positions: one over the 131072-position cap.
        World world = new World().line(0, 131072, 64, 0);
        assertNull(ParallelRedstone.computeRegions(pending(chunk(0, 0)), world.source()));
        assertEquals(null, referencePartition(new HashSet<>(pending(chunk(0, 0))), world));
    }

    @Test
    public void unsafeNeighbourhoodFailsClosed() {
        // The distance geometry is perfectly parallelizable, but one column in the 5x5
        // window around the pending chunk contains an unaudited reactive block. The
        // audit, not distance alone, must veto the phase.
        World world = new World();
        LongSet pending = pending(chunk(0, 0));
        ParallelRedstone.PropagatorSource source = world.source();
        ParallelRedstone.PropagatorSource unsafe = new ParallelRedstone.PropagatorSource() {
            @Override
            public LongOpenHashSet propagatorsOf(final int chunkX, final int chunkZ) {
                return source.propagatorsOf(chunkX, chunkZ);
            }

            @Override
            public boolean neighbourhoodSafe(final int chunkX, final int chunkZ) {
                return !(chunkX == 2 && chunkZ == 2);
            }
        };
        assertNull(ParallelRedstone.computeRegions(pending, unsafe));
        // The same geometry with an audited neighbourhood still parallelizes.
        assertNotNull(ParallelRedstone.computeRegions(pending, source));
    }

    // ------------------------------------------------------------ partitions

    @Test
    public void nearbyPendingChunksShareOneRegion() {
        World world = new World();
        LongSet pending = pending(chunk(0, 0), chunk(1, 0));
        Long2IntMap regions = ParallelRedstone.computeRegions(pending, world.source());
        assertNotNull(regions);
        assertAllPendingPresent(pending, regions);
        assertEquals(singleGroup(chunk(0, 0), chunk(1, 0)), partitionOf(regions));
        assertEquals(singleGroup(chunk(0, 0), chunk(1, 0)), referencePartition(new HashSet<>(pending), world));
    }

    @Test
    public void distantPendingChunksStaySeparate() {
        World world = new World();
        LongSet pending = pending(chunk(0, 0), chunk(10, 0));
        Long2IntMap regions = ParallelRedstone.computeRegions(pending, world.source());
        assertNotNull(regions);
        assertAllPendingPresent(pending, regions);
        assertEquals(groups(new long[] { chunk(0, 0) }, new long[] { chunk(10, 0) }), partitionOf(regions));
    }

    @Test
    public void propagatorComponentAnchorsBothPendings() {
        // One wire line spanning chunks 1..9: its first column is within Chebyshev 2 of
        // pending chunk (0,0) and its last within 2 of (10,0), so the whole network must
        // run in a single region - exactly what vanilla's serial execution guarantees.
        World world = new World().line(16, 159, 64, 0);
        LongSet pending = pending(chunk(0, 0), chunk(10, 0));
        Long2IntMap regions = ParallelRedstone.computeRegions(pending, world.source());
        assertNotNull(regions);
        assertAllPendingPresent(pending, regions);
        assertEquals(singleGroup(chunk(0, 0), chunk(10, 0)), partitionOf(regions));
        assertEquals(singleGroup(chunk(0, 0), chunk(10, 0)), referencePartition(new HashSet<>(pending), world));
    }

    @Test
    public void componentTooFarFromEveryPendingIsIgnored() {
        // A line in columns 4..5 is farther than 2 chunks from both pendings, so it never
        // anchors anything and the pendings stay independent.
        World world = new World().line(64, 95, 64, 0);
        LongSet pending = pending(chunk(0, 0), chunk(10, 0));
        Long2IntMap regions = ParallelRedstone.computeRegions(pending, world.source());
        assertNotNull(regions);
        assertAllPendingPresent(pending, regions);
        assertEquals(groups(new long[] { chunk(0, 0) }, new long[] { chunk(10, 0) }), partitionOf(regions));
        assertEquals(groups(new long[] { chunk(0, 0) }, new long[] { chunk(10, 0) }),
                referencePartition(new HashSet<>(pending), world));
    }

    @Test
    public void columnProximityMergesAnchoredComponents() {
        // Line A (columns 1..2) anchors only to pending (0,0); line B (columns 3..4, kept
        // 6-disjoint by a gap) anchors only to (5,0); their columns sit 1 chunk apart, so
        // the proximity rule must merge both pendings into one region.
        World world = new World().line(16, 47, 64, 0).line(50, 79, 64, 0);
        LongSet pending = pending(chunk(0, 0), chunk(5, 0));
        Long2IntMap regions = ParallelRedstone.computeRegions(pending, world.source());
        assertNotNull(regions);
        assertAllPendingPresent(pending, regions);
        assertEquals(singleGroup(chunk(0, 0), chunk(5, 0)), partitionOf(regions));
        assertEquals(singleGroup(chunk(0, 0), chunk(5, 0)), referencePartition(new HashSet<>(pending), world));
    }

    @Test
    public void threeSeparateComponentsGiveThreeRegions() {
        // Three wires anchored one-to-one to three far-apart pendings.
        World world = new World()
                .line(16, 31, 64, 0)
                .line(64, 79, 64, 0)
                .line(112, 127, 64, 0);
        LongSet pending = pending(chunk(0, 0), chunk(4, 0), chunk(8, 0));
        Long2IntMap regions = ParallelRedstone.computeRegions(pending, world.source());
        assertNotNull(regions);
        assertAllPendingPresent(pending, regions);
        assertEquals(3, partitionOf(regions).size(), "each anchored wire must form its own region");
        assertEquals(referencePartition(new HashSet<>(pending), world), partitionOf(regions));
    }

    // -------------------------------------------------------------- random

    @Test
    public void randomizedWorldsMatchReference() {
        Random random = new Random(0x5EED2026L);
        for (int iteration = 0; iteration < 40; iteration++) {
            World world = new World();
            for (int chunkX = 0; chunkX < 7; chunkX++) {
                for (int chunkZ = 0; chunkZ < 7; chunkZ++) {
                    if (random.nextDouble() < 0.10) {
                        world.unload(chunkX, chunkZ);
                        continue;
                    }
                    int count = random.nextInt(6);
                    for (int k = 0; k < count; k++) {
                        int x = chunkX * 16 + random.nextInt(16);
                        int z = chunkZ * 16 + random.nextInt(16);
                        int y = 64 + random.nextInt(3);
                        if (k > 0 && !world.allPositions.isEmpty() && random.nextBoolean()) {
                            // Chain off a random existing position so components form.
                            long from = pickRandom(world.allPositions, random);
                            int axis = random.nextInt(3);
                            int step = random.nextBoolean() ? 1 : -1;
                            x = BlockPos.getX(from) + (axis == 0 ? step : 0);
                            y = BlockPos.getY(from) + (axis == 1 ? step : 0);
                            z = BlockPos.getZ(from) + (axis == 2 ? step : 0);
                        }
                        world.put(x, y, z);
                    }
                }
            }
            LongOpenHashSet pending = new LongOpenHashSet();
            int count = 2 + random.nextInt(4);
            while (pending.size() < count) {
                pending.add(chunk(random.nextInt(7), random.nextInt(7)));
            }
            assertMatchesReference(pending, world);
        }
    }

    private static long pickRandom(Set<Long> values, Random random) {
        int index = random.nextInt(values.size());
        for (long value : values) {
            if (index-- == 0) {
                return value;
            }
        }
        throw new IllegalStateException("unreachable");
    }
}
