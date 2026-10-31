/*
 * Deliberately incompatible or broken native libraries used only by the Java
 * loader's rejection and fallback tests. They are built with values chosen by
 * CMake because the loader must prove it refuses or falls back from a real
 * library, before resolving or calling operations it cannot trust.
 *
 * Modes:
 *   0  version/ABI fixture: valid symbols, capabilities NONE
 *   1  initialization fails
 *   2  initialization succeeds but transform reports INTERNAL
 *   3  transform export is missing
 *
 * These fixtures are never installed and never loaded by the server.
 */
#include "veltis/native/veltis_native.h"

#ifndef VELTIS_FIXTURE_ABI
#  define VELTIS_FIXTURE_ABI 999
#endif
#ifndef VELTIS_FIXTURE_MAJOR
#  define VELTIS_FIXTURE_MAJOR 1
#endif
#ifndef VELTIS_FIXTURE_MINOR
#  define VELTIS_FIXTURE_MINOR 0
#endif
#ifndef VELTIS_FIXTURE_PATCH
#  define VELTIS_FIXTURE_PATCH 0
#endif
#ifndef VELTIS_FIXTURE_MODE
#  define VELTIS_FIXTURE_MODE 0
#endif

VELTIS_NATIVE_API int32_t veltis_native_abi_version(void) {
    return VELTIS_FIXTURE_ABI;
}

VELTIS_NATIVE_API int32_t veltis_native_version_major(void) {
    return VELTIS_FIXTURE_MAJOR;
}

VELTIS_NATIVE_API int32_t veltis_native_version_minor(void) {
    return VELTIS_FIXTURE_MINOR;
}

VELTIS_NATIVE_API int32_t veltis_native_version_patch(void) {
    return VELTIS_FIXTURE_PATCH;
}

VELTIS_NATIVE_API const char* veltis_native_version_string(void) {
    return "fixture";
}

VELTIS_NATIVE_API const char* veltis_native_status_string(int32_t status) {
    (void)status;
    return "fixture";
}

VELTIS_NATIVE_API const char* veltis_native_last_error(void) {
#if VELTIS_FIXTURE_MODE == 1
    return "fixture init failure";
#elif VELTIS_FIXTURE_MODE == 2
    return "fixture transform failure";
#else
    return "";
#endif
}

VELTIS_NATIVE_API int32_t veltis_native_init(void) {
#if VELTIS_FIXTURE_MODE == 1
    return VELTIS_NATIVE_ERROR_INTERNAL;
#else
    return VELTIS_NATIVE_OK;
#endif
}

VELTIS_NATIVE_API int32_t veltis_native_is_initialized(void) {
#if VELTIS_FIXTURE_MODE == 1
    return 0;
#else
    return 1;
#endif
}

VELTIS_NATIVE_API void veltis_native_shutdown(void) {
}

VELTIS_NATIVE_API uint32_t veltis_native_capabilities(void) {
#if VELTIS_FIXTURE_MODE == 0
    return VELTIS_NATIVE_CAPABILITY_NONE;
#else
    return VELTIS_NATIVE_CAPABILITY_TRANSFORM_I32 | VELTIS_NATIVE_CAPABILITY_SELF_TEST;
#endif
}

VELTIS_NATIVE_API int32_t veltis_native_self_test(void) {
#if VELTIS_FIXTURE_MODE == 0
    return VELTIS_NATIVE_ERROR_UNSUPPORTED;
#else
    return VELTIS_NATIVE_OK;
#endif
}

#if VELTIS_FIXTURE_MODE != 3
VELTIS_NATIVE_API int32_t veltis_native_transform_i32(
    const int32_t* input,
    size_t input_length,
    int32_t salt,
    int32_t* output,
    size_t output_length) {
    (void)input;
    (void)input_length;
    (void)salt;
    (void)output;
    (void)output_length;
#if VELTIS_FIXTURE_MODE == 2
    return VELTIS_NATIVE_ERROR_INTERNAL;
#else
    return VELTIS_NATIVE_ERROR_UNSUPPORTED;
#endif
}
#endif
