package org.veltismc.buildtools.version;

import java.util.Objects;
import java.util.regex.Pattern;

public final class MinecraftVersion implements Comparable<MinecraftVersion> {

    private static final Pattern PATTERN =
        Pattern.compile("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?$");

    private final int major;
    private final int minor;
    private final int patch;

    private MinecraftVersion(int major, int minor, int patch) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
    }

    public static MinecraftVersion parse(String input) {
        Objects.requireNonNull(input, "input must not be null");
        var matcher = PATTERN.matcher(input.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                "Invalid version format: " + input);
        }
        var major = Integer.parseInt(matcher.group(1));
        var minor = Integer.parseInt(matcher.group(2));
        var patch = matcher.group(3) != null
            ? Integer.parseInt(matcher.group(3))
            : 0;
        return new MinecraftVersion(major, minor, patch);
    }

    public static MinecraftVersion of(int major, int minor) {
        return new MinecraftVersion(major, minor, 0);
    }

    public static MinecraftVersion of(int major, int minor, int patch) {
        return new MinecraftVersion(major, minor, patch);
    }

    public int major() {
        return major;
    }

    public int minor() {
        return minor;
    }

    public int patch() {
        return patch;
    }

    public boolean isAtLeast(MinecraftVersion other) {
        return compareTo(other) >= 0;
    }

    public boolean isAtMost(MinecraftVersion other) {
        return compareTo(other) <= 0;
    }

    @Override
    public int compareTo(MinecraftVersion other) {
        var majorDiff = Integer.compare(this.major, other.major);
        if (majorDiff != 0) return majorDiff;
        var minorDiff = Integer.compare(this.minor, other.minor);
        if (minorDiff != 0) return minorDiff;
        return Integer.compare(this.patch, other.patch);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MinecraftVersion other)) return false;
        return major == other.major
            && minor == other.minor
            && patch == other.patch;
    }

    @Override
    public int hashCode() {
        return Objects.hash(major, minor, patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + (patch > 0 ? "." + patch : "");
    }
}


