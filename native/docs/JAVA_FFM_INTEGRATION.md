# Java FFM integration

`NativeLoader` uses the JDK Foreign Function & Memory API to open a shared
library, resolve the module handshake and lifecycle symbols, and create
downcall handles. It rejects unsupported host platforms, mismatched module
identity, ABI incompatibility, and missing required exports before returning a
`LoadedLibrary`.

`NativeLoader.tryLoad(config)` preserves the existing `transform` entry point.
`tryLoad(config, moduleName, expectedAbiVersion)` loads a named module.
`NativeEngine` owns the transform fast path and Java fallback, and provides
`loadModule`, `moduleStatus`, and `loadedModule` for additional modules.

Each loaded module has its own lifecycle lock and FFM arena. `close()` waits for
calls made through the `LoadedLibrary` API, shuts down the module, and releases
its arena. A symbol address returned by `findSymbol` is valid only while that
library remains open.

The server is usable when native access is unavailable. Native failures are
reported in `NativeStatus`; fallback-disabled operations fail explicitly.
