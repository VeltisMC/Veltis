#include "veltis/native/veltis_native.h"

#include <stdint.h>

#ifndef VELTIS_FIXTURE_ABI
#define VELTIS_FIXTURE_ABI 1
#endif
#ifndef VELTIS_FIXTURE_MAJOR
#define VELTIS_FIXTURE_MAJOR 1
#endif
#ifndef VELTIS_FIXTURE_MINOR
#define VELTIS_FIXTURE_MINOR 0
#endif
#ifndef VELTIS_FIXTURE_MODE
#define VELTIS_FIXTURE_MODE 0
#endif

static int initialized;

VELTIS_NATIVE_API const char *VELTIS_NATIVE_CALL veltis_native_module_name(void) {
    return "transform";
}
VELTIS_NATIVE_API int32_t VELTIS_NATIVE_CALL veltis_native_abi_version(void) {
    return VELTIS_FIXTURE_ABI;
}
VELTIS_NATIVE_API int32_t VELTIS_NATIVE_CALL veltis_native_version_major(void) {
    return VELTIS_FIXTURE_MAJOR;
}
VELTIS_NATIVE_API int32_t VELTIS_NATIVE_CALL veltis_native_version_minor(void) {
    return VELTIS_FIXTURE_MINOR;
}
VELTIS_NATIVE_API int32_t VELTIS_NATIVE_CALL veltis_native_version_patch(void) {
    return 0;
}
VELTIS_NATIVE_API const char *VELTIS_NATIVE_CALL veltis_native_version_string(void) {
    return "1.0.0";
}
VELTIS_NATIVE_API const char *VELTIS_NATIVE_CALL veltis_native_status_string(int32_t status) {
    (void)status;
    return "fixture";
}
VELTIS_NATIVE_API const char *VELTIS_NATIVE_CALL veltis_native_last_error(void) {
#if VELTIS_FIXTURE_MODE == 1
    return "fixture init failure";
#elif VELTIS_FIXTURE_MODE == 2
    return "fixture transform failure";
#else
    return "fixture failure";
#endif
}
VELTIS_NATIVE_API int32_t VELTIS_NATIVE_CALL veltis_native_init(void) {
#if VELTIS_FIXTURE_MODE == 1
    return -5;
#else
    initialized = 1;
    return 0;
#endif
}
VELTIS_NATIVE_API void VELTIS_NATIVE_CALL veltis_native_shutdown(void) {
    initialized = 0;
}
VELTIS_NATIVE_API int32_t VELTIS_NATIVE_CALL veltis_native_is_initialized(void) {
    return initialized;
}
VELTIS_NATIVE_API uint32_t VELTIS_NATIVE_CALL veltis_native_capabilities(void) {
    return VELTIS_MODULE_CAPABILITY_BATCH_I32 | VELTIS_MODULE_CAPABILITY_SELF_TEST;
}
VELTIS_NATIVE_API int32_t VELTIS_NATIVE_CALL veltis_native_self_test(void) {
    return 0;
}

#if VELTIS_FIXTURE_MODE != 3
VELTIS_NATIVE_API int32_t VELTIS_NATIVE_CALL veltis_native_transform_i32(
        const int32_t *input, size_t input_length, int32_t salt,
        int32_t *output, size_t output_length) {
    (void)input;
    (void)input_length;
    (void)salt;
    (void)output;
    (void)output_length;
#if VELTIS_FIXTURE_MODE == 2
    return -5;
#else
    return 0;
#endif
}
#endif
