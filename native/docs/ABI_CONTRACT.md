# ABI contract

The native boundary is a C ABI with fixed-width integer types, `size_t`, plain
pointers, and NUL-terminated strings. No Java or server type crosses it. The
public declarations are in `include/veltis/native/veltis_native.h`; shared
module constants and platform export macros are in `veltis_module.h`.

## Required exports

Every loadable module must export:

- `veltis_native_module_name`
- `veltis_native_abi_version`
- `veltis_native_version_major`, `veltis_native_version_minor`,
  `veltis_native_version_patch`, and `veltis_native_version_string`
- `veltis_native_init`, `veltis_native_shutdown`, and
  `veltis_native_is_initialized`
- `veltis_native_capabilities` and `veltis_native_self_test`
- `veltis_native_status_string` and `veltis_native_last_error`

The loader checks the requested module name and ABI before accepting the
library, then resolves the remaining required exports. ABI versions must match
exactly; the engine major must match and newer minor versions are accepted.

## Transform operation

The current `transform` module additionally exports:

```c
int32_t veltis_native_transform_i32(
    const int32_t *input,
    size_t input_length,
    int32_t salt,
    int32_t *output,
    size_t output_length);
```

Lengths are element counts. The module validates lengths and pointers before
access. Empty input is a successful no-op. The output buffer must hold at
least the input length. The deterministic operation is implemented by
`modules/transform/transform.c`.

## Ownership and errors

Java owns FFM arenas and closes them after each call. Module-owned error strings
are borrowed and must not be freed. A module returns a status code; it must not
let a failure escape the C ABI. Callers must not use a module's symbols after
its `LoadedLibrary` is closed.
