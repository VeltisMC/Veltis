#include "veltis/native/veltis_native.hpp"

namespace veltis::native {

void transform_i32(
    const std::int32_t* input,
    std::size_t count,
    std::int32_t salt,
    std::int32_t* output) noexcept {
    const auto salt_bits = static_cast<std::uint32_t>(salt);
    for (std::size_t i = 0; i < count; ++i) {
        const auto value = static_cast<std::uint32_t>(input[i]);
        const auto index = static_cast<std::uint32_t>(i);
        // Unsigned multiplication and addition wrap modulo 2^32 by definition.
        // Java's int arithmetic has the same two's-complement result.
        const auto mixed = value * 31u + salt_bits + index;
        output[i] = static_cast<std::int32_t>(mixed);
    }
}

} // namespace veltis::native
