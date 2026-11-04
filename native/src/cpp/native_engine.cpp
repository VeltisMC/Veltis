#include "veltis/native/veltis_native.hpp"
#include "veltis/native/veltis_native.h"
#include "native_internal.h"
#include "native_buffer_checks.hpp"

#include <array>
#include <cstddef>
#include <cstdint>

static_assert(sizeof(std::int32_t) == 4, "int32_t must be exactly 32 bits");
static_assert(sizeof(std::uint32_t) == 4, "uint32_t must be exactly 32 bits");
static_assert(sizeof(std::uintptr_t) == sizeof(void*), "uintptr_t must hold a pointer");
static_assert(std::atomic<bool>::is_always_lock_free,
              "the process-wide lifecycle flag must not require a hidden runtime lock");

namespace veltis::native {

Engine& Engine::instance() noexcept {
    static Engine engine;
    return engine;
}

void Engine::initialize() noexcept {
    initialized_.store(true, std::memory_order_release);
}

void Engine::shutdown() noexcept {
    initialized_.store(false, std::memory_order_release);
}

bool Engine::initialized() const noexcept {
    return initialized_.load(std::memory_order_acquire);
}

std::uint32_t Engine::capabilities() const noexcept {
    return kCapabilities;
}

bool Engine::self_test() const noexcept {
    constexpr std::array<std::int32_t, 4> input = {0, 1, -1, 123456789};
    constexpr std::array<std::int32_t, 4> expected = {17, 49, -12, -467806817};
    std::array<std::int32_t, 4> output = {};

    transform_i32(input.data(), input.size(), 17, output.data());

    for (std::size_t i = 0; i < expected.size(); ++i) {
        if (output[i] != expected[i]) {
            return false;
        }
    }
    return capabilities() == kCapabilities;
}

} // namespace veltis::native

namespace {

/*
 * The three buffer predicates live in native_buffer_checks.hpp so that the next
 * pointer-and-length export reuses them instead of rewriting the arithmetic.
 * Every boundary check in this library must fail the same way.
 */
using veltis::native::detail::byte_ranges_overlap;
using veltis::native::detail::checked_byte_extent;
using veltis::native::detail::naturally_aligned;

} // namespace

extern "C" {

VELTIS_NATIVE_API int32_t VELTIS_NATIVE_CALL veltis_native_init(void) {
    try {
        veltis::native::Engine::instance().initialize();
        veltis_native_clear_last_error();
        return VELTIS_NATIVE_OK;
    } catch (...) {
        veltis_native_set_last_error("native engine initialization threw");
        return VELTIS_NATIVE_ERROR_INTERNAL;
    }
}

VELTIS_NATIVE_API int32_t VELTIS_NATIVE_CALL veltis_native_is_initialized(void) {
    try {
        return veltis::native::Engine::instance().initialized() ? 1 : 0;
    } catch (...) {
        // The header promises no export throws into a caller, and a Java
        // downcall has no stack to unwind into.
        veltis_native_set_last_error("native engine state query threw");
        return 0;
    }
}

VELTIS_NATIVE_API void VELTIS_NATIVE_CALL veltis_native_shutdown(void) {
    try {
        veltis::native::Engine::instance().shutdown();
        veltis_native_clear_last_error();
    } catch (...) {
        veltis_native_set_last_error("native engine shutdown threw");
    }
}

VELTIS_NATIVE_API uint32_t VELTIS_NATIVE_CALL veltis_native_capabilities(void) {
    try {
        return veltis::native::Engine::instance().capabilities();
    } catch (...) {
        veltis_native_set_last_error("native capability query threw");
        return VELTIS_NATIVE_CAPABILITY_NONE;
    }
}

VELTIS_NATIVE_API int32_t VELTIS_NATIVE_CALL veltis_native_self_test(void) {
    try {
        if (!veltis::native::Engine::instance().initialized()) {
            veltis_native_set_last_error("self-test requires veltis_native_init()");
            return VELTIS_NATIVE_ERROR_NOT_INITIALIZED;
        }
        if (!veltis::native::Engine::instance().self_test()) {
            veltis_native_set_last_error("native engine self-test failed");
            return VELTIS_NATIVE_ERROR_INTERNAL;
        }
        veltis_native_clear_last_error();
        return VELTIS_NATIVE_OK;
    } catch (...) {
        veltis_native_set_last_error("native engine self-test threw");
        return VELTIS_NATIVE_ERROR_INTERNAL;
    }
}

VELTIS_NATIVE_API int32_t VELTIS_NATIVE_CALL veltis_native_transform_i32(
    const int32_t* input,
    size_t input_length,
    int32_t salt,
    int32_t* output,
    size_t output_length) {
    // The initialisation guard is queried before the argument checks, in the same
    // order as before, but inside a handler: Engine::instance() constructs a
    // static-local object and any exception escaping here would land in a Java
    // downcall, where there is no stack to unwind into.
    bool engine_ready = false;
    try {
        engine_ready = veltis::native::Engine::instance().initialized();
    } catch (...) {
        veltis_native_set_last_error("native engine state query threw");
        return VELTIS_NATIVE_ERROR_INTERNAL;
    }
    if (!engine_ready) {
        veltis_native_set_last_error("transform requires veltis_native_init()");
        return VELTIS_NATIVE_ERROR_NOT_INITIALIZED;
    }
    if (input_length != output_length) {
        veltis_native_set_last_error("input_length and output_length must match");
        return VELTIS_NATIVE_ERROR_LENGTH_MISMATCH;
    }
    if (input_length == 0) {
        veltis_native_clear_last_error();
        return VELTIS_NATIVE_OK;
    }
    if (input == NULL) {
        veltis_native_set_last_error("input buffer is null");
        return VELTIS_NATIVE_ERROR_INVALID_ARGUMENT;
    }
    if (output == NULL) {
        veltis_native_set_last_error("output buffer is null");
        return VELTIS_NATIVE_ERROR_INVALID_ARGUMENT;
    }

    std::size_t byte_extent = 0;
    if (!checked_byte_extent<std::int32_t>(input_length, byte_extent)) {
        veltis_native_set_last_error("input_length is too large for a byte extent");
        return VELTIS_NATIVE_ERROR_INVALID_ARGUMENT;
    }
    if (!naturally_aligned<std::int32_t>(input)) {
        veltis_native_set_last_error("input buffer is not aligned for int32_t");
        return VELTIS_NATIVE_ERROR_INVALID_ARGUMENT;
    }
    if (!naturally_aligned<std::int32_t>(output)) {
        veltis_native_set_last_error("output buffer is not aligned for int32_t");
        return VELTIS_NATIVE_ERROR_INVALID_ARGUMENT;
    }
    if (byte_ranges_overlap(input, byte_extent, output, byte_extent)) {
        veltis_native_set_last_error("input and output buffers must not overlap");
        return VELTIS_NATIVE_ERROR_INVALID_ARGUMENT;
    }

    try {
        veltis::native::transform_i32(input, input_length, salt, output);
        veltis_native_clear_last_error();
        return VELTIS_NATIVE_OK;
    } catch (...) {
        veltis_native_set_last_error("native transform threw");
        return VELTIS_NATIVE_ERROR_INTERNAL;
    }
}

} // extern "C"
