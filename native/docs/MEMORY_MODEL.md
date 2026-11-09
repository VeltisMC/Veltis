# Memory model

Java owns FFM arenas and closes them after calls. The loader keeps each library
in a shared arena for its loaded lifetime and guards lifecycle operations with
a read/write lock.

The `transform` module stores only an atomic initialized flag and a
thread-local error buffer. Input and output buffers are borrowed for the
duration of a downcall; the module neither retains nor frees them. Native
strings returned for diagnostics are borrowed and must not be freed by Java.

Any future module that retains a caller buffer or adds mutable state must define
its ownership and synchronization contract before exposing that operation.
