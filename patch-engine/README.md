# Patch Engine Documentation

## Overview

The **Patch Engine** is a runtime module that enables EULA-compliant distribution of VeltisMC. Instead of shipping a pre-patched Minecraft jar (which violates the EULA), the patch-engine:

1. Downloads the vanilla Minecraft server jar from Mojang's official servers
2. Decompiles it using Vineflower
3. Applies patches from the `patches/` directory
4. Compiles patched sources
5. Packages the result into a versioned jar
6. Caches results for subsequent runs

This ensures VeltisMC only distributes the launcher, not modified Minecraft code.

## Architecture

### Module Location
```
patch-engine/
├── build.gradle.kts
└── src/main/java/org/veltismc/veltis/patchengine/
    ├── PatchEngineConfig.java          # Configuration management
    ├── PatchEngineException.java       # Error handling
    ├── VanillaJarDownloader.java       # Mojang API integration
    ├── RuntimeDecompiler.java          # Vineflower decompiler
    ├── RuntimePatchApplier.java        # Git patch application
    └── PatchedJarBuilder.java          # Orchestrator
```

### Directory Structure

The patch-engine creates the following directory structure in the user's home directory:

```
{home_directory}/
├── vanilla/{version}/
│   ├── server.jar                      # Downloaded from Mojang
│   └── server-sha256.txt               # Hash verification (future)
│
├── versions/{version}/
│   ├── patched-source/                 # Decompiled + patched source
│   ├── classes/                        # Compiled patched classes
│   ├── libraries/                      # Minecraft dependencies
│   ├── server.jar                      # Copy of vanilla jar (unchanged)
│   └── veltismc-server.jar             # Final patched server jar ✓
│
└── patches/                            # Repository patches (optional at runtime)
    ├── server/                         # Server-side patches
    │   ├── 0001-MinecraftServer.patch
    │   ├── 0002-PlayerList.patch
    │   └── ...
    └── api/                            # API patches (if needed)
```

## Components

### 1. PatchEngineConfig
Configuration record that manages all path lookups for a specific Minecraft version.

```java
var config = new PatchEngineConfig(
    "26.2",                           // Minecraft version
    Path.of(System.getProperty("user.home")).resolve("VeltisMC"),  // Home dir
    Path.of(System.getProperty("user.dir")).resolve("patches")     // Patches dir
);

// Convenient path getters:
config.vanillaServerJar();              // vanilla/{version}/server.jar
config.patchedServerJar();              // versions/{version}/veltismc-server.jar
config.patchedSourceDirectory();        // versions/{version}/patched-source/
config.compiledClassesDirectory();      // versions/{version}/classes/
config.serverPatchesDirectory();        // patches/server/
```

### 2. VanillaJarDownloader
Downloads vanilla server jar from Mojang using version manifests.

```java
var downloader = new VanillaJarDownloader();
Path vanillaJar = downloader.download("26.2", Path.of("vanilla/26.2/server.jar"));

// Optional SHA-256 verification
boolean valid = downloader.verifySha256(vanillaJar, "abc123...");
```

**Features:**
- Resolves version URLs from Mojang's version manifest
- Downloads with configurable timeouts
- SHA-256 integrity verification
- Caches downloads (skips if already present)

### 3. RuntimeDecompiler
Decompiles bytecode to Java source using Vineflower.

```java
var decompiler = new RuntimeDecompiler();
decompiler.decompile(
    Path.of("vanilla/26.2/server.jar"),
    Path.of("versions/26.2/patched-source/")
);
```

**Features:**
- Configured for Minecraft decompilation best practices
- Fixes known decompilation artifacts (VAR_NAMELESS_ENCLOSURE)
- Preserves original identifiers (no renaming)

### 4. RuntimePatchApplier
Applies git patches to decompiled source using system `git apply` command.

```java
var patchApplier = new RuntimePatchApplier();
patchApplier.applyPatches(
    Path.of("patches/server/"),
    Path.of("versions/26.2/patched-source/")
);
```

**Features:**
- Automatically discovers .patch files (sorted numerically)
- Handles whitespace and spacing differences
- Clear error messages on patch failure
- Uses native git command (requires git in PATH)

