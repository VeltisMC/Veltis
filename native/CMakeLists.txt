cmake_minimum_required(VERSION 3.21)

project(VeltisNative
    VERSION 1.0.0
    DESCRIPTION "VeltisMC optional native engine"
    LANGUAGES C CXX
)

# Deterministic minimum macOS version. Without this, CMake uses the SDK version,
# which changes with every runner image and silently narrows compatibility. The
# value is recorded in the manifest; it is a build-time floor, not a claim that
# an older macOS was executed. The JVM's own requirement is usually higher.
if(APPLE AND NOT CMAKE_OSX_DEPLOYMENT_TARGET)
    set(CMAKE_OSX_DEPLOYMENT_TARGET "11.0" CACHE STRING "Minimum macOS version" FORCE)
endif()

# Gradle passes the platform-qualified output directory explicitly. The fallback
# keeps a manual `cmake -S . -B build` usable without any Gradle properties.
set(VELTIS_NATIVE_OUTPUT_DIR "${CMAKE_BINARY_DIR}/out" CACHE PATH
    "Directory receiving the shared library and test fixtures")

option(VELTIS_NATIVE_BUILD_TESTS "Build the native engine test executable and fixtures" ON)

# The DLL should not need a compiler runtime beside it. MinGW gets a static
# libgcc/libstdc++ by default; MSVC users can opt in to the static CRT. Linux and
# macOS use the system runtime, which is the normal convention on those systems.
if(MINGW)
    set(VELTIS_NATIVE_STATIC_RUNTIME_DEFAULT ON)
else()
    set(VELTIS_NATIVE_STATIC_RUNTIME_DEFAULT OFF)
endif()
option(VELTIS_NATIVE_STATIC_RUNTIME
    "Link the C/C++ runtime statically where the toolchain supports it"
    ${VELTIS_NATIVE_STATIC_RUNTIME_DEFAULT})

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_C_EXTENSIONS OFF)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# A single-config generator otherwise produces an unoptimised, undefined build
# type. A manual `cmake -S . -B build` should behave like the documented Release
# build; presets and Gradle still pass an explicit type.
if(NOT CMAKE_CONFIGURATION_TYPES AND NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "Build type" FORCE)
    message(STATUS "VeltisNative: CMAKE_BUILD_TYPE was empty; defaulting to Release")
endif()

# `math(EXPR)` is required: CMake message arguments understand only plain
# `${variable}` references, not arithmetic inside them.
math(EXPR VELTIS_NATIVE_POINTER_BITS "${CMAKE_SIZEOF_VOID_P} * 8")

message(STATUS
    "VeltisNative: system=${CMAKE_SYSTEM_NAME} processor=${CMAKE_SYSTEM_PROCESSOR} "
    "pointer=${VELTIS_NATIVE_POINTER_BITS}-bit "
    "C=${CMAKE_C_COMPILER_ID} ${CMAKE_C_COMPILER_VERSION} "
    "CXX=${CMAKE_CXX_COMPILER_ID} ${CMAKE_CXX_COMPILER_VERSION}")
if(CMAKE_SIZEOF_VOID_P LESS 8)
    message(WARNING
        "VeltisNative: 32-bit builds are not a supported release target. "
        "The C ABI remains size_t-correct, but the Java FFM integration only "
        "recognises 64-bit x86-64 and AArch64 platforms.")
endif()

if(CMAKE_SYSTEM_NAME STREQUAL "Darwin" AND NOT CMAKE_OSX_DEPLOYMENT_TARGET)
    message(STATUS
        "VeltisNative: CMAKE_OSX_DEPLOYMENT_TARGET is unset; the current SDK default is used. "
        "Set it explicitly for a redistributable macOS build.")
endif()

add_library(veltis_native SHARED
    src/c/native_utils.c
    src/cpp/native_engine.cpp
    src/cpp/algorithms.cpp
)

target_compile_definitions(veltis_native PRIVATE
    VELTIS_NATIVE_EXPORTS
)

