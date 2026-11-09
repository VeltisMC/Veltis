# Module boundaries

Each `native/modules/<name>/` directory is an independent shared-library
target. Its module name is also its target and output basename. A module:

- provides `main.c` and only C11 source files;
- exports its module identity, ABI/version metadata, lifecycle, capability
  mask, and self-test through the shared native ABI;
- owns its internal state and operation symbols;
- does not include server or Minecraft types or depend on another module's
  implementation.

`NativeLoader` validates and loads one requested module. `NativeEngine` keeps
Java policy and fallback above the loader; `NativeDistributionManager` selects
the requested module/platform artifact. The current implementation contains
the `transform` module.

## Adding a module

1. Create `native/modules/<name>/` with a lowercase identifier (`[a-z][a-z0-9_]*`)
   and a `main.c`. CMake discovers it from the directory name, makes it a
   separate shared library, and compiles only that directory's C sources.
2. Export the shared metadata, lifecycle, capability, and self-test symbols the
   ABI contract requires, plus the module's own operation symbols. Every module
   names itself; the loader rejects a library whose reported module name differs
   from the one requested.
3. Keep the module self-contained: no server/Minecraft types and no dependency
   on another module's implementation.
4. On the Java side, load it with `NativeLoader.tryLoad(config, "<name>", abi)`
   and keep the Java implementation as the fallback. A missing or rejected
   module must leave the server startable.
5. Build and test: `./gradlew :veltis-native:buildNative :veltis-native:testNative`,
   then `./gradlew :veltis-native:test -PveltisNativeTest=true`. The packaged
   archive and `native-build-info.json` enumerate modules from the built
   libraries, so a new module needs no list to be updated.

Do not turn modules into one combined library or add a C++ translation unit:
the per-module boundary is what lets the runtime load, reject, and close each
module independently.
