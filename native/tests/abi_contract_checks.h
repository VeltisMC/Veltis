/*
 * Compile-time ABI assertions shared by the C and C++ contract tests.
 *
 * The public header already pins the widths it depends on, but it can only pin
 * what it uses. These assertions pin what the *contract* promises, and the
 * signature pins below turn any change to an exported parameter, return type or
 * const-ness into a compile error rather than a runtime surprise in a server.
 *
 * This file is included by exactly two translation units:
 * abi_contract_c.c (compiled as C11) and abi_contract_cxx.cpp (compiled as
 * C++20). Both must accept the same header and the same assertions, which is
 * what proves the header is valid C and valid C++ at once.
 */
#ifndef VELTIS_NATIVE_ABI_CONTRACT_CHECKS_H
#define VELTIS_NATIVE_ABI_CONTRACT_CHECKS_H

#include "veltis/native/veltis_native.h"

#include <stdint.h>
#include <stddef.h>

#if defined(__cplusplus)
#  define ABI_ASSERT(condition, message) static_assert(condition, message)
#  define ABI_ALIGNOF(type) alignof(type)
#else
#  define ABI_ASSERT(condition, message) _Static_assert(condition, message)
#  define ABI_ALIGNOF(type) _Alignof(type)
#endif

/* ------------------------------------------------------------------ */
/* 1. Integer widths the ABI is defined in terms of                    */
/* ------------------------------------------------------------------ */

ABI_ASSERT(CHAR_BIT == 8, "the ABI is defined on 8-bit bytes");
ABI_ASSERT(sizeof(int8_t) == 1, "int8_t must be one byte");
ABI_ASSERT(sizeof(int16_t) == 2, "int16_t must be two bytes");
ABI_ASSERT(sizeof(int32_t) == 4, "int32_t must be four bytes");
ABI_ASSERT(sizeof(uint32_t) == 4, "uint32_t must be four bytes");
ABI_ASSERT(sizeof(int64_t) == 8, "int64_t must be eight bytes");
ABI_ASSERT(sizeof(uint64_t) == 8, "uint64_t must be eight bytes");
ABI_ASSERT(sizeof(int32_t) == sizeof(uint32_t),
           "the transform's signed and unsigned element types must be the same size");

/* A length is a size_t; a status and a salt are int32_t. */
ABI_ASSERT(sizeof(size_t) >= sizeof(uint32_t), "size_t must hold an element count");
ABI_ASSERT(sizeof(uintptr_t) == sizeof(void*), "uintptr_t must hold a pointer");

/* ------------------------------------------------------------------ */
/* 2. Alignment of the types the ABI exposes                            */
/* ------------------------------------------------------------------ */

/*
 * There is no struct, union or array type in this ABI: every parameter is a
 * scalar, a pointer to a scalar, or a string. So the layout question the
 * contract has to answer is not "what is the offset of field N" but "does a
 * buffer the caller aligned for int32_t satisfy the implementation's
 * natural-alignment requirement". Both sides of that are pinned here, and
 * native_engine.cpp enforces the same predicate at runtime.
 */
ABI_ASSERT(ABI_ALIGNOF(int32_t) == 4, "int32_t alignment is part of the buffer contract");
ABI_ASSERT(ABI_ALIGNOF(uint32_t) == 4, "uint32_t must share int32_t's alignment");
ABI_ASSERT(ABI_ALIGNOF(int32_t) <= ABI_ALIGNOF(void*),
           "a pointer-aligned allocation must be able to hold an aligned int32_t");
ABI_ASSERT(sizeof(void*) >= 4, "the ABI requires at least 32-bit pointers");
ABI_ASSERT(sizeof(size_t) == sizeof(void*) || sizeof(size_t) == 4,
           "size_t is either pointer-width or 32-bit; anything else is a new platform decision");

/*
 * If a future capability introduces an aggregate, it needs a documented field
 * order, an explicit padding rule and a size assertion in this file. A struct
 * that no test pins is a struct whose layout nobody owns.
 */

/* ------------------------------------------------------------------ */
/* 3. Enum values are part of the ABI                                   */
/* ------------------------------------------------------------------ */