target_compile_features(veltis_native PRIVATE
    c_std_11
    cxx_std_20
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

# Warnings are applied per target instead of globally: consumers of the public
# header must not inherit this project's warning policy, and the test/fixture
# targets should be held to the same standard as the library.
function(veltis_enable_warnings target)
    if(MSVC)
        target_compile_options(${target} PRIVATE /W4)
    else()
        target_compile_options(${target} PRIVATE -Wall -Wextra -Wpedantic)
    endif()
endfunction()

function(veltis_configure_runtime target)
    if(MSVC AND VELTIS_NATIVE_STATIC_RUNTIME)
        set_property(TARGET ${target} PROPERTY
            MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
    endif()
    if(MINGW AND VELTIS_NATIVE_STATIC_RUNTIME)
        # Keep libgcc and libc++ inside the artifact. The UCRT API set that
        # remains is part of Windows, not of the compiler.
        target_link_options(${target} PRIVATE -static-libgcc)
        # A C-only target does not link libstdc++, and clang warns that the
        # option was unused, so pass it only where it applies. Callers add the
        # C_ONLY keyword for a pure-C executable such as the export checker.
        if(NOT "C_ONLY" IN_LIST ARGN)
            target_link_options(${target} PRIVATE -static-libstdc++)
        endif()
    endif()
endfunction()

veltis_enable_warnings(veltis_native)
veltis_configure_runtime(veltis_native)

# On ELF platforms a shared object is allowed unresolved symbols by default.
# The Java loader would then fail at dlopen time, far from the build, so make the
# link step refuse them. PE and Mach-O already behave this way.
if(NOT WIN32 AND NOT APPLE)
    target_link_options(veltis_native PRIVATE "LINKER:--no-undefined")
endif()

# Reproducible artifact bytes. PE embeds the link time and Mach-O embeds a random
# LC_UUID; both change between otherwise identical builds, which makes the
# published checksum meaningless. ELF build IDs are content-derived, so they are
# left alone.
if(MSVC)
    target_link_options(veltis_native PRIVATE "/Brepro")
elseif(MINGW)
    target_link_options(veltis_native PRIVATE "-Wl,--no-insert-timestamp")
endif()
if(APPLE)
    target_link_options(veltis_native PRIVATE "LINKER:-no_uuid")
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

# ---------------------------------------------------------------------------
# Build metadata
#
# The version, ABI and export list are read from the public header rather than
# duplicated here, so the manifest cannot drift from the ABI. Gradle's
# `packageNative` task turns this file into the distribution manifest; a manual
# CMake build still gets the raw metadata beside the library.
# ---------------------------------------------------------------------------
file(STRINGS "${CMAKE_CURRENT_SOURCE_DIR}/include/veltis/native/veltis_native.h"
    VELTIS_NATIVE_HEADER_CONSTANTS
    REGEX "^#define VELTIS_NATIVE_(ABI_VERSION|VERSION_MAJOR|VERSION_MINOR|VERSION_PATCH|VERSION_STRING)[ \t]")
foreach(_line IN LISTS VELTIS_NATIVE_HEADER_CONSTANTS)
    if(_line MATCHES "^#define VELTIS_NATIVE_ABI_VERSION[ \t]+([0-9]+)")
        set(VELTIS_NATIVE_ABI_VERSION "${CMAKE_MATCH_1}")
    elseif(_line MATCHES "^#define VELTIS_NATIVE_VERSION_STRING[ \t]+\"([^\"]+)\"")
        set(VELTIS_NATIVE_VERSION_STRING "${CMAKE_MATCH_1}")
    endif()
endforeach()
if(NOT VELTIS_NATIVE_ABI_VERSION OR NOT VELTIS_NATIVE_VERSION_STRING)
    message(FATAL_ERROR "VeltisNative: could not read the ABI/version macros from the public header")
endif()

file(STRINGS "${CMAKE_CURRENT_SOURCE_DIR}/include/veltis/native/veltis_native.h"
    VELTIS_NATIVE_API_LINES
    REGEX "^VELTIS_NATIVE_API ")
set(VELTIS_NATIVE_EXPORTS "")
foreach(_line IN LISTS VELTIS_NATIVE_API_LINES)
    # The exported identifier is the last identifier before the opening paren.
    string(REGEX REPLACE "^.*[ *]([A-Za-z_][A-Za-z0-9_]*)\\(.*$" "\\1" _symbol "${_line}")
    list(APPEND VELTIS_NATIVE_EXPORTS "${_symbol}")
endforeach()
list(LENGTH VELTIS_NATIVE_EXPORTS VELTIS_NATIVE_EXPORT_COUNT)
string(REPLACE ";" "\", \"" VELTIS_NATIVE_EXPORTS_JSON "${VELTIS_NATIVE_EXPORTS}")
set(VELTIS_NATIVE_EXPORTS_JSON "\"${VELTIS_NATIVE_EXPORTS_JSON}\"")

function(veltis_native_normalize_arch out value)
    string(TOLOWER "${value}" _value)
    if(_value MATCHES "^(x86_64|amd64|x64)$")
        set(_result "x86_64")
    elseif(_value MATCHES "^(aarch64|arm64)$")
        set(_result "aarch64")
    elseif(_value MATCHES "^(riscv64|riscv)$")
        set(_result "riscv64")
    else()
        set(_result "${_value}")
    endif()
    set(${out} "${_result}" PARENT_SCOPE)
endfunction()

if(CMAKE_SYSTEM_NAME STREQUAL "Windows")
    set(VELTIS_NATIVE_OS_NAME "windows")
elseif(CMAKE_SYSTEM_NAME STREQUAL "Linux")
    set(VELTIS_NATIVE_OS_NAME "linux")
elseif(CMAKE_SYSTEM_NAME STREQUAL "Darwin")
    set(VELTIS_NATIVE_OS_NAME "macos")
elseif(CMAKE_SYSTEM_NAME STREQUAL "FreeBSD")
    set(VELTIS_NATIVE_OS_NAME "freebsd")
else()
    string(TOLOWER "${CMAKE_SYSTEM_NAME}" VELTIS_NATIVE_OS_NAME)
endif()

veltis_native_normalize_arch(VELTIS_NATIVE_ARCHITECTURE "${CMAKE_SYSTEM_PROCESSOR}")

# Gradle supplies the platform key it uses for output paths and the loader; a
# manual CMake build derives the same spelling from CMake's system variables.
if(NOT VELTIS_NATIVE_PLATFORM_KEY)
    set(VELTIS_NATIVE_PLATFORM_KEY "${VELTIS_NATIVE_OS_NAME}-${VELTIS_NATIVE_ARCHITECTURE}")
endif()
set(VELTIS_NATIVE_PLATFORM_KEY "${VELTIS_NATIVE_PLATFORM_KEY}" CACHE STRING
    "Loader platform key recorded in the artifact manifest")

# The triple is best-effort: it is exact for prefixed cross compilers and for
# CMake's own CMAKE_*_COMPILER_TARGET, and a documented convention otherwise.
set(VELTIS_NATIVE_TARGET_TRIPLE "")
if(CMAKE_C_COMPILER_TARGET)
    set(VELTIS_NATIVE_TARGET_TRIPLE "${CMAKE_C_COMPILER_TARGET}")
else()
    get_filename_component(_veltis_cc_name "${CMAKE_C_COMPILER}" NAME_WE)
    if(_veltis_cc_name MATCHES "^([a-z0-9_]+)-([a-z0-9_]+)-([a-z0-9_]+)-(clang|gcc|cc)$")
        set(VELTIS_NATIVE_TARGET_TRIPLE "${CMAKE_MATCH_1}-${CMAKE_MATCH_2}-${CMAKE_MATCH_3}")
    endif()
endif()
if(NOT VELTIS_NATIVE_TARGET_TRIPLE)
    if(MINGW)
        if(VELTIS_NATIVE_ARCHITECTURE STREQUAL "aarch64")
            set(VELTIS_NATIVE_TARGET_TRIPLE "aarch64-w64-mingw32")
        else()
            set(VELTIS_NATIVE_TARGET_TRIPLE "x86_64-w64-mingw32")
        endif()
    elseif(MSVC)
        set(VELTIS_NATIVE_TARGET_TRIPLE "${VELTIS_NATIVE_ARCHITECTURE}-pc-windows-msvc")
    elseif(APPLE)
        set(VELTIS_NATIVE_TARGET_TRIPLE "${VELTIS_NATIVE_ARCHITECTURE}-apple-darwin")
    elseif(VELTIS_NATIVE_OS_NAME STREQUAL "linux")
        set(VELTIS_NATIVE_TARGET_TRIPLE "${VELTIS_NATIVE_ARCHITECTURE}-unknown-linux-gnu")
    else()
        set(VELTIS_NATIVE_TARGET_TRIPLE "${VELTIS_NATIVE_ARCHITECTURE}-unknown-${VELTIS_NATIVE_OS_NAME}")
    endif()
endif()

if(VELTIS_NATIVE_STATIC_RUNTIME)
    set(VELTIS_NATIVE_STATIC_RUNTIME_JSON "true")
else()
    set(VELTIS_NATIVE_STATIC_RUNTIME_JSON "false")
endif()

# An empty value is the honest answer where the minimum runtime is not known at
# configure time (Linux glibc, Windows). CI records the observed requirement.
set(VELTIS_NATIVE_MINIMUM_OS "${CMAKE_OSX_DEPLOYMENT_TARGET}")

file(MAKE_DIRECTORY "${VELTIS_NATIVE_OUTPUT_DIR}")
configure_file(
    "${CMAKE_CURRENT_SOURCE_DIR}/cmake/veltis-native-build-info.json.in"
    "${VELTIS_NATIVE_OUTPUT_DIR}/veltis-native-build-info.json"
    @ONLY)
message(STATUS
    "VeltisNative: platform=${VELTIS_NATIVE_PLATFORM_KEY} abi=${VELTIS_NATIVE_ABI_VERSION} "
    "version=${VELTIS_NATIVE_VERSION_STRING} exports=${VELTIS_NATIVE_EXPORT_COUNT}")

if(VELTIS_NATIVE_BUILD_TESTS)
    enable_testing()
    add_subdirectory(tests)
endif()
