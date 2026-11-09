#ifndef VELTIS_NATIVE_VELTIS_NATIVE_H
#define VELTIS_NATIVE_VELTIS_NATIVE_H

#include "veltis/native/veltis_module.h"

#include <stddef.h>
#include <stdint.h>

#define VELTIS_NATIVE_ABI_VERSION VELTIS_MODULE_ABI_VERSION
#define VELTIS_NATIVE_VERSION_MAJOR 1
#define VELTIS_NATIVE_VERSION_MINOR 0
#define VELTIS_NATIVE_VERSION_PATCH 0
#define VELTIS_NATIVE_VERSION_STRING "1.0.0"
#define VELTIS_NATIVE_API VELTIS_MODULE_API
#define VELTIS_NATIVE_CALL VELTIS_MODULE_CALL

VELTIS_NATIVE_API const char *VELTIS_NATIVE_CALL veltis_native_module_name(void);
VELTIS_NATIVE_API int32_t VELTIS_NATIVE_CALL veltis_native_abi_version(void);
VELTIS_NATIVE_API int32_t VELTIS_NATIVE_CALL veltis_native_version_major(void);
VELTIS_NATIVE_API int32_t VELTIS_NATIVE_CALL veltis_native_version_minor(void);
VELTIS_NATIVE_API int32_t VELTIS_NATIVE_CALL veltis_native_version_patch(void);
VELTIS_NATIVE_API const char *VELTIS_NATIVE_CALL veltis_native_version_string(void);
VELTIS_NATIVE_API const char *VELTIS_NATIVE_CALL veltis_native_status_string(int32_t status);
VELTIS_NATIVE_API const char *VELTIS_NATIVE_CALL veltis_native_last_error(void);
VELTIS_NATIVE_API int32_t VELTIS_NATIVE_CALL veltis_native_init(void);
VELTIS_NATIVE_API void VELTIS_NATIVE_CALL veltis_native_shutdown(void);
VELTIS_NATIVE_API int32_t VELTIS_NATIVE_CALL veltis_native_is_initialized(void);
VELTIS_NATIVE_API uint32_t VELTIS_NATIVE_CALL veltis_native_capabilities(void);
VELTIS_NATIVE_API int32_t VELTIS_NATIVE_CALL veltis_native_self_test(void);
VELTIS_NATIVE_API int32_t VELTIS_NATIVE_CALL veltis_native_transform_i32(
        const int32_t *input,
        size_t input_length,
        int32_t salt,
        int32_t *output,
        size_t output_length);

#endif
