cmake_minimum_required(VERSION 3.20)

project(VeltisNative
    VERSION 1.0.0
    DESCRIPTION "VeltisMC optional native engine"
    LANGUAGES C CXX
)

# Gradle passes the platform-qualified output directory explicitly. The fallback
# keeps a manual `cmake -S . -B build` usable without any Gradle properties.
set(VELTIS_NATIVE_OUTPUT_DIR "${CMAKE_BINARY_DIR}/out" CACHE PATH
    "Directory receiving the shared library and test fixtures")

option(VELTIS_NATIVE_BUILD_TESTS "Build the native engine test executable and fixtures" ON)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_C_EXTENSIONS OFF)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(veltis_native SHARED
    src/c/native_utils.c
    src/cpp/native_engine.cpp
    src/cpp/algorithms.cpp
)

target_compile_definitions(veltis_native PRIVATE
    VELTIS_NATIVE_EXPORTS
)

target_include_directories(veltis_native
    PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/include
    PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/src/c
)

set_target_properties(veltis_native PROPERTIES
    OUTPUT_NAME "veltis_native"
    C_VISIBILITY_PRESET hidden
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN YES
    POSITION_INDEPENDENT_CODE ON
)

if(MSVC)
    target_compile_options(veltis_native PRIVATE /W4)
else()
    target_compile_options(veltis_native PRIVATE -Wall -Wextra -Wpedantic)
endif()

# MinGW-based toolchains (including LLVM-MinGW) otherwise import libc++.dll and
# libunwind.dll from the compiler's bin directory. An operator copying one DLL
# would then get "cannot open library" with no clue which dependency is missing,
# so link the C++ runtime statically. The UCRT API sets that remain are part of
# Windows, not of the compiler.
if(MINGW)
    target_link_options(veltis_native PRIVATE -static-libgcc -static-libstdc++)
endif()

# A multi-config generator (Visual Studio, Xcode, Ninja Multi-Config) appends a
# per-configuration directory unless each one is named. Setting both the generic
# and per-configuration properties is what keeps the shared library at exactly
# build/native/<platform>/ on every generator.
function(veltis_set_output_directory target directory)
    set_target_properties(${target} PROPERTIES
        RUNTIME_OUTPUT_DIRECTORY "${directory}"
        LIBRARY_OUTPUT_DIRECTORY "${directory}"
        ARCHIVE_OUTPUT_DIRECTORY "${directory}"
    )
    if(WIN32)
        # MinGW and some other generators otherwise put a "lib" prefix on a
        # Windows DLL, while MSVC does not. The Java loader and the install
        # instructions name one file, so normalize the prefix here instead of
        # teaching the loader two spellings.
        set_target_properties(${target} PROPERTIES PREFIX "")
    endif()
    foreach(config Debug Release RelWithDebInfo MinSizeRel)
        set_target_properties(${target} PROPERTIES
            RUNTIME_OUTPUT_DIRECTORY_${config} "${directory}"
            LIBRARY_OUTPUT_DIRECTORY_${config} "${directory}"
            ARCHIVE_OUTPUT_DIRECTORY_${config} "${directory}"
        )
    endforeach()
endfunction()

veltis_set_output_directory(veltis_native "${VELTIS_NATIVE_OUTPUT_DIR}")

if(VELTIS_NATIVE_BUILD_TESTS)
    enable_testing()
    add_subdirectory(tests)
endif()
