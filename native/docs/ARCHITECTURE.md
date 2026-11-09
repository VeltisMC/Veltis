# Architecture

The native subsystem has three layers:

1. `NativeEngine` owns Java-side policy, lifecycle, fallback, and the current
   transform convenience operation.
2. `NativeLoader` resolves a named platform library and validates its module
   identity, ABI, version, required exports, and capabilities through FFM.
3. Each directory under `native/modules/` builds as an independent C11 shared
   library. The module does not depend on server classes or another module's
   sources.

CMake discovers module directories in sorted order. A module directory must
have a valid lowercase identifier and a `main.c`; all of its C sources are
compiled into the library named after that directory. The current module is
`transform`.

The shared ABI declarations live under `include/veltis/native/`. Module
operations are advertised through capability bits; the Java layer only calls
the transform operation for the `transform` module. Additional modules can be
loaded and queried independently with `NativeEngine.loadModule`.

Native support is optional. Failure to locate, verify, initialize, or use a
module leaves the Java implementation available unless fallback is explicitly
disabled.
