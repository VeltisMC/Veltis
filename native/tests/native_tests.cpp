#include "veltis/native/veltis_native.h"
#include "native_buffer_checks.hpp"

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <latch>
#include <limits>
#include <thread>
#include <vector>

static_assert(sizeof(std::int32_t) == 4, "the ABI is built around 32-bit elements");
static_assert(sizeof(std::uint32_t) == 4, "the ABI is built around 32-bit elements");
static_assert(sizeof(std::uint64_t) == 8, "the ABI reports 64-bit version components");
static_assert(sizeof(std::size_t) >= sizeof(std::uint32_t), "lengths are element counts");

namespace {

int failures = 0;

void check(bool condition, const char* expression, const char* message) {
    if (condition) {
        return;
    }
    ++failures;
    std::fprintf(stderr, "FAIL: %s (%s)\n", message, expression);
}

#define CHECK(condition, message) check((condition), #condition, (message))

std::int32_t reference_value(std::int32_t value, std::int32_t salt, std::size_t index) {
    const auto mixed = static_cast<std::uint32_t>(value) * 31u
        + static_cast<std::uint32_t>(salt)
        + static_cast<std::uint32_t>(index);
    return static_cast<std::int32_t>(mixed);
}

void test_versions() {
    CHECK(veltis_native_abi_version() == VELTIS_NATIVE_ABI_VERSION, "ABI version is the header's version");
    CHECK(veltis_native_version_major() == 1, "engine major version");
    CHECK(veltis_native_version_minor() >= 0, "engine minor version is non-negative");
    CHECK(veltis_native_version_patch() >= 0, "engine patch version is non-negative");
    CHECK(std::strcmp(veltis_native_version_string(), VELTIS_NATIVE_VERSION_STRING) == 0,
          "version string matches the header");
}

void test_status_strings() {
    CHECK(std::strcmp(veltis_native_status_string(VELTIS_NATIVE_OK), "ok") == 0, "ok status text");
    CHECK(std::strcmp(veltis_native_status_string(VELTIS_NATIVE_ERROR_INVALID_ARGUMENT), "invalid argument") == 0,
          "invalid-argument status text");
    CHECK(std::strcmp(veltis_native_status_string(VELTIS_NATIVE_ERROR_BUFFER_TOO_SMALL), "buffer too small") == 0,
          "buffer-too-small status text");
    CHECK(std::strcmp(veltis_native_status_string(VELTIS_NATIVE_ERROR_NOT_INITIALIZED), "not initialized") == 0,
          "not-initialized status text");
    CHECK(std::strcmp(veltis_native_status_string(VELTIS_NATIVE_ERROR_UNSUPPORTED), "unsupported operation") == 0,
          "unsupported status text");
    CHECK(std::strcmp(veltis_native_status_string(VELTIS_NATIVE_ERROR_INTERNAL), "internal error") == 0,
          "internal status text");
    CHECK(std::strcmp(veltis_native_status_string(VELTIS_NATIVE_ERROR_LENGTH_MISMATCH),
                      "input/output length mismatch") == 0, "length-mismatch status text");
    CHECK(std::strcmp(veltis_native_status_string(12345), "unknown status") == 0, "unknown status text");
}

void test_lifecycle_and_capabilities() {
    CHECK(veltis_native_is_initialized() == 0, "engine starts uninitialized");
    CHECK((veltis_native_capabilities() & VELTIS_NATIVE_CAPABILITY_TRANSFORM_I32) != 0,
          "transform capability is compiled in");
    CHECK((veltis_native_capabilities() & VELTIS_NATIVE_CAPABILITY_SELF_TEST) != 0,
          "self-test capability is compiled in");

    CHECK(veltis_native_self_test() == VELTIS_NATIVE_ERROR_NOT_INITIALIZED,
          "self-test refuses to run before initialization");
    CHECK(veltis_native_init() == VELTIS_NATIVE_OK, "init succeeds");
    CHECK(veltis_native_is_initialized() == 1, "init marks the engine initialized");
    CHECK(veltis_native_init() == VELTIS_NATIVE_OK, "init is idempotent");
    CHECK(veltis_native_self_test() == VELTIS_NATIVE_OK, "self-test succeeds after init");
}

void test_transform() {
    const std::int32_t input[] = {0, 1, 2, -1, 2147483647};
    const std::int32_t expected[] = {7, 39, 71, -21, 2147483628};
    std::int32_t output[5] = {};

    CHECK(veltis_native_transform_i32(input, 5, 7, output, 5) == VELTIS_NATIVE_OK,
          "transform succeeds");
    for (std::size_t i = 0; i < 5; ++i) {
        CHECK(output[i] == expected[i], "transformed value matches the contract");
    }

    CHECK(veltis_native_transform_i32(nullptr, 0, 7, nullptr, 0) == VELTIS_NATIVE_OK,
          "zero-length transform does not dereference buffers");

    const std::int32_t one[] = {42};
    std::int32_t one_out[] = {0};
    CHECK(veltis_native_transform_i32(one, 0, 7, one_out, 0) == VELTIS_NATIVE_OK,
          "a zero-length transform may still receive non-null pointers");
}

void test_integer_boundaries() {
    const std::int32_t input[] = {
        std::numeric_limits<std::int32_t>::min(),
        std::numeric_limits<std::int32_t>::max(),
        -1,
        0,
        1,
        -123456789,
        987654321,
    };
    const std::int32_t salts[] = {
        std::numeric_limits<std::int32_t>::min(),
        std::numeric_limits<std::int32_t>::max(),
        -1,
        0,
        1,
    };
    std::int32_t output[sizeof(input) / sizeof(input[0])] = {};

    for (const auto salt : salts) {
        CHECK(veltis_native_transform_i32(input, 7, salt, output, 7) == VELTIS_NATIVE_OK,
              "boundary transform succeeds");
        for (std::size_t i = 0; i < 7; ++i) {
            CHECK(output[i] == reference_value(input[i], salt, i),
                  "boundary value wraps exactly like the Java fallback");
        }
    }
}

void test_large_input() {
    constexpr std::size_t count = 1u << 20;
    constexpr std::int32_t salt = -987654321;
    std::vector<std::int32_t> input(count);
    std::vector<std::int32_t> output(count);

    for (std::size_t i = 0; i < count; ++i) {
        input[i] = static_cast<std::int32_t>((i * 2654435761u) ^ (i >> 7));
    }

    CHECK(veltis_native_transform_i32(input.data(), input.size(), salt, output.data(), output.size()) ==
              VELTIS_NATIVE_OK,
          "large transform succeeds");
    bool all_match = true;
    for (std::size_t i = 0; i < count; ++i) {
        if (output[i] != reference_value(input[i], salt, i)) {
            all_match = false;
            break;
        }
    }
    CHECK(all_match, "large transform matches the reference for every element");
}

void test_invalid_arguments() {
    const std::int32_t input[] = {1, 2, 3, 4};
    std::int32_t output[4] = {};

    CHECK(veltis_native_transform_i32(nullptr, 4, 0, output, 4) ==
              VELTIS_NATIVE_ERROR_INVALID_ARGUMENT,
          "null input is rejected");
    CHECK(veltis_native_last_error()[0] != '\0', "rejected input records an error");

    CHECK(veltis_native_transform_i32(input, 4, 0, nullptr, 4) ==
              VELTIS_NATIVE_ERROR_INVALID_ARGUMENT,
          "null output is rejected");

    CHECK(veltis_native_transform_i32(nullptr, 4, 0, nullptr, 4) ==
              VELTIS_NATIVE_ERROR_INVALID_ARGUMENT,
          "two null buffers are rejected");

    CHECK(veltis_native_transform_i32(input, 4, 0, output, 3) ==
              VELTIS_NATIVE_ERROR_LENGTH_MISMATCH,
          "mismatched lengths are rejected");
    CHECK(veltis_native_transform_i32(input, 3, 0, output, 4) ==
              VELTIS_NATIVE_ERROR_LENGTH_MISMATCH,
          "mismatched lengths are rejected in both directions");
}

void test_buffer_boundaries() {
    const std::int32_t input[] = {-7, 0, 7, 1234};
    constexpr std::int32_t sentinel = 0x5A5A5A5A;
    std::int32_t output[6] = {sentinel, 0, 0, 0, 0, sentinel};

    CHECK(veltis_native_transform_i32(input, 4, 3, output + 1, 4) == VELTIS_NATIVE_OK,
          "transform writes exactly the declared output extent");
    CHECK(output[0] == sentinel, "buffer before the output is untouched");
    CHECK(output[5] == sentinel, "buffer after the output is untouched");
    CHECK(output[1] == -214, "first element matches the contract");
    CHECK(output[4] == 38260, "last element matches the contract");
}

void test_concurrent_transforms() {
    constexpr int thread_count = 8;
    constexpr int rounds = 200;
    constexpr std::size_t count = 4096;
    constexpr std::int32_t salt = 12345;

    CHECK(veltis_native_init() == VELTIS_NATIVE_OK, "init before concurrent transforms");
    std::latch start(thread_count);
    std::atomic<int> mismatches{0};
    std::vector<std::thread> threads;
    threads.reserve(thread_count);

    for (int t = 0; t < thread_count; ++t) {
        threads.emplace_back([t, &start, &mismatches] {
            std::vector<std::int32_t> input(count);
            std::vector<std::int32_t> output(count);
            for (std::size_t i = 0; i < count; ++i) {
                input[i] = static_cast<std::int32_t>(i * 31u + static_cast<unsigned>(t) * 7919u);
            }
            start.arrive_and_wait();
            for (int round = 0; round < rounds; ++round) {
                if (veltis_native_transform_i32(input.data(), input.size(), salt, output.data(), output.size())
                    != VELTIS_NATIVE_OK) {
                    ++mismatches;
                    continue;
                }
                for (std::size_t i = 0; i < count; ++i) {
                    if (output[i] != reference_value(input[i], salt, i)) {
                        ++mismatches;
                        break;
                    }
                }
            }
        });
    }
    for (auto& thread : threads) {
        thread.join();
    }
    CHECK(mismatches.load() == 0, "concurrent transforms are independent and correct");
}

void test_thread_local_last_error() {
    constexpr int thread_count = 4;
    std::latch start(thread_count);
    std::latch reported(thread_count);
    std::atomic<int> mismatches{0};
    std::vector<std::thread> threads;
    threads.reserve(thread_count);

    for (int t = 0; t < thread_count; ++t) {
        threads.emplace_back([t, &start, &reported, &mismatches] {
            std::int32_t output[4] = {};
            const std::int32_t input[4] = {1, 2, 3, 4};
            start.arrive_and_wait();

            // Every thread records its own failure text, then verifies that no
            // other thread can observe it through this thread's last-error slot.
            if (t % 2 == 0) {
                (void)veltis_native_transform_i32(nullptr, 4, 0, output, 4);
            } else {
                (void)veltis_native_transform_i32(input, 4, 0, output, 3);
            }
            reported.arrive_and_wait();
            if (t % 2 == 0) {
                if (std::strstr(veltis_native_last_error(), "input buffer is null") == nullptr) {
                    ++mismatches;
                }
            } else {
                if (std::strstr(veltis_native_last_error(), "must match") == nullptr) {
                    ++mismatches;
                }
            }
        });
    }
    for (auto& thread : threads) {
        thread.join();
    }
    CHECK(mismatches.load() == 0, "last-error text is thread-local");
}

void test_null_and_zero_length_matrix() {
    const std::int32_t input[] = {1, 2, 3, 4};
    std::int32_t output[4] = {};

    CHECK(veltis_native_transform_i32(nullptr, 0, 0, nullptr, 0) == VELTIS_NATIVE_OK,
          "null/null with zero length is a no-op");
    CHECK(veltis_native_transform_i32(input, 0, 0, nullptr, 0) == VELTIS_NATIVE_OK,
          "a non-null input with zero length is a no-op");
    CHECK(veltis_native_transform_i32(nullptr, 0, 0, output, 0) == VELTIS_NATIVE_OK,
          "a non-null output with zero length is a no-op");

    CHECK(veltis_native_transform_i32(input, 0, 0, output, 1) ==
              VELTIS_NATIVE_ERROR_LENGTH_MISMATCH,
          "zero input with a non-zero output is a length mismatch");
    CHECK(veltis_native_transform_i32(input, 1, 0, output, 0) ==
              VELTIS_NATIVE_ERROR_LENGTH_MISMATCH,
          "non-zero input with a zero output is a length mismatch");
    CHECK(veltis_native_transform_i32(nullptr, 1, 0, output, 1) ==
              VELTIS_NATIVE_ERROR_INVALID_ARGUMENT,
          "a null input with a non-zero length is rejected");
    CHECK(veltis_native_transform_i32(input, 1, 0, nullptr, 1) ==
              VELTIS_NATIVE_ERROR_INVALID_ARGUMENT,
          "a null output with a non-zero length is rejected");
}

void test_alignment_overlap_and_overflow() {
    alignas(std::int32_t) unsigned char raw[12 * sizeof(std::int32_t)] = {};
    auto* input = reinterpret_cast<std::int32_t*>(raw);
    auto* adjacent = reinterpret_cast<std::int32_t*>(raw + 4 * sizeof(std::int32_t));
    auto* overlapping = reinterpret_cast<std::int32_t*>(raw + 2 * sizeof(std::int32_t));

    if (alignof(std::int32_t) > 1) {
        auto* misaligned = reinterpret_cast<const std::int32_t*>(raw + 1);
        CHECK(veltis_native_transform_i32(misaligned, 4, 0, adjacent, 4) ==
                  VELTIS_NATIVE_ERROR_INVALID_ARGUMENT,
              "a misaligned input is rejected instead of dereferenced");
        CHECK(veltis_native_transform_i32(input, 4, 0,
                                          reinterpret_cast<std::int32_t*>(raw + 1), 4) ==
                  VELTIS_NATIVE_ERROR_INVALID_ARGUMENT,
              "a misaligned output is rejected instead of dereferenced");
    }

    CHECK(veltis_native_transform_i32(input, 4, 0, input, 4) ==
              VELTIS_NATIVE_ERROR_INVALID_ARGUMENT,
          "an exactly aliased input/output pair is rejected");
    CHECK(veltis_native_transform_i32(input, 4, 0, overlapping, 4) ==
              VELTIS_NATIVE_ERROR_INVALID_ARGUMENT,
          "a partially overlapping input/output pair is rejected");
    CHECK(veltis_native_transform_i32(input, 4, 0, adjacent, 4) == VELTIS_NATIVE_OK,
          "adjacent but non-overlapping buffers are accepted");

    const auto huge = (std::numeric_limits<std::size_t>::max)();
    CHECK(veltis_native_transform_i32(input, huge, 0, adjacent, huge) ==
              VELTIS_NATIVE_ERROR_INVALID_ARGUMENT,
          "an element count whose byte extent would overflow is rejected");
    CHECK(std::strstr(veltis_native_last_error(), "too large") != nullptr,
          "the overflow rejection explains itself");
}

void test_error_clears_on_success() {
    CHECK(veltis_native_init() == VELTIS_NATIVE_OK, "init before error-clearing test");
    const std::int32_t input[] = {1, 2};
    std::int32_t output[2] = {};

    (void)veltis_native_transform_i32(nullptr, 2, 0, output, 2);
    CHECK(veltis_native_last_error()[0] != '\0', "a failure records an error");

    CHECK(veltis_native_transform_i32(input, 2, 0, output, 2) == VELTIS_NATIVE_OK,
          "a later success still runs");
    CHECK(veltis_native_last_error()[0] == '\0', "a successful call clears the thread's error");
}

void test_byte_order_neutral_values() {
    /*
     * The ABI passes int32 values, not a serialized byte stream. Constructing
     * the values with memcpy from unsigned bit patterns exercises both byte
     * orders without assuming either one; the expected result is numeric.
     */
    const std::uint32_t patterns[] = {0x00000001u, 0x01000000u, 0xFFFFFFFFu, 0x80000000u};
    std::int32_t input[4] = {};
    std::int32_t output[4] = {};
    std::memcpy(input, patterns, sizeof(input));

    CHECK(veltis_native_transform_i32(input, 4, -1, output, 4) == VELTIS_NATIVE_OK,
          "bit-pattern transform succeeds");
    for (std::size_t i = 0; i < 4; ++i) {
        CHECK(output[i] == reference_value(input[i], -1, i),
              "the result depends on the int32 value, not host byte order");
    }
}

void test_repeated_lifecycle() {
    for (int cycle = 0; cycle < 100; ++cycle) {
        CHECK(veltis_native_init() == VELTIS_NATIVE_OK, "repeated init succeeds");
        CHECK(veltis_native_is_initialized() == 1, "repeated init marks the engine initialized");
        CHECK(veltis_native_self_test() == VELTIS_NATIVE_OK, "repeated self-test succeeds");
        veltis_native_shutdown();
        CHECK(veltis_native_is_initialized() == 0, "repeated shutdown clears the state");
    }
}

void test_concurrent_lifecycle() {
    constexpr int thread_count = 8;
    constexpr int rounds = 300;
    std::latch start(thread_count);
    std::atomic<int> worker_failures{0};
    std::vector<std::thread> threads;
    threads.reserve(thread_count);

    for (int t = 0; t < thread_count; ++t) {
        threads.emplace_back([t, &start, &worker_failures] {
            std::int32_t input[33] = {};
            std::int32_t output[33] = {};
            for (std::size_t i = 0; i < 33; ++i) {
                input[i] = static_cast<std::int32_t>(i * 31u + static_cast<unsigned>(t));
            }
            start.arrive_and_wait();
            for (int round = 0; round < rounds; ++round) {
                if (veltis_native_init() != VELTIS_NATIVE_OK) {
                    ++worker_failures;
                }

                const auto rc = veltis_native_transform_i32(input, 33, t, output, 33);
                if (rc == VELTIS_NATIVE_OK) {
                    for (std::size_t i = 0; i < 33; ++i) {
                        if (output[i] != reference_value(input[i], t, i)) {
                            ++worker_failures;
                            break;
                        }
                    }
                } else if (rc != VELTIS_NATIVE_ERROR_NOT_INITIALIZED) {
                    ++worker_failures;
                }

                const auto self = veltis_native_self_test();
                if (self != VELTIS_NATIVE_OK && self != VELTIS_NATIVE_ERROR_NOT_INITIALIZED) {
                    ++worker_failures;
                }
                const auto initialized = veltis_native_is_initialized();
                if (initialized != 0 && initialized != 1) {
                    ++worker_failures;
                }
                veltis_native_shutdown();
            }
        });
    }
    for (auto& thread : threads) {
        thread.join();
    }
    CHECK(worker_failures.load() == 0, "concurrent init/shutdown/transform never corrupts results or state");
    CHECK(veltis_native_init() == VELTIS_NATIVE_OK, "engine can be initialized after the lifecycle race");
}

void test_shutdown_while_transforms_run() {
    constexpr std::size_t count = 1u << 15;
    constexpr int thread_count = 4;
    CHECK(veltis_native_init() == VELTIS_NATIVE_OK, "init before shutdown-during-transform stress");

    std::atomic<bool> stop{false};
    std::atomic<int> worker_failures{0};
    std::vector<std::thread> threads;
    threads.reserve(thread_count);

    for (int t = 0; t < thread_count; ++t) {
        threads.emplace_back([t, &stop, &worker_failures] {
            std::vector<std::int32_t> input(count);
            std::vector<std::int32_t> output(count);
            for (std::size_t i = 0; i < count; ++i) {
                input[i] = static_cast<std::int32_t>(i * 31u + static_cast<unsigned>(t));
            }
            while (!stop.load(std::memory_order_relaxed)) {
                const auto rc = veltis_native_transform_i32(
                        input.data(), count, t, output.data(), count);
                if (rc == VELTIS_NATIVE_OK) {
                    for (std::size_t i = 0; i < count; ++i) {
                        if (output[i] != reference_value(input[i], t, i)) {
                            ++worker_failures;
                            break;
                        }
                    }
                } else if (rc != VELTIS_NATIVE_ERROR_NOT_INITIALIZED) {
                    ++worker_failures;
                }
            }
        });
    }

    for (int cycle = 0; cycle < 250; ++cycle) {
        veltis_native_shutdown();
        (void)veltis_native_init();
        std::this_thread::yield();
    }
    stop.store(true, std::memory_order_relaxed);
    for (auto& thread : threads) {
        thread.join();
    }

    CHECK(worker_failures.load() == 0,
          "shutdown during active transforms never produces a wrong result or an unexpected error");
    CHECK(veltis_native_init() == VELTIS_NATIVE_OK, "init after the shutdown stress");
}

void test_shutdown() {
    veltis_native_shutdown();
    CHECK(veltis_native_is_initialized() == 0, "shutdown clears initialization");

    const std::int32_t input[] = {1};
    std::int32_t output[] = {0};
    CHECK(veltis_native_transform_i32(input, 1, 0, output, 1) ==
              VELTIS_NATIVE_ERROR_NOT_INITIALIZED,
          "transform refuses to run after shutdown");

    veltis_native_shutdown();
    CHECK(veltis_native_is_initialized() == 0, "shutdown is idempotent");

    CHECK(veltis_native_init() == VELTIS_NATIVE_OK, "engine can be initialized again");
    CHECK(veltis_native_transform_i32(input, 1, 0, output, 1) == VELTIS_NATIVE_OK,
          "transform works after re-initialization");
    veltis_native_shutdown();
}

/*
 * The buffer predicates tested directly, not only through an export. Through
 * veltis_native_transform_i32 a mistake in one of them shows up as a wrong
 * status code, which hides which predicate was wrong and cannot reach the exact
 * arithmetic boundary: the export also has its own ordering, so a case that
 * should be decided by the extent check may be reported by an earlier one.
 */
void test_buffer_predicates_directly() {
    using veltis::native::detail::byte_ranges_overlap;
    using veltis::native::detail::checked_byte_extent;
    using veltis::native::detail::naturally_aligned;

    /* --- overflow-safe extent ------------------------------------------- */
    std::size_t bytes = 1;
    CHECK(checked_byte_extent<std::int32_t>(0, bytes) && bytes == 0,
          "zero elements have a zero extent");
    bytes = 1;
    CHECK(checked_byte_extent<std::int32_t>(1, bytes) && bytes == 4, "one element is four bytes");

    constexpr std::size_t max_count = (std::numeric_limits<std::size_t>::max)();
    /* The exact boundary: 4 * (SIZE_MAX / 4) still fits, one past it does not. */
    bytes = 0;
    CHECK(checked_byte_extent<std::int32_t>(max_count / 4u, bytes),
          "the largest count whose extent fits is accepted");
    CHECK(bytes == (max_count / 4u) * 4u, "the accepted extent is the exact product");
    bytes = 7;
    CHECK(!checked_byte_extent<std::int32_t>(max_count / 4u + 1u, bytes),
          "one element past the boundary is refused");
    CHECK(bytes == 7, "a refused extent leaves the caller's variable untouched");
    CHECK(!checked_byte_extent<std::int32_t>(max_count, bytes), "SIZE_MAX is refused");

    /* The predicate is per element type, which is why it is a template. */
    bytes = 0;
    CHECK(checked_byte_extent<std::uint8_t>(max_count, bytes) && bytes == max_count,
          "a one-byte element type can represent the full range");
    bytes = 0;
    CHECK(!checked_byte_extent<std::uint64_t>(max_count / 8u + 1u, bytes),
          "an eight-byte element type refuses earlier");

    /* --- natural alignment ---------------------------------------------- */
    alignas(16) std::int32_t aligned[4] = {1, 2, 3, 4};
    CHECK(naturally_aligned<std::int32_t>(aligned), "an aligned buffer is accepted");
    CHECK(naturally_aligned<std::int32_t>(nullptr),
          "a null pointer is aligned; its validity is a separate question");
    for (std::size_t offset = 1; offset < 4; ++offset) {
        const auto* misaligned = reinterpret_cast<const std::int32_t*>(
            reinterpret_cast<const unsigned char*>(aligned) + offset);
        CHECK(!naturally_aligned<std::int32_t>(misaligned),
              "every non-multiple of the element alignment is refused");
    }
    CHECK(naturally_aligned<std::int32_t>(aligned + 2), "an interior aligned offset stays aligned");
    CHECK(naturally_aligned<std::uint8_t>(
              reinterpret_cast<const unsigned char*>(aligned) + 1),
          "a byte type accepts any address");

    /* --- overlap -------------------------------------------------------- */
    /*
     * These ranges are synthesized addresses, not buffers: the predicate only
     * does integer arithmetic and never dereferences, and forming a pointer 1 KB
     * past a 16-byte array is undefined behaviour that a compiler is entitled to
     * warn about. The addresses still have to behave like the real thing.
     */
    const auto base_address = reinterpret_cast<std::uintptr_t>(aligned);
    auto at = [](std::uintptr_t address) {
        return reinterpret_cast<const unsigned char*>(address);
    };

    CHECK(byte_ranges_overlap(at(base_address), 16, at(base_address), 16),
          "a buffer overlaps itself");
    CHECK(byte_ranges_overlap(at(base_address), 16, at(base_address + 8), 16),
          "a partial overlap is detected");
    CHECK(byte_ranges_overlap(at(base_address), 16, at(base_address + 4), 4),
          "a contained range is detected");
    CHECK(byte_ranges_overlap(at(base_address + 4), 4, at(base_address), 16),
          "containment is detected from either side");
    CHECK(!byte_ranges_overlap(at(base_address), 8, at(base_address + 8), 8),
          "two ranges that merely touch do not overlap — rejecting this would refuse a legal call");
    CHECK(!byte_ranges_overlap(at(base_address), 16, at(base_address + 17), 16),
          "a one-byte gap is not an overlap");
    CHECK(!byte_ranges_overlap(at(base_address), 0, at(base_address), 16),
          "a zero-length range overlaps nothing, even at the same address");
    CHECK(!byte_ranges_overlap(at(base_address), 16, at(base_address + 4096), 16),
          "distant ranges are independent");

    /* An extent that would wrap the address space is treated as invalid. */
    const auto top = (std::numeric_limits<std::uintptr_t>::max)();
    CHECK(byte_ranges_overlap(at(top - 8u), 64, at(base_address), 16),
          "a wrapping extent is reported as overlapping rather than as safe");
    CHECK(!byte_ranges_overlap(at(top - 8u), 8, at(base_address), 16),
          "an extent that stops exactly at the top of the address space does not wrap");
}

} // namespace

int main() {
    test_versions();
    test_status_strings();
    test_lifecycle_and_capabilities();
    test_transform();
    test_integer_boundaries();
    test_large_input();
    test_invalid_arguments();
    test_buffer_boundaries();
    test_concurrent_transforms();
    test_thread_local_last_error();
    test_null_and_zero_length_matrix();
    test_alignment_overlap_and_overflow();
    test_buffer_predicates_directly();
    test_error_clears_on_success();
    test_byte_order_neutral_values();
    test_repeated_lifecycle();
    test_concurrent_lifecycle();
    test_shutdown_while_transforms_run();
    test_shutdown();

    if (failures != 0) {
        std::fprintf(stderr, "%d native test(s) failed\n", failures);
        return 1;
    }
    std::printf("native tests passed\n");
    return 0;
}
