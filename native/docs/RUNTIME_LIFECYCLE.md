# Runtime lifecycle

The server may initialize the optional native engine during startup. The
default module is `transform`; an unavailable module records a `NativeStatus`
and leaves Java fallback active.

An additional module is loaded explicitly with `NativeEngine.loadModule`.
Modules have independent load status and library lifetimes. `shutdown()` closes
the transform library and every additional module, then resets the engine so it
can be initialized again.

The loader validates the module identity, ABI, version compatibility, and
required symbols before initialization. A module's FFM arena is released only
after `LoadedLibrary.close()` has waited for calls made through its managed
operations.
