#include "veltis/native/veltis_native.h"
#include "native_internal.h"

#include <stddef.h>
#include <stdio.h>
#include <string.h>

/*
 * C11 thread-local storage is the portable spelling. MSVC (including clang-cl,
 * which defines _MSC_VER) uses its __declspec equivalent so the library does
 * not depend on MSVC's C11 runtime support. Both forms are per-thread in a DLL
 * on Windows Vista and newer, which covers every supported Windows target.
 */
#if defined(_MSC_VER)
#  define VELTIS_THREAD_LOCAL __declspec(thread)
#elif defined(__STDC_VERSION__) && __STDC_VERSION__ >= 201112L
#  define VELTIS_THREAD_LOCAL _Thread_local
#else
#  error "VeltisMC native engine requires C11 thread-local storage"
#endif

#define VELTIS_LAST_ERROR_CAPACITY 256

static VELTIS_THREAD_LOCAL char veltis_last_error[VELTIS_LAST_ERROR_CAPACITY] = "";

/* These are the assumptions the public header documents. */
_Static_assert(VELTIS_NATIVE_ABI_VERSION == 1, "the compiled ABI version must match the header");
_Static_assert(VELTIS_NATIVE_OK == 0, "VELTIS_NATIVE_OK must remain zero");
_Static_assert(VELTIS_NATIVE_ERROR_LENGTH_MISMATCH == -6, "status codes are part of the ABI");
_Static_assert(VELTIS_NATIVE_CAPABILITY_TRANSFORM_I32 == 1u, "capability bits are part of the ABI");
_Static_assert(VELTIS_NATIVE_CAPABILITY_SELF_TEST == 2u, "capability bits are part of the ABI");
_Static_assert(sizeof(size_t) >= sizeof(uint32_t), "size_t must hold an element count");

void veltis_native_set_last_error(const char* message) {
    if (message == NULL) {
        veltis_last_error[0] = '\0';
        return;
    }
    /* Avoid the undefined overlap case if a caller ever passes this slot back. */
    if (message == veltis_last_error) {
        return;
    }
    (void)snprintf(veltis_last_error, sizeof(veltis_last_error), "%s", message);
}

void veltis_native_clear_last_error(void) {
    veltis_last_error[0] = '\0';
}

VELTIS_NATIVE_API int32_t VELTIS_NATIVE_CALL veltis_native_abi_version(void) {
    return VELTIS_NATIVE_ABI_VERSION;
}

VELTIS_NATIVE_API int32_t VELTIS_NATIVE_CALL veltis_native_version_major(void) {
    return VELTIS_NATIVE_VERSION_MAJOR;
}

VELTIS_NATIVE_API int32_t VELTIS_NATIVE_CALL veltis_native_version_minor(void) {
    return VELTIS_NATIVE_VERSION_MINOR;
}

VELTIS_NATIVE_API int32_t VELTIS_NATIVE_CALL veltis_native_version_patch(void) {
    return VELTIS_NATIVE_VERSION_PATCH;
}

VELTIS_NATIVE_API const char* VELTIS_NATIVE_CALL veltis_native_version_string(void) {
    return VELTIS_NATIVE_VERSION_STRING;
}

VELTIS_NATIVE_API const char* VELTIS_NATIVE_CALL veltis_native_status_string(int32_t status) {
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

VELTIS_NATIVE_API const char* VELTIS_NATIVE_CALL veltis_native_last_error(void) {
    return veltis_last_error;
}
