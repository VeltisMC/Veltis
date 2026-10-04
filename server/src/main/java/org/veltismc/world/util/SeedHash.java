package org.veltismc.world.util;

/**
 * Deterministic seed mixing. Two seeds computed for the same coordinates are
 * always equal, regardless of thread scheduling, giving the engine deterministic
 * random ticks and generation across runs.
 */
public final class SeedHash {

    private SeedHash() {
    }

    public static long hash(long seed, int a, int b) {
        long h = seed;
        h ^= (a * 0x9E3779B97F4A7C15L) >>> 3;
        h ^= (b * 0xBF58476D1CE4E5B9L) >>> 5;
        h = mix(h);
        return h;
    }

    public static long hash(long seed, long a, long b) {
        long h = seed;
        h ^= (a * 0x9E3779B97F4A7C15L) >>> 3;
        h ^= (b * 0xBF58476D1CE4E5B9L) >>> 5;
        h = mix(h);
        return h;
    }

    public static long hash(long seed, int a, int b, int c) {
        long h = seed;
        h ^= (a * 0x9E3779B97F4A7C15L) >>> 3;
        h ^= (b * 0xBF58476D1CE4E5B9L) >>> 5;
        h ^= (c * 0x94D049BB133111EBL) >>> 7;
        h = mix(h);
        return h;
    }

    private static long mix(long h) {
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return h;
    }
}
