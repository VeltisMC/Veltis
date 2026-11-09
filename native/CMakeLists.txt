cmake_minimum_required(VERSION 3.21)

if(CMAKE_SYSTEM_NAME STREQUAL "Darwin" OR CMAKE_HOST_APPLE)
    set(CMAKE_OSX_DEPLOYMENT_TARGET "11.0" CACHE STRING "Minimum supported macOS version")
endif()

project(VeltisNative VERSION 1.0.0 DESCRIPTION "Optional VeltisMC C11 modules" LANGUAGES C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_C_EXTENSIONS OFF)

set(VELTIS_NATIVE_OUTPUT_DIR "${CMAKE_BINARY_DIR}/out" CACHE PATH
    "Directory receiving the native module libraries")
option(VELTIS_NATIVE_BUILD_TESTS "Build native module tests" ON)
option(VELTIS_NATIVE_WERROR "Treat compiler warnings as errors" OFF)
if(WIN32)
    set(_static_runtime_default ON)
else()
    set(_static_runtime_default OFF)
endif()
option(VELTIS_NATIVE_STATIC_RUNTIME "Link the Windows C runtime statically" ${_static_runtime_default})
set(VELTIS_NATIVE_SANITIZERS "" CACHE STRING "Comma-separated C sanitizers")

if(NOT CMAKE_CONFIGURATION_TYPES AND NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "Build type" FORCE)
endif()

if(VELTIS_NATIVE_SANITIZERS)
    string(REPLACE "," ";" _sanitizers "${VELTIS_NATIVE_SANITIZERS}")
    foreach(_sanitizer IN LISTS _sanitizers)
        if(NOT _sanitizer MATCHES "^(address|undefined|thread)$")
            message(FATAL_ERROR "Unsupported C sanitizer: ${_sanitizer}")
        endif()
        if(MSVC)
            if(NOT _sanitizer STREQUAL "address")
                message(FATAL_ERROR "MSVC supports only the address sanitizer")
            endif()
            add_compile_options(/fsanitize=address)
            add_link_options(/fsanitize=address)
        else()
            add_compile_options("-fsanitize=${_sanitizer}" -fno-omit-frame-pointer)
            add_link_options("-fsanitize=${_sanitizer}")
        endif()
    endforeach()
endif()

if(VELTIS_NATIVE_WERROR)
    if(MSVC)
        add_compile_options(/WX)
    else()
        add_compile_options(-Werror)
    endif()
endif()

