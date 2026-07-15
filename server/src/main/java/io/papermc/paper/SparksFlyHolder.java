package io.papermc.paper;

/**
 * Static holder for the SparksFly instance, accessible from the server module.
 * Set by VeltisBootstrap (runtime module) during initialization.
 */
public final class SparksFlyHolder {
    public static SparksFly instance;

    private SparksFlyHolder() {
    }
}
