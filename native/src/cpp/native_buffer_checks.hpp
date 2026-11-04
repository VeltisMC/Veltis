/*
 * Buffer safety predicates shared by the C ABI implementations.
 *
 * These are the three questions every pointer-and-length export has to answer
 * before it touches a caller's memory: can this count be turned into a byte
 * extent without wrapping, is this buffer aligned for its element type, and do
 * these two ranges overlap. They live in a header so a second capability reuses
 * the same predicates instead of writing its own arithmetic, which is where a
 * boundary check normally starts to differ from the first one.
 *
 * Nothing here allocates. The native library owns no memory: every buffer is the
 * caller's, which is the rule documented in docs/MEMORY_MODEL.md.
 */
#ifndef VELTIS_NATIVE_BUFFER_CHECKS_HPP
#define VELTIS_NATIVE_BUFFER_CHECKS_HPP

#include <cstddef>
#include <cstdint>
#include <limits>
#include <type_traits>

namespace veltis::native::detail {

/** Byte extent of `count` elements, or false when the multiplication would wrap. */
template <typename T>
[[nodiscard]] constexpr bool checked_byte_extent(std::size_t count, std::size_t& bytes) noexcept {
    static_assert(sizeof(T) > 0, "an element type is required");
    constexpr std::size_t element_size = sizeof(T);
    if (element_size != 0U && count > (std::numeric_limits<std::size_t>::max)() / element_size) {
        return false;
    }
    bytes = count * element_size;
    return true;
}

/** True when `pointer` is aligned for `T`. A null pointer is aligned by definition. */
template <typename T>
[[nodiscard]] constexpr bool naturally_aligned(const void* pointer) noexcept {
    static_assert(alignof(T) > 0, "alignment must be positive");
    if (pointer == nullptr) {
        return true;
    }
    return (reinterpret_cast<std::uintptr_t>(pointer) % alignof(T)) == 0U;
}

/*
 * Comparing unrelated pointers with < is not defined by C++, so the ranges are
 * compared as integer addresses. An extent that wraps the address space is
 * treated as overlapping — that is, as invalid — rather than as non-overlapping.
 * Two ranges that merely touch (one ends where the other begins) do not overlap.
 */
[[nodiscard]] inline bool byte_ranges_overlap(const void* first, std::size_t first_bytes,
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

} // namespace veltis::native::detail

#endif /* VELTIS_NATIVE_BUFFER_CHECKS_HPP */
