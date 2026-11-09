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
