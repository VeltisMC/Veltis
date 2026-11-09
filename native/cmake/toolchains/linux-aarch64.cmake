# Linux AArch64 cross-compilation with a GNU cross toolchain.
#
# The default prefix is the Debian/Ubuntu `gcc-aarch64-linux-gnu` one. Override
# it for another distribution or an LLVM cross toolchain:
#
#   VELTIS_CROSS_PREFIX=aarch64-unknown-linux-gnu- cmake ...
#
# A cross build is compile-only. Run the tests on an AArch64 runner (native or a
# full-system emulator); CTest on the build host would try to execute a foreign
# binary.

set(CMAKE_SYSTEM_NAME Linux)
set(CMAKE_SYSTEM_PROCESSOR aarch64)

if(DEFINED ENV{VELTIS_CROSS_PREFIX} AND NOT "$ENV{VELTIS_CROSS_PREFIX}" STREQUAL "")
    set(VELTIS_CROSS_PREFIX "$ENV{VELTIS_CROSS_PREFIX}")
else()
    set(VELTIS_CROSS_PREFIX "aarch64-linux-gnu-")
endif()

set(CMAKE_C_COMPILER "${VELTIS_CROSS_PREFIX}gcc")

set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE ONLY)