ABI_ASSERT(VELTIS_NATIVE_OK == 0, "OK must remain zero: callers test for success against 0");
ABI_ASSERT(VELTIS_NATIVE_ERROR_INVALID_ARGUMENT == -1, "status codes are frozen");
ABI_ASSERT(VELTIS_NATIVE_ERROR_BUFFER_TOO_SMALL == -2, "status codes are frozen");
ABI_ASSERT(VELTIS_NATIVE_ERROR_NOT_INITIALIZED == -3, "status codes are frozen");
ABI_ASSERT(VELTIS_NATIVE_ERROR_UNSUPPORTED == -4, "status codes are frozen");
ABI_ASSERT(VELTIS_NATIVE_ERROR_INTERNAL == -5, "status codes are frozen");
ABI_ASSERT(VELTIS_NATIVE_ERROR_LENGTH_MISMATCH == -6, "status codes are frozen");

ABI_ASSERT(VELTIS_NATIVE_CAPABILITY_NONE == 0u, "the empty capability set is zero");
ABI_ASSERT(VELTIS_NATIVE_CAPABILITY_TRANSFORM_I32 == (1u << 0), "capability bits are frozen");
ABI_ASSERT(VELTIS_NATIVE_CAPABILITY_SELF_TEST == (1u << 1), "capability bits are frozen");

ABI_ASSERT(VELTIS_NATIVE_ABI_VERSION == 1, "ABI version 1 is the frozen contract");
ABI_ASSERT(VELTIS_NATIVE_VERSION_MAJOR == 1, "the engine major version must match the loader's policy");

/* Booleans and statuses are int32_t, never _Bool, so the enum must fit. */
ABI_ASSERT(sizeof(int32_t) >= sizeof(veltis_native_status),
           "a status returned as int32_t must be able to carry every enum value");
ABI_ASSERT(sizeof(uint32_t) >= sizeof(veltis_native_capability),
           "the capability mask is returned as uint32_t");

/* ------------------------------------------------------------------ */
/* 4. Exported signatures pinned as function-pointer types              */
/* ------------------------------------------------------------------ */

/*
 * Each typedef below is the signature the contract documents. Assigning the
 * address of the export to a pointer of that type is checked at compile time,
 * so a changed parameter, a dropped const, a different return type, or a
 * different calling convention on 32-bit Windows fails the build. That is the
 * only ABI test that can run before a library exists.
 */

typedef int32_t (VELTIS_NATIVE_CALL *abi_void_to_i32)(void);
typedef void (VELTIS_NATIVE_CALL *abi_void_to_void)(void);
typedef uint32_t (VELTIS_NATIVE_CALL *abi_void_to_u32)(void);
typedef const char* (VELTIS_NATIVE_CALL *abi_void_to_str)(void);
typedef const char* (VELTIS_NATIVE_CALL *abi_i32_to_str)(int32_t status);
typedef int32_t (VELTIS_NATIVE_CALL *abi_transform)(
    const int32_t* input,
    size_t input_length,
    int32_t salt,
    int32_t* output,
    size_t output_length);

/*
 * The assignments above are the test: an incompatible signature is a compile
 * error, so nothing here has to be evaluated at run time. The casts exist only so
 * no compiler can argue the pins are dead and delete them; comparing a function
 * address against null would be a check that can never fail, and the compiler
 * says so (-Waddress).
 */
static int abi_pin_all_signatures(void) {
    const abi_void_to_i32 abi_version = &veltis_native_abi_version;
    const abi_void_to_i32 version_major = &veltis_native_version_major;
    const abi_void_to_i32 version_minor = &veltis_native_version_minor;
    const abi_void_to_i32 version_patch = &veltis_native_version_patch;
    const abi_void_to_str version_string = &veltis_native_version_string;
    const abi_i32_to_str status_string = &veltis_native_status_string;
    const abi_void_to_str last_error = &veltis_native_last_error;
    const abi_void_to_i32 init = &veltis_native_init;
    const abi_void_to_void shutdown = &veltis_native_shutdown;
    const abi_void_to_i32 is_initialized = &veltis_native_is_initialized;
    const abi_void_to_u32 capabilities = &veltis_native_capabilities;
    const abi_void_to_i32 self_test = &veltis_native_self_test;
    const abi_transform transform_i32 = &veltis_native_transform_i32;

    (void)abi_version;
    (void)version_major;
    (void)version_minor;
    (void)version_patch;
    (void)version_string;
    (void)status_string;
    (void)last_error;
    (void)init;
    (void)shutdown;
    (void)is_initialized;
    (void)capabilities;
    (void)self_test;
    (void)transform_i32;
    return 1;
}

#endif /* VELTIS_NATIVE_ABI_CONTRACT_CHECKS_H */
