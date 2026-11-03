#include "veltis/native/veltis_native.hpp"

#include <bit>

namespace veltis::native {

void transform_i32(
    const std::int32_t* input,
    std::size_t count,
    std::int32_t salt,
    std::int32_t* output) noexcept {
    const auto salt_bits = static_cast<std::uint32_t>(salt);
    for (std::size_t i = 0; i < count; ++i) {
        const auto value = static_cast<std::uint32_t>(input[i]);
        // The contract defines the index term modulo 2^32, matching Java's int
        // loop counter. The cast is a defined truncation, not a pointer-width
        // assumption; a buffer with more than 2^32 elements cannot be addressed
        // by the matching Java int[] anyway.
        const auto index = static_cast<std::uint32_t>(i);
        // Unsigned multiplication and addition wrap modulo 2^32 by definition.
        // Java's int arithmetic has the same two's-complement result.
        const auto mixed = value * 31u + salt_bits + index;
        // bit_cast makes the bit-preserving uint32_t -> int32_t conversion
        // explicit on every compiler, including pre-C++20 library modes.
        output[i] = std::bit_cast<std::int32_t>(mixed);
    }
}

} // namespace veltis::native
