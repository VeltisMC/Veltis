# CPU dispatch

The current `transform` module is portable C11 and contains no explicit
architecture-specific dispatch or intrinsic code. CMake does not select a
platform-specific transform implementation. Compiler optimizations remain
subject to the toolchain and flags used for the build.

Do not add architecture-specific paths or claim a performance benefit without
preserving the scalar result contract and measuring the actual server workload.
