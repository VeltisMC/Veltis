#include "veltis/native/veltis_native.h"

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <latch>
#include <limits>
#include <thread>
#include <vector>

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
    test_shutdown();

    if (failures != 0) {
        std::fprintf(stderr, "%d native test(s) failed\n", failures);
        return 1;
    }
    std::printf("native tests passed\n");
    return 0;
}
