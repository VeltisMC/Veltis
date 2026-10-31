/*
 * Internal bridge between the C utility layer and the C++ engine.
 *
 * These symbols are intentionally not in the public header and are not exported
 * from the shared library: visibility is hidden by CMake, and no
 * VELTIS_NATIVE_API annotation is applied.
 */
#ifndef VELTIS_NATIVE_NATIVE_INTERNAL_H
#define VELTIS_NATIVE_NATIVE_INTERNAL_H

#ifdef __cplusplus
extern "C" {
#endif

void veltis_native_set_last_error(const char* message);
void veltis_native_clear_last_error(void);

#ifdef __cplusplus
} /* extern "C" */
#endif

#endif /* VELTIS_NATIVE_NATIVE_INTERNAL_H */
