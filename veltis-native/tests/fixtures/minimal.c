/*
 * A library with the wrong ABI and no other exports. It proves the loader
 * reports the ABI incompatibility rather than a misleading missing-version
 * symbol, even though the library is otherwise unusable.
 */
#include "veltis/native/veltis_native.h"

VELTIS_NATIVE_API int32_t veltis_native_abi_version(void) {
    return 999;
}