function(veltis_enable_c_warnings target)
    if(MSVC)
        target_compile_options(${target} PRIVATE /W4)
        if(VELTIS_NATIVE_STATIC_RUNTIME)
            set_property(TARGET ${target} PROPERTY
                MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
        endif()
        set_property(TARGET ${target} PROPERTY
            MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
    else()
        target_compile_options(${target} PRIVATE -Wall -Wextra -Wpedantic -Wshadow)
    endif()
    if(WIN32 AND MINGW AND VELTIS_NATIVE_STATIC_RUNTIME)
        target_link_options(${target} PRIVATE -static)
    endif()
endfunction()

function(veltis_set_output_directory target directory)
    set_target_properties(${target} PROPERTIES
        RUNTIME_OUTPUT_DIRECTORY "${directory}"
        LIBRARY_OUTPUT_DIRECTORY "${directory}"
        ARCHIVE_OUTPUT_DIRECTORY "${directory}")
    foreach(configuration DEBUG RELEASE RELWITHDEBINFO MINSIZEREL)
        set_target_properties(${target} PROPERTIES
            RUNTIME_OUTPUT_DIRECTORY_${configuration} "${directory}"
            LIBRARY_OUTPUT_DIRECTORY_${configuration} "${directory}"
            ARCHIVE_OUTPUT_DIRECTORY_${configuration} "${directory}")
    endforeach()
endfunction()

file(MAKE_DIRECTORY "${VELTIS_NATIVE_OUTPUT_DIR}")
file(GLOB _module_entries LIST_DIRECTORIES true "${CMAKE_CURRENT_SOURCE_DIR}/modules/*")
list(SORT _module_entries)
set(VELTIS_NATIVE_MODULE_TARGETS "")

foreach(_module_dir IN LISTS _module_entries)
    if(NOT IS_DIRECTORY "${_module_dir}")
        continue()
    endif()

    get_filename_component(_module_name "${_module_dir}" NAME)
    if(NOT _module_name MATCHES "^[a-z][a-z0-9_]*$")
        message(FATAL_ERROR "Invalid native module directory name: ${_module_name}")
    endif()

    if(NOT EXISTS "${_module_dir}/main.c")
        message(FATAL_ERROR "Native module '${_module_name}' must provide ${_module_dir}/main.c")
    endif()

    file(GLOB_RECURSE _module_sources CONFIGURE_DEPENDS
        "${_module_dir}/*.c")
    list(SORT _module_sources)
    if(NOT _module_sources)
        message(FATAL_ERROR "Native module '${_module_name}' contains no C source files")
    endif()

    add_library("${_module_name}" SHARED ${_module_sources})
    target_compile_definitions("${_module_name}" PRIVATE VELTIS_MODULE_EXPORTS)
    target_compile_features("${_module_name}" PRIVATE c_std_11)
    target_include_directories("${_module_name}" PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/include"
        "${_module_dir}")
    set_target_properties("${_module_name}" PROPERTIES
        OUTPUT_NAME "${_module_name}"
        C_VISIBILITY_PRESET hidden
        POSITION_INDEPENDENT_CODE ON)
    if(WIN32)
        set_target_properties("${_module_name}" PROPERTIES PREFIX "")
    endif()
    if(NOT WIN32 AND NOT APPLE)
        target_link_options("${_module_name}" PRIVATE "LINKER:--no-undefined")
    endif()
    veltis_enable_c_warnings("${_module_name}")
    veltis_set_output_directory("${_module_name}" "${VELTIS_NATIVE_OUTPUT_DIR}")
    list(APPEND VELTIS_NATIVE_MODULE_TARGETS "${_module_name}")
endforeach()

if(NOT VELTIS_NATIVE_MODULE_TARGETS)
    message(STATUS "No native modules are present; Java fallback remains available")
endif()

if(NOT VELTIS_NATIVE_PLATFORM_KEY)
    string(TOLOWER "${CMAKE_SYSTEM_NAME}" _native_os)
    if(_native_os STREQUAL "darwin")
        set(_native_os "macos")
    endif()
    string(TOLOWER "${CMAKE_SYSTEM_PROCESSOR}" _native_arch)
    if(_native_arch MATCHES "^(amd64|x64)$")
        set(_native_arch "x86_64")
    elseif(_native_arch STREQUAL "arm64")
        set(_native_arch "aarch64")
    endif()
    set(VELTIS_NATIVE_PLATFORM_KEY "${_native_os}-${_native_arch}")
endif()
string(REPLACE "-" ";" _platform_parts "${VELTIS_NATIVE_PLATFORM_KEY}")
list(GET _platform_parts 0 VELTIS_NATIVE_OS_NAME)
list(GET _platform_parts 1 VELTIS_NATIVE_ARCHITECTURE)
math(EXPR VELTIS_NATIVE_POINTER_BITS "${CMAKE_SIZEOF_VOID_P} * 8")
set(VELTIS_NATIVE_ABI_VERSION 1)
set(VELTIS_NATIVE_VERSION_STRING "1.0.0")
file(STRINGS "${CMAKE_CURRENT_SOURCE_DIR}/include/veltis/native/veltis_native.h"
    VELTIS_NATIVE_API_LINES REGEX "^VELTIS_NATIVE_API ")
set(VELTIS_NATIVE_EXPORTS "")
foreach(_line IN LISTS VELTIS_NATIVE_API_LINES)
    string(REGEX REPLACE "^.*[ *]([A-Za-z_][A-Za-z0-9_]*)\\(.*$" "\\1" _symbol "${_line}")
    list(APPEND VELTIS_NATIVE_EXPORTS "${_symbol}")
endforeach()
list(LENGTH VELTIS_NATIVE_EXPORTS VELTIS_NATIVE_EXPORT_COUNT)
string(REPLACE ";" "\", \"" VELTIS_NATIVE_EXPORTS_JSON "${VELTIS_NATIVE_EXPORTS}")
set(VELTIS_NATIVE_EXPORTS_JSON "\"${VELTIS_NATIVE_EXPORTS_JSON}\"")
if(VELTIS_NATIVE_STATIC_RUNTIME)
    set(VELTIS_NATIVE_STATIC_RUNTIME_JSON true)
else()
    set(VELTIS_NATIVE_STATIC_RUNTIME_JSON false)
endif()
set(VELTIS_NATIVE_MINIMUM_OS "${CMAKE_OSX_DEPLOYMENT_TARGET}")
if(VELTIS_NATIVE_WERROR)
    set(VELTIS_NATIVE_WERROR_JSON true)
else()
    set(VELTIS_NATIVE_WERROR_JSON false)
endif()
set(VELTIS_NATIVE_SANITIZERS_JSON "")
if(VELTIS_NATIVE_SANITIZERS)
    string(REPLACE "," "\", \"" VELTIS_NATIVE_SANITIZERS_JSON "${VELTIS_NATIVE_SANITIZERS}")
    set(VELTIS_NATIVE_SANITIZERS_JSON "\"${VELTIS_NATIVE_SANITIZERS_JSON}\"")
endif()
set(VELTIS_NATIVE_MODULES_JSON "")
foreach(_module_name IN LISTS VELTIS_NATIVE_MODULE_TARGETS)
    if(VELTIS_NATIVE_MODULES_JSON)
        string(APPEND VELTIS_NATIVE_MODULES_JSON ", ")
    endif()
    string(APPEND VELTIS_NATIVE_MODULES_JSON "\"${_module_name}\"")
endforeach()
set(VELTIS_NATIVE_BUILD_INFO_GENERATED "${CMAKE_BINARY_DIR}/veltis-native-build-info.json")
configure_file(
    "${CMAKE_CURRENT_SOURCE_DIR}/cmake/veltis-native-build-info.json.in"
    "${VELTIS_NATIVE_BUILD_INFO_GENERATED}"
    @ONLY)
add_custom_target(veltis_native_build_info ALL
    COMMAND "${CMAKE_COMMAND}" -E copy_if_different
        "${VELTIS_NATIVE_BUILD_INFO_GENERATED}"
        "${VELTIS_NATIVE_OUTPUT_DIR}/veltis-native-build-info.json"
    VERBATIM)

if(VELTIS_NATIVE_BUILD_TESTS)
    enable_testing()
    add_subdirectory(tests)
endif()
