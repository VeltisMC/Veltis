#include "veltis/native/veltis_native.h"
#include "native_internal.h"

#include <stdio.h>
#include <string.h>

#if defined(_MSC_VER)
#  define VELTIS_THREAD_LOCAL __declspec(thread)
#else
#  define VELTIS_THREAD_LOCAL _Thread_local
#endif

#define VELTIS_LAST_ERROR_CAPACITY 256

static VELTIS_THREAD_LOCAL char veltis_last_error[VELTIS_LAST_ERROR_CAPACITY] = "";

void veltis_native_set_last_error(const char* message) {
    if (message == NULL) {
        veltis_last_error[0] = '\0';
        return;
    }
    (void)snprintf(veltis_last_error, sizeof(veltis_last_error), "%s", message);
}

void veltis_native_clear_last_error(void) {
    veltis_last_error[0] = '\0';
}

VELTIS_NATIVE_API int32_t veltis_native_abi_version(void) {
    return VELTIS_NATIVE_ABI_VERSION;
}

VELTIS_NATIVE_API int32_t veltis_native_version_major(void) {
    return VELTIS_NATIVE_VERSION_MAJOR;
}

VELTIS_NATIVE_API int32_t veltis_native_version_minor(void) {
    return VELTIS_NATIVE_VERSION_MINOR;
}

VELTIS_NATIVE_API int32_t veltis_native_version_patch(void) {
    return VELTIS_NATIVE_VERSION_PATCH;
}

VELTIS_NATIVE_API const char* veltis_native_version_string(void) {
    return VELTIS_NATIVE_VERSION_STRING;
}

VELTIS_NATIVE_API const char* veltis_native_status_string(int32_t status) {
    switch (status) {
        case VELTIS_NATIVE_OK:
            return "ok";
        case VELTIS_NATIVE_ERROR_INVALID_ARGUMENT:
            return "invalid argument";
        case VELTIS_NATIVE_ERROR_BUFFER_TOO_SMALL:
            return "buffer too small";
        case VELTIS_NATIVE_ERROR_NOT_INITIALIZED:
            return "not initialized";
        case VELTIS_NATIVE_ERROR_UNSUPPORTED:
            return "unsupported operation";
        case VELTIS_NATIVE_ERROR_INTERNAL:
            return "internal error";
        case VELTIS_NATIVE_ERROR_LENGTH_MISMATCH:
            return "input/output length mismatch";
        default:
            return "unknown status";
    }
}

VELTIS_NATIVE_API const char* veltis_native_last_error(void) {
    return veltis_last_error;
}
