#include "veltis/native/veltis_native.hpp"
#include "veltis/native/veltis_native.h"
#include "native_internal.h"

#include <array>
#include <cstddef>
#include <cstdint>
#include <limits>

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

constexpr std::size_t kElementSize = sizeof(std::int32_t);

/*
 * Element counts are multiplied by sizeof(int32_t) on the way to a byte extent.
 * Refusing an unrepresentable extent keeps pointer arithmetic well-defined even
 * for a hostile or accidental SIZE_MAX caller.
 */
bool checked_byte_extent(std::size_t count, std::size_t& bytes) noexcept {
    if (count > (std::numeric_limits<std::size_t>::max)() / kElementSize) {
        return false;
    }
    bytes = count * kElementSize;
    return true;
}

bool naturally_aligned(const void* pointer) noexcept {
    return (reinterpret_cast<std::uintptr_t>(pointer) % alignof(std::int32_t)) == 0;
}

/*
 * Comparing unrelated pointers with < is not defined by C++, so the ranges are
 * compared as integer addresses. An extent that wraps the address space is
 * treated as invalid rather than as non-overlapping.
 */
bool byte_ranges_overlap(const void* first, std::size_t first_bytes,
                         const void* second, std::size_t second_bytes) noexcept {
    const auto first_begin = reinterpret_cast<std::uintptr_t>(first);
    const auto second_begin = reinterpret_cast<std::uintptr_t>(second);
    const auto maximum = (std::numeric_limits<std::uintptr_t>::max)();

    if (first_bytes > maximum - first_begin || second_bytes > maximum - second_begin) {
        return true;
    }
    const auto first_end = first_begin + first_bytes;
    const auto second_end = second_begin + second_bytes;
    return first_begin < second_end && second_begin < first_end;
}

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
    return veltis::native::Engine::instance().initialized() ? 1 : 0;
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
    return veltis::native::Engine::instance().capabilities();
}

VELTIS_NATIVE_API int32_t VELTIS_NATIVE_CALL veltis_native_self_test(void) {
    if (!veltis::native::Engine::instance().initialized()) {
        veltis_native_set_last_error("self-test requires veltis_native_init()");
        return VELTIS_NATIVE_ERROR_NOT_INITIALIZED;
    }
    try {
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
    if (!veltis::native::Engine::instance().initialized()) {
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
    if (!checked_byte_extent(input_length, byte_extent)) {
        veltis_native_set_last_error("input_length is too large for a byte extent");
        return VELTIS_NATIVE_ERROR_INVALID_ARGUMENT;
    }
    if (!naturally_aligned(input)) {
        veltis_native_set_last_error("input buffer is not aligned for int32_t");
        return VELTIS_NATIVE_ERROR_INVALID_ARGUMENT;
    }
    if (!naturally_aligned(output)) {
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
