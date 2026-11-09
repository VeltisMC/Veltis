#include "veltis/native/veltis_module.h"

VELTIS_MODULE_API const char *VELTIS_MODULE_CALL veltis_native_module_name(void) {
    return "transform";
}

VELTIS_MODULE_API int32_t VELTIS_MODULE_CALL veltis_native_abi_version(void) {
    return 99;
}
