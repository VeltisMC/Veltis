#include "transform.h"
#include "veltis/native/veltis_native.h"
#include "veltis/native/veltis_module.h"

#include <stdatomic.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

static atomic_bool initialized = ATOMIC_VAR_INIT(false);
#if defined(_MSC_VER)
static __declspec(thread) char last_error[256];
#else
static _Thread_local char last_error[256];
#endif

static void set_error(const char *message) {
    if (message == NULL) {
        last_error[0] = '\0';
    } else {
        (void)snprintf(last_error, sizeof(last_error), "%s", message);
    }
}

VELTIS_MODULE_API const char *VELTIS_MODULE_CALL veltis_native_module_name(void) {
    return "transform";
}

VELTIS_MODULE_API int32_t VELTIS_MODULE_CALL veltis_native_abi_version(void) {
    return VELTIS_NATIVE_ABI_VERSION;
}

VELTIS_MODULE_API int32_t VELTIS_MODULE_CALL veltis_native_version_major(void) {
    return VELTIS_NATIVE_VERSION_MAJOR;
}

VELTIS_MODULE_API int32_t VELTIS_MODULE_CALL veltis_native_version_minor(void) {
    return VELTIS_NATIVE_VERSION_MINOR;
}

VELTIS_MODULE_API int32_t VELTIS_MODULE_CALL veltis_native_version_patch(void) {
    return VELTIS_NATIVE_VERSION_PATCH;
}

VELTIS_MODULE_API const char *VELTIS_MODULE_CALL veltis_native_version_string(void) {
    return VELTIS_NATIVE_VERSION_STRING;
}

VELTIS_MODULE_API int32_t VELTIS_MODULE_CALL veltis_native_init(void) {
    atomic_store_explicit(&initialized, true, memory_order_release);
    set_error(NULL);
    return 0;
}

VELTIS_MODULE_API void VELTIS_MODULE_CALL veltis_native_shutdown(void) {
    atomic_store_explicit(&initialized, false, memory_order_release);
    set_error(NULL);
}

VELTIS_MODULE_API int32_t VELTIS_MODULE_CALL veltis_native_is_initialized(void) {
    return atomic_load_explicit(&initialized, memory_order_acquire) ? 1 : 0;
}

VELTIS_MODULE_API uint32_t VELTIS_MODULE_CALL veltis_native_capabilities(void) {
    return VELTIS_MODULE_CAPABILITY_SELF_TEST | VELTIS_MODULE_CAPABILITY_BATCH_I32;
}

VELTIS_MODULE_API int32_t VELTIS_MODULE_CALL veltis_native_self_test(void) {
    static const int32_t input[] = {0, 1, -1, 123456789};
    static const int32_t expected[] = {17, 49, -12, -467806817};
    int32_t output[sizeof(input) / sizeof(input[0])] = {0};
    size_t i;

    if (!veltis_native_is_initialized()) {
        set_error("self-test requires initialization");
        return -3;
    }
    if (veltis_transform_apply(input, 4, 17, output, 4) != 0) {
        set_error("transform self-test returned an error");
        return -5;
    }
    for (i = 0; i < 4; ++i) {
        if (output[i] != expected[i]) {
            set_error("transform self-test result mismatch");
            return -5;
        }
    }
    set_error(NULL);
    return 0;
}

VELTIS_MODULE_API const char *VELTIS_MODULE_CALL veltis_native_status_string(int32_t status) {
    switch (status) {
        case 0: return "ok";
        case -1: return "invalid argument";
        case -3: return "not initialized";
        case -5: return "internal error";
        case -6: return "input/output length mismatch";
        default: return "unknown status";
    }
}

VELTIS_MODULE_API const char *VELTIS_MODULE_CALL veltis_native_last_error(void) {
    return last_error;
}

VELTIS_MODULE_API int32_t VELTIS_MODULE_CALL veltis_native_transform_i32(
        const int32_t *input,
        size_t input_length,
        int32_t salt,
        int32_t *output,
        size_t output_length) {
    int32_t status;

    if (!veltis_native_is_initialized()) {
        set_error("transform requires initialization");
        return -3;
    }
    status = veltis_transform_apply(input, input_length, salt, output, output_length);
    set_error(status == 0 ? NULL : veltis_native_status_string(status));
    return status;
}
