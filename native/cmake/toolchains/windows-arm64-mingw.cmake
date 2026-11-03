# Windows ARM64 cross-compilation with LLVM-MinGW.
#
# This is a compile-only toolchain: CMake can produce and inspect the DLL, but
# the producing host cannot execute an ARM64 Windows binary. Use it through the
# `windows-arm64-mingw-cross` preset or:
#
#   cmake -S . -B build/native/cmake/windows-aarch64 \
#         -DCMAKE_TOOLCHAIN_FILE=cmake/toolchains/windows-arm64-mingw.cmake
#
# Point VELTIS_ARM64_CC and VELTIS_ARM64_CXX at the compiler binaries when they
# are not on PATH. The default names are what LLVM-MinGW ships.

set(CMAKE_SYSTEM_NAME Windows)
set(CMAKE_SYSTEM_PROCESSOR ARM64)

if(DEFINED ENV{VELTIS_ARM64_CC} AND NOT "$ENV{VELTIS_ARM64_CC}" STREQUAL "")
    set(CMAKE_C_COMPILER "$ENV{VELTIS_ARM64_CC}")
else()
    set(CMAKE_C_COMPILER "aarch64-w64-mingw32-clang")
endif()

if(DEFINED ENV{VELTIS_ARM64_CXX} AND NOT "$ENV{VELTIS_ARM64_CXX}" STREQUAL "")
    set(CMAKE_CXX_COMPILER "$ENV{VELTIS_ARM64_CXX}")
else()
    set(CMAKE_CXX_COMPILER "aarch64-w64-mingw32-clang++")
endif()

# Do not let CMake "helpfully" find host programs or libraries for the target.
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE ONLY)
