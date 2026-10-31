/*
 * VeltisMC native engine - public C ABI.
 *
 * This is the only header a native consumer (or the Java FFM loader, which
 * resolves the symbols by name) is allowed to depend on. C++ types, exceptions,
 * STL containers and the internal engine class deliberately never cross this
 * boundary: every export below is a plain C function with a fixed-width
 * integer, pointer or string return.
 *
 * Lifetime and threading
 * ----------------------
 *   - veltis_native_init() and veltis_native_shutdown() are process-wide and
 *     idempotent. Every operation other than the version/status queries
 *     requires a prior successful init and returns
 *     VELTIS_NATIVE_ERROR_NOT_INITIALIZED otherwise.
 *   - The library is thread-safe. Buffers passed to veltis_native_transform_i32
 *     must not overlap.
 *   - veltis_native_last_error() returns a pointer to thread-local storage: it
 *     is valid until the next call on the same thread and must never be freed.
 *   - No exported function throws, calls exit(), or allocates memory the caller
 *     owns.
 *
 * ABI/version policy
 * ------------------
 *   VELTIS_NATIVE_ABI_VERSION changes only for a binary-incompatible change.
 *   The Java integration layer accepts exactly this ABI, requires major 1, and
 *   accepts any minor version at or above its own minimum because additions are
 *   backwards compatible.
 */

#ifndef VELTIS_NATIVE_VELTIS_NATIVE_H
#define VELTIS_NATIVE_VELTIS_NATIVE_H

#include <stddef.h>
#include <stdint.h>

#define VELTIS_NATIVE_ABI_VERSION 1

#define VELTIS_NATIVE_VERSION_MAJOR 1
#define VELTIS_NATIVE_VERSION_MINOR 0
#define VELTIS_NATIVE_VERSION_PATCH 0
#define VELTIS_NATIVE_VERSION_STRING "1.0.0"

#if defined(_WIN32) || defined(__CYGWIN__)
#  if defined(VELTIS_NATIVE_EXPORTS)
#    define VELTIS_NATIVE_API __declspec(dllexport)
#  elif defined(VELTIS_NATIVE_STATIC)
#    define VELTIS_NATIVE_API
#  else
#    define VELTIS_NATIVE_API __declspec(dllimport)
#  endif
#elif defined(__GNUC__) || defined(__clang__)
#  define VELTIS_NATIVE_API __attribute__((visibility("default")))
#else
#  define VELTIS_NATIVE_API
#endif

#if defined(__cplusplus)
extern "C" {
#endif

/** Return codes shared by every operation. Negative values are failures. */
typedef enum veltis_native_status {
    /** The operation completed. */
    VELTIS_NATIVE_OK = 0,
    /** A required pointer was NULL with a non-zero length. */
    VELTIS_NATIVE_ERROR_INVALID_ARGUMENT = -1,
    /** An output extent was smaller than the input it had to receive. */
    VELTIS_NATIVE_ERROR_BUFFER_TOO_SMALL = -2,
    /** The operation requires a successful veltis_native_init() first. */
    VELTIS_NATIVE_ERROR_NOT_INITIALIZED = -3,
    /** The operation is not compiled into this library. */
    VELTIS_NATIVE_ERROR_UNSUPPORTED = -4,
    /** An unexpected internal failure; see veltis_native_last_error(). */
    VELTIS_NATIVE_ERROR_INTERNAL = -5,
    /** input_length and output_length were not equal. */
    VELTIS_NATIVE_ERROR_LENGTH_MISMATCH = -6
} veltis_native_status;

/** Capability bits returned by veltis_native_capabilities(). */
typedef enum veltis_native_capability {
    VELTIS_NATIVE_CAPABILITY_NONE = 0,
    /** veltis_native_transform_i32() is implemented. */
    VELTIS_NATIVE_CAPABILITY_TRANSFORM_I32 = (1u << 0),
    /** veltis_native_self_test() can verify the implementation. */
    VELTIS_NATIVE_CAPABILITY_SELF_TEST = (1u << 1)
} veltis_native_capability;

/* -------------------------------------------------------------------------- */
/* Version and diagnostics                                                    */
/* -------------------------------------------------------------------------- */

/** The ABI version this library implements. */
VELTIS_NATIVE_API int32_t veltis_native_abi_version(void);

/** Engine semantic version components. */
VELTIS_NATIVE_API int32_t veltis_native_version_major(void);
VELTIS_NATIVE_API int32_t veltis_native_version_minor(void);
VELTIS_NATIVE_API int32_t veltis_native_version_patch(void);

/** The version as "major.minor.patch". Static storage; do not free. */
VELTIS_NATIVE_API const char* veltis_native_version_string(void);

/**
 * A human-readable name for a status code. Static storage; do not free.
 * Returns "unknown status" for a value this build does not know.
 */
VELTIS_NATIVE_API const char* veltis_native_status_string(int32_t status);

/**
 * The last error message recorded on the calling thread. Never NULL; the empty
 * string means no error. Valid until the next call on the same thread.
 */
VELTIS_NATIVE_API const char* veltis_native_last_error(void);

/* -------------------------------------------------------------------------- */
/* Lifecycle and capabilities                                                 */
/* -------------------------------------------------------------------------- */

VELTIS_NATIVE_API int32_t veltis_native_init(void);
VELTIS_NATIVE_API void veltis_native_shutdown(void);

/** 1 while the engine is initialized, 0 otherwise. */
VELTIS_NATIVE_API int32_t veltis_native_is_initialized(void);

/** A bit mask of veltis_native_capability values. */
VELTIS_NATIVE_API uint32_t veltis_native_capabilities(void);

/**
 * Runs the library's deterministic self-test. Returns
 * VELTIS_NATIVE_ERROR_NOT_INITIALIZED before init, VELTIS_NATIVE_OK on success.
 */
VELTIS_NATIVE_API int32_t veltis_native_self_test(void);

/* -------------------------------------------------------------------------- */
/* Operations                                                                 */
/* -------------------------------------------------------------------------- */

/**
 * Bulk deterministic int32 transform:
 *
 *   output[i] = (uint32_t)input[i] * 31u + (uint32_t)salt + i   (mod 2^32)
 *
 * The unsigned arithmetic makes the wraparound defined; the Java fallback uses
 * the equivalent two's-complement int arithmetic, so both produce identical
 * bits.
 *
 * @param input          source elements; may be NULL only when input_length == 0
 * @param input_length   number of int32 elements in input
 * @param salt           caller-supplied mixing value
 * @param output         destination elements; must not overlap input
 * @param output_length  must equal input_length
 *
 * @return VELTIS_NATIVE_OK, VELTIS_NATIVE_ERROR_NOT_INITIALIZED,
 *         VELTIS_NATIVE_ERROR_LENGTH_MISMATCH,
 *         VELTIS_NATIVE_ERROR_INVALID_ARGUMENT or VELTIS_NATIVE_ERROR_INTERNAL.
 */
VELTIS_NATIVE_API int32_t veltis_native_transform_i32(
    const int32_t* input,
    size_t input_length,
    int32_t salt,
    int32_t* output,
    size_t output_length);

#if defined(__cplusplus)
} /* extern "C" */
#endif

#endif /* VELTIS_NATIVE_VELTIS_NATIVE_H */
