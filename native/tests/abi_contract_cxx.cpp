/*
 * ABI contract test, compiled as C++20.
 *
 * The same assertions as abi_contract_c.c, in a different language, against the
 * same header. That is the point: veltis_native.h has to be valid C and valid
 * C++ with identical observable rules, because the library is built as both and
 * an external consumer may include it from either.
 *
 * This TU adds what only a C++ compiler can catch:
 *   - the header's `extern "C"` guard actually works, including when included
 *     from inside a namespace;
 *   - no C++-only construct hides in the header;
 *   - the exported set can be referenced through C++ linkage without a single
 *     C++ type appearing in a signature.
 */
#include "abi_contract_checks.h"

#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <type_traits>

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

/*
 * Return-type pins, which the function-pointer typedefs in the shared checks
 * already cover as part of a full signature but which read better spelled out.
 * A C++ compiler is the only one that can state them: C has no decltype, so the
 * "the export still returns int32_t" rule is asserted here and the parameter
 * rules are asserted in both languages.
 *
 * The internal bridge is deliberately not declared in veltis_native.h; naming it
 * here would be a compile error rather than a test, so its absence is proven
 * against the built binary by tests/export_check.c instead.
 */
static_assert(std::is_same<decltype(veltis_native_abi_version()), int32_t>::value,
              "the ABI version query returns int32_t");
static_assert(std::is_same<decltype(veltis_native_init()), int32_t>::value,
              "init returns a status code, not a bool");
static_assert(std::is_same<decltype(veltis_native_is_initialized()), int32_t>::value,
              "the initialization query returns int32_t, never C++ bool");
static_assert(std::is_same<decltype(veltis_native_self_test()), int32_t>::value,
              "self-test returns a status code");
static_assert(std::is_same<decltype(veltis_native_capabilities()), uint32_t>::value,
              "the capability mask returns uint32_t");
static_assert(std::is_same<decltype(veltis_native_shutdown()), void>::value,
              "shutdown reports no status by design");
static_assert(std::is_same<decltype(veltis_native_version_string()), const char*>::value,
              "version_string returns a const C string");
static_assert(std::is_same<decltype(veltis_native_last_error()), const char*>::value,
              "last_error returns a const C string the caller must not free");
static_assert(std::is_same<decltype(veltis_native_status_string(0)), const char*>::value,
              "status_string returns a const C string");
static_assert(std::is_same<decltype(veltis_native_transform_i32(nullptr, 0, 0, nullptr, 0)),
                            int32_t>::value,
              "the transform returns a status code");

/* A C++-side reference, written from the documented formula, not from the code. */
constexpr std::uint32_t mix(std::int32_t value, std::int32_t salt, std::size_t index) {
    return static_cast<std::uint32_t>(value) * 31u
         + static_cast<std::uint32_t>(salt)
         + static_cast<std::uint32_t>(index);
}

/* The narrowing the C++ implementation makes explicit with std::bit_cast. */
std::int32_t bits_of(std::uint32_t value) {
    std::int32_t result = 0;
    std::memcpy(&result, &value, sizeof(result));
    return result;
}

void test_signature_pins(void) {
    CHECK(abi_pin_all_signatures() == 1, "every export resolves to its documented signature");
}

void test_header_agrees_with_the_library(void) {
    CHECK(veltis_native_abi_version() == VELTIS_NATIVE_ABI_VERSION, "ABI version is the header's");

    char expected[32];
    std::snprintf(expected, sizeof(expected), "%d.%d.%d",
                  veltis_native_version_major(),
                  veltis_native_version_minor(),
                  veltis_native_version_patch());
    CHECK(std::strcmp(veltis_native_version_string(), expected) == 0,
          "version string equals the components");
}

void test_capability_and_status_rules(void) {
    const std::uint32_t mask = veltis_native_capabilities();
    CHECK((mask & VELTIS_NATIVE_CAPABILITY_TRANSFORM_I32) != 0u, "transform is advertised");
    CHECK((mask & ~(VELTIS_NATIVE_CAPABILITY_TRANSFORM_I32 | VELTIS_NATIVE_CAPABILITY_SELF_TEST))
              == 0u,
          "no capability bit outside the header is advertised");

    /* A status arrives as int32_t, so the enum must survive the round trip. */
    const std::int32_t codes[] = {
        VELTIS_NATIVE_OK,
        VELTIS_NATIVE_ERROR_INVALID_ARGUMENT,
        VELTIS_NATIVE_ERROR_BUFFER_TOO_SMALL,
        VELTIS_NATIVE_ERROR_NOT_INITIALIZED,
        VELTIS_NATIVE_ERROR_UNSUPPORTED,
        VELTIS_NATIVE_ERROR_INTERNAL,
        VELTIS_NATIVE_ERROR_LENGTH_MISMATCH,
    };
    for (const auto code : codes) {
        CHECK(std::strcmp(veltis_native_status_string(code), "unknown status") != 0,
              "a documented code names itself in C++ too");
    }
}

void test_transform_from_cxx(void) {
    CHECK(veltis_native_init() == VELTIS_NATIVE_OK, "init through the C ABI from C++");

    const std::int32_t input[] = {0, 1, -1, INT32_MAX, INT32_MIN, 31, 987654321};
    std::int32_t output[sizeof(input) / sizeof(input[0])] = {};
    const std::size_t count = sizeof(input) / sizeof(input[0]);

    CHECK(veltis_native_transform_i32(input, count, -987654321, output, count)
              == VELTIS_NATIVE_OK,
          "transform succeeds when called from C++");
    for (std::size_t i = 0; i < count; ++i) {
        CHECK(output[i] == bits_of(mix(input[i], -987654321, i)),
              "native result equals the formula in the header comment");
    }

    /* Ownership rule: nothing here was allocated by the library. */
    CHECK(veltis_native_last_error() != nullptr, "last_error is never null");

    veltis_native_shutdown();
    CHECK(veltis_native_is_initialized() == 0, "shutdown from C++ is observable");
}

} // namespace

int main() {
    test_signature_pins();
    test_header_agrees_with_the_library();
    test_capability_and_status_rules();
    test_transform_from_cxx();

    if (failures != 0) {
        std::fprintf(stderr, "ABI contract (C++): %d failure(s)\n", failures);
        return 1;
    }
    std::printf("ABI contract (C++): all checks passed\n");
    return 0;
}
