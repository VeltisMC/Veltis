/*
 * VeltisMC native engine - internal C++ surface.
 *
 * This header is part of the native module, not part of the public ABI. The
 * Java loader never sees these types; it only resolves the C functions in
 * veltis_native.h. Nothing here uses a C++ type in an exported signature.
 */
#ifndef VELTIS_NATIVE_VELTIS_NATIVE_HPP
#define VELTIS_NATIVE_VELTIS_NATIVE_HPP

#include "veltis/native/veltis_native.h"

#include <atomic>
#include <cstddef>
#include <cstdint>

namespace veltis::native {

inline constexpr std::int32_t kAbiVersion = VELTIS_NATIVE_ABI_VERSION;
inline constexpr std::uint32_t kCapabilities =
    VELTIS_NATIVE_CAPABILITY_TRANSFORM_I32 | VELTIS_NATIVE_CAPABILITY_SELF_TEST;

/**
 * Deterministic bulk transform shared by the engine and its self-test.
 *
 * Unsigned arithmetic is intentional: it has defined wraparound, and the Java
 * fallback uses the same two's-complement overflow rule.
 */
void transform_i32(
    const std::int32_t* input,
    std::size_t count,
    std::int32_t salt,
    std::int32_t* output) noexcept;

/** Process-wide engine state. All public operations are thread-safe. */
class Engine final {
public:
    static Engine& instance() noexcept;

    Engine(const Engine&) = delete;
    Engine& operator=(const Engine&) = delete;

    void initialize() noexcept;
    void shutdown() noexcept;

    [[nodiscard]] bool initialized() const noexcept;
    [[nodiscard]] std::uint32_t capabilities() const noexcept;
    [[nodiscard]] bool self_test() const noexcept;

private:
    Engine() = default;

    std::atomic<bool> initialized_{false};
};

} // namespace veltis::native

#endif // VELTIS_NATIVE_VELTIS_NATIVE_HPP
