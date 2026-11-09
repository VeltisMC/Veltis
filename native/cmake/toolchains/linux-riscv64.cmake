# Linux RISC-V 64 cross-compilation with a GNU cross toolchain.
#
# RISC-V is an investigated Tier 2 target, not a supported release target.
# This file exists so the portability work can be compiled and inspected:
# building does not prove support, and the support table in README.md stays the
# authority until a real RISC-V runtime has executed the test suite.

set(CMAKE_SYSTEM_NAME Linux)
set(CMAKE_SYSTEM_PROCESSOR riscv64)

if(DEFINED ENV{VELTIS_CROSS_PREFIX} AND NOT "$ENV{VELTIS_CROSS_PREFIX}" STREQUAL "")
    set(VELTIS_CROSS_PREFIX "$ENV{VELTIS_CROSS_PREFIX}")
else()
    set(VELTIS_CROSS_PREFIX "riscv64-linux-gnu-")
endif()

set(CMAKE_C_COMPILER "${VELTIS_CROSS_PREFIX}gcc")

set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE ONLY)