### 5. PatchedJarBuilder
Orchestrates the complete build pipeline.

```java
var config = new PatchEngineConfig(version, homeDir, patchesDir);
var builder = new PatchedJarBuilder(config);
Path result = builder.build();  // Returns: versions/{version}/veltismc-server.jar
```

**Pipeline:**
1.  Download vanilla jar (caches if exists)
2.  Decompile vanilla jar (caches decompiled sources)
3.  Apply patches to decompiled source
4.  Compile patched sources using `javac`
5.  Package into final veltismc-server.jar

**Caching:**
- Returns immediately if `veltismc-server.jar` already exists
- Skips download if vanilla jar cached
- Skips decompilation if sources already cached
- Clears compiled classes before each compilation

## Integration with Launcher

The `VeltisLauncher` integrates patch-engine as follows:

```java
// In VeltisLauncher.locateOrBuildServerJar()
var patchedPath = homeDir.resolve("versions")
    .resolve(version)
    .resolve("veltismc-server.jar");

if (Files.exists(patchedPath)) {
    return patchedPath;  // Use cached version
}

// Build using patch-engine if not cached
return buildPatchedJar(homeDir, version);
```

The launcher automatically invokes the patch-engine on first run, creating the final jar in the background. Subsequent launches use the cached jar.

## Error Handling

All errors are wrapped in `PatchEngineException`:

```java
try {
    var builder = new PatchedJarBuilder(config);
    builder.build();
} catch (PatchEngineException e) {
    LOG.error("Failed to build patched jar: " + e.getMessage());
    // e.getCause() contains root cause
}
```

Common error scenarios:
- Network issues (downloading from Mojang)
- Missing patches directory
- Patch application conflicts
- Compilation errors in patched sources
- Insufficient disk space

## Performance Characteristics

| Step | Time | Notes |
|------|------|-------|
| Download vanilla jar | 2-5 min | Network dependent, 500MB jar |
| Decompile | 3-8 min | Vineflower processing |
| Apply patches | 10-30 sec | Git patch application |
| Compile patches | 1-3 min | javac on patched sources |
| Package jar | 30-60 sec | Creating final jar |
| **Total (first run)** | **7-17 min** | |
| **Total (cached)** | **<1 sec** | Just return path |

## Requirements

- **Java 26+** (for compilation compatibility)
- **Git** in PATH (for patch application)
- **javac** (included with JDK)
- **Network access** to Mojang servers (first run only)
- **~3GB disk space** (vanilla jar + decompiled source + compiled classes)

## Future Enhancements

- [ ] Parallel decompilation with multiple cores
- [ ] Incremental patching (only compile changed files)
- [ ] Cache invalidation on patch updates
- [ ] SHA-256 verification of downloaded vanilla jar
- [ ] Support for API patches
- [ ] Progress reporting UI
- [ ] Resumable downloads

## Testing

Run tests with:
```bash
gradle :patch-engine:test
```

Test components individually:
```java
// Test downloader
VanillaJarDownloader downloader = new VanillaJarDownloader();
Path jar = downloader.download("26.2", outputPath);

// Test decompiler
RuntimeDecompiler decompiler = new RuntimeDecompiler();
decompiler.decompile(jar, sourceDir);

// Test patch applier
RuntimePatchApplier applier = new RuntimePatchApplier();
applier.applyPatches(patchesDir, sourceDir);
```

## Troubleshooting

### "Failed to download vanilla server jar"
- Check internet connection
- Verify Mojang servers are accessible
- Check if version string is correct (e.g., "26.2")

### "Patch application failed"
- Ensure patches are in correct git format
- Verify patches apply cleanly (no conflicts)
- Check that decompiled source matches patch expectations

### "Javac compilation failed"
- Check for syntax errors in patched sources
- Verify patch didn't introduce invalid code
- Ensure Java 26+ is being used

### "Out of disk space"
- Clear `versions/{version}/` directories for unused versions
- The final jar is ~600MB (vanilla jar + patches)

## References

- [Vineflower Decompiler](https://github.com/Vineflower/Vineflower)
- [Minecraft EULA](https://www.minecraft.net/en-us/eula)
- [Git Patch Format](https://www.kernel.org/doc/html/latest/process/applying-patches.html)
