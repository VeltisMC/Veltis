# Threading model

Java serializes initialization and shutdown with `NativeEngine`'s lifecycle
lock. Each `LoadedLibrary` has a read/write lock: managed calls hold a read lock
and close holds the write lock, so close waits for in-flight calls.

The `transform` module uses a C11 atomic initialized flag and a thread-local
diagnostic buffer. It creates no worker threads and retains no caller buffers.
New modules must define their own concurrency behavior and coordinate shutdown
with any state they retain.
