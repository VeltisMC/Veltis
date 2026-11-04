/*
 * ABI contract test, compiled as C11.
 *
 * What the other suites cannot do from their side of the boundary:
 *
 *   - consume veltis_native.h as plain C, so a C++ construct cannot leak into
 *     the only header an external consumer is allowed to depend on;
 *   - pin every exported signature as a function-pointer type, so a changed
 *     parameter, a dropped const or a different return type is a compile error
 *     rather than a corrupted downcall;
 *   - verify the library's *self-reported* facts against the header it was
 *     supposed to implement: ABI version, the three version components against
 *     the version string, the status-code domain, and the capability mask;
 *   - compute the transform result with an independent C reference, so a
 *     "correct" native implementation and a correct C++ test that copied its
 *     formula cannot both be wrong in the same way.
 *
 * It never allocates, never creates a thread, and only uses the documented
 * return codes.
 */
#include "abi_contract_checks.h"

#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

static int g_failures = 0;

static void check(int condition, const char* expression, const char* message) {
    if (condition) {
        return;
    }
    ++g_failures;
    (void)fprintf(stderr, "FAIL: %s (%s)\n", message, expression);
}

#define CHECK(condition, message) check((condition), #condition, (message))

/*
 * The header promises that a returned string is NUL-terminated and never longer
 * than this. The Java loader reinterprets a fixed window around every string it
 * reads, so the promise is what makes that window a bound rather than a guess;
 * it is checked here instead of only written down.
 */
#define MAX_STRING_BYTES 255

/*
 * The contract restated in C, without reusing the library. The cast back to
 * int32_t is done with memcpy because converting an out-of-range unsigned value
 * is implementation-defined before C23; the copy is bit-exact on every target.
 */
static int32_t c_reference(int32_t value, int32_t salt, size_t index) {
    const uint32_t mixed = (uint32_t)value * 31u + (uint32_t)salt + (uint32_t)index;
    int32_t bits = 0;
    (void)memcpy(&bits, &mixed, sizeof(bits));
    return bits;
}

static int is_documented_status(int32_t status) {
    return status == VELTIS_NATIVE_OK
        || status == VELTIS_NATIVE_ERROR_INVALID_ARGUMENT
        || status == VELTIS_NATIVE_ERROR_BUFFER_TOO_SMALL
        || status == VELTIS_NATIVE_ERROR_NOT_INITIALIZED
        || status == VELTIS_NATIVE_ERROR_UNSUPPORTED
        || status == VELTIS_NATIVE_ERROR_INTERNAL
        || status == VELTIS_NATIVE_ERROR_LENGTH_MISMATCH;
}

/*
 * This case cannot fail at run time, and saying so is part of the contract test:
 * the pin function assigns every export address to a function-pointer type with
 * the documented signature, so a changed parameter, dropped const, different
 * return type or wrong calling convention is a *compile* error in this
 * translation unit. The run-time CHECK only records that the unit was built.
 */
static void test_signatures_are_pinnable(void) {
    CHECK(abi_pin_all_signatures() == 1, "the signature pins compiled (see the comment above)");
}

static void test_header_and_library_agree(void) {
    CHECK(veltis_native_abi_version() == VELTIS_NATIVE_ABI_VERSION,
          "the library reports the header's ABI version");

    const int32_t major = veltis_native_version_major();
    const int32_t minor = veltis_native_version_minor();
    const int32_t patch = veltis_native_version_patch();
    CHECK(major == VELTIS_NATIVE_VERSION_MAJOR, "major component matches the header");
    CHECK(minor == VELTIS_NATIVE_VERSION_MINOR, "minor component matches the header");
    CHECK(patch == VELTIS_NATIVE_VERSION_PATCH, "patch component matches the header");

    char expected[32];
    (void)snprintf(expected, sizeof(expected), "%d.%d.%d", (int)major, (int)minor, (int)patch);
    const char* actual = veltis_native_version_string();
    CHECK(actual != NULL, "version string is never null");
    if (actual != NULL) {
        CHECK(strcmp(actual, expected) == 0,
              "the version string is the three components, not an unrelated literal");
        CHECK(strlen(actual) <= MAX_STRING_BYTES,
              "the version string stays inside the header's read bound");
    }
}

static void test_status_string_domain(void) {
    /*
     * Every code the header documents must name itself, and every code outside
     * that set must be reported as unknown rather than reused. A caller logs
     * these strings, so a wrong pairing is a wrong support ticket.
     */
    static const int32_t documented[] = {
        VELTIS_NATIVE_OK,
        VELTIS_NATIVE_ERROR_INVALID_ARGUMENT,
        VELTIS_NATIVE_ERROR_BUFFER_TOO_SMALL,
        VELTIS_NATIVE_ERROR_NOT_INITIALIZED,
        VELTIS_NATIVE_ERROR_UNSUPPORTED,
        VELTIS_NATIVE_ERROR_INTERNAL,
        VELTIS_NATIVE_ERROR_LENGTH_MISMATCH,
    };
    for (size_t i = 0; i < sizeof(documented) / sizeof(documented[0]); ++i) {
        const char* text = veltis_native_status_string(documented[i]);
        /*
         * The header says a status name is never null, so a null here is already
         * a contract violation — but the checks below must not dereference it on
         * their way to reporting that.
         */
        CHECK(text != NULL && text[0] != '\0', "a documented status has a name");
        if (text == NULL) {
            continue;
        }
        CHECK(strcmp(text, "unknown status") != 0, "a documented status is not reported as unknown");
        CHECK(strlen(text) <= MAX_STRING_BYTES, "a status name stays inside the header's read bound");
    }

    static const int32_t undocumented[] = {1, -7, -100, INT32_MIN, INT32_MAX};
    for (size_t i = 0; i < sizeof(undocumented) / sizeof(undocumented[0]); ++i) {
        CHECK(!is_documented_status(undocumented[i]),
              "the value really is outside the header's set");
        CHECK(strcmp(veltis_native_status_string(undocumented[i]), "unknown status") == 0,
              "an undocumented code is reported as unknown, never as a known failure");
    }
    CHECK(strcmp(veltis_native_status_string(VELTIS_NATIVE_OK), "ok") == 0, "OK is 'ok'");
}

static void test_lifecycle_contract(void) {
    /* Fresh process: nothing is initialized yet, and that must be visible. */
    CHECK(veltis_native_is_initialized() == 0, "a new process starts uninitialized");
    CHECK(veltis_native_transform_i32(NULL, 0, 0, NULL, 0) == VELTIS_NATIVE_ERROR_NOT_INITIALIZED,
          "even a zero-length transform refuses before init");
    CHECK(veltis_native_self_test() == VELTIS_NATIVE_ERROR_NOT_INITIALIZED,
          "self-test refuses before init");

    /*
     * The capability mask answers before init, and the Java loader reads it both
     * here and after init. The two reads must agree or the loader's pre-init
     * answer would be a different library's claim: the mask is a property of the
     * compiled code, not of the engine state.
     */
    const uint32_t pre_init_mask = veltis_native_capabilities();

    CHECK(veltis_native_init() == VELTIS_NATIVE_OK, "init succeeds");
    CHECK(veltis_native_is_initialized() == 1, "init is observable through is_initialized");
    CHECK(veltis_native_init() == VELTIS_NATIVE_OK, "repeated init is idempotent");
    CHECK(veltis_native_self_test() == VELTIS_NATIVE_OK, "self-test succeeds after init");

    const uint32_t mask = veltis_native_capabilities();
    CHECK(mask == pre_init_mask, "the capability mask does not depend on lifecycle state");
    CHECK((mask & VELTIS_NATIVE_CAPABILITY_TRANSFORM_I32) != 0u, "the transform is advertised");
    CHECK((mask & ~(VELTIS_NATIVE_CAPABILITY_TRANSFORM_I32 | VELTIS_NATIVE_CAPABILITY_SELF_TEST))
              == 0u,
          "this build advertises only capability bits the header defines");

    veltis_native_shutdown();
    CHECK(veltis_native_capabilities() == pre_init_mask,
          "the mask survives shutdown, because shutdown does not change the code");
    /* The operation tests that run after this one need an initialized engine. */
    CHECK(veltis_native_init() == VELTIS_NATIVE_OK,
          "the lifecycle test leaves the engine initialized for the tests that follow");
}

static void test_transform_matches_the_contract(void) {
    static const int32_t input[] = {
        0, 1, -1, 2, INT32_MAX, INT32_MIN, 7, 123456789, -987654321, 42,
    };
    static int32_t output[sizeof(input) / sizeof(input[0])];
    static const size_t count = sizeof(input) / sizeof(input[0]);
    static const int32_t salts[] = {0, 1, -1, INT32_MAX, INT32_MIN, 31, 987654321};

    for (size_t s = 0; s < sizeof(salts) / sizeof(salts[0]); ++s) {
        (void)memset(output, 0x5A, sizeof(output));
        const int32_t status = veltis_native_transform_i32(input, count, salts[s], output, count);
        CHECK(status == VELTIS_NATIVE_OK, "transform succeeds on an aligned, non-overlapping pair");
        for (size_t i = 0; i < count; ++i) {
            CHECK(output[i] == c_reference(input[i], salts[s], i),
                  "native result equals the independent C reference");
        }
    }

    /* Length 0 is a defined no-op for any pointer combination, including NULL. */
    CHECK(veltis_native_transform_i32(NULL, 0, 5, NULL, 0) == VELTIS_NATIVE_OK,
          "zero length is success, not an error");
    /* A single element exercises the index term, which is easy to get off by one. */
    int32_t one_in = INT32_MIN;
    int32_t one_out = 0;
    CHECK(veltis_native_transform_i32(&one_in, 1, 0, &one_out, 1) == VELTIS_NATIVE_OK,
          "one-element transform succeeds");
    CHECK(one_out == c_reference(one_in, 0, 0), "one-element transform matches the reference");
}

static void test_buffer_size_validation(void) {
    static int32_t input[] = {1, 2, 3, 4};
    static int32_t output[4];

    /*
     * The transform has no capacity model: the two lengths must be equal, so a
     * smaller output is a length mismatch and never a silent partial write.
     * VELTIS_NATIVE_ERROR_BUFFER_TOO_SMALL is reserved for a future export that
     * takes a capacity, and this library never returns it.
     */
    CHECK(veltis_native_transform_i32(input, 4, 0, output, 3)
              == VELTIS_NATIVE_ERROR_LENGTH_MISMATCH,
          "an output shorter than the input is rejected, not partially written");
    CHECK(veltis_native_transform_i32(input, 3, 0, output, 4)
              == VELTIS_NATIVE_ERROR_LENGTH_MISMATCH,
          "an output longer than the input is rejected too");
    for (size_t i = 0; i < 4; ++i) {
        CHECK(output[i] == 0, "a rejected call writes nothing");
    }

    CHECK(veltis_native_transform_i32(NULL, 4, 0, output, 4)
              == VELTIS_NATIVE_ERROR_INVALID_ARGUMENT,
          "a null input with a non-zero length is rejected");
    CHECK(veltis_native_transform_i32(input, 4, 0, NULL, 4)
              == VELTIS_NATIVE_ERROR_INVALID_ARGUMENT,
          "a null output with a non-zero length is rejected");

    /* The byte extent is checked for overflow before any pointer is dereferenced. */
    CHECK(veltis_native_transform_i32(input, SIZE_MAX, 0, output, SIZE_MAX)
              == VELTIS_NATIVE_ERROR_INVALID_ARGUMENT,
          "a length whose byte extent overflows size_t is rejected");
    CHECK(veltis_native_transform_i32(input, SIZE_MAX / 2u, 0, output, SIZE_MAX / 2u)
              == VELTIS_NATIVE_ERROR_INVALID_ARGUMENT,
          "a length one below that boundary is still rejected");

    /* Overlap is rejected rather than producing an order-dependent result. */
    CHECK(veltis_native_transform_i32(input, 4, 0, input, 4)
              == VELTIS_NATIVE_ERROR_INVALID_ARGUMENT,
          "in-place transform is refused");
    CHECK(veltis_native_transform_i32(&input[1], 3, 0, &input[2], 3)
              == VELTIS_NATIVE_ERROR_INVALID_ARGUMENT,
          "a partially overlapping pair is refused");
}

static void test_error_reporting_is_a_contract(void) {
    static int32_t input[] = {1, 2, 3};
    static int32_t output[3];

    /*
     * Earlier checks in this process already recorded failures, so the slot is
     * occupied when the function starts. Establish the cleared state through a
     * success first: that is the behaviour the contract actually promises, and
     * it does not depend on what ran before it.
     */
    CHECK(veltis_native_transform_i32(input, 3, 0, output, 3) == VELTIS_NATIVE_OK,
          "the reference call succeeds");
    CHECK(veltis_native_last_error() != NULL, "last_error never returns null");
    CHECK(strcmp(veltis_native_last_error(), "") == 0,
          "success leaves the slot as the empty string, never null and never stale");

    CHECK(veltis_native_transform_i32(input, 3, 0, NULL, 3)
              == VELTIS_NATIVE_ERROR_INVALID_ARGUMENT,
          "the failing call returns invalid-argument");
    CHECK(strlen(veltis_native_last_error()) > 0,
          "the failing call records a message on this thread");
    CHECK(strlen(veltis_native_last_error()) <= MAX_STRING_BYTES,
          "the recorded message stays inside the header's read bound");

    /* A success must clear the slot, or the next caller reads someone else's
       failure as its own. */
    CHECK(veltis_native_transform_i32(input, 3, 0, output, 3) == VELTIS_NATIVE_OK,
          "the retry succeeds");
    CHECK(strcmp(veltis_native_last_error(), "") == 0, "success clears the error slot");
}

static void test_shutdown_and_reinitialization(void) {
    veltis_native_shutdown();
    CHECK(veltis_native_is_initialized() == 0, "shutdown is observable");
    veltis_native_shutdown();
    CHECK(veltis_native_is_initialized() == 0, "repeated shutdown is idempotent");
    CHECK(veltis_native_transform_i32(NULL, 0, 0, NULL, 0) == VELTIS_NATIVE_ERROR_NOT_INITIALIZED,
          "operations refuse again after shutdown");

    CHECK(veltis_native_init() == VELTIS_NATIVE_OK, "the engine can be started again");
    int32_t value = 5;
    int32_t result = 0;
    CHECK(veltis_native_transform_i32(&value, 1, 0, &result, 1) == VELTIS_NATIVE_OK,
          "a re-initialized engine works");
    CHECK(result == c_reference(value, 0, 0), "a re-initialized engine is still correct");
    veltis_native_shutdown();
}

int main(void) {
    test_signatures_are_pinnable();
    test_header_and_library_agree();
    test_status_string_domain();
    test_lifecycle_contract();
    test_transform_matches_the_contract();
    test_buffer_size_validation();
    test_error_reporting_is_a_contract();
    test_shutdown_and_reinitialization();

    if (g_failures != 0) {
        (void)fprintf(stderr, "ABI contract (C): %d failure(s)\n", g_failures);
        return 1;
    }
    (void)printf("ABI contract (C): all checks passed\n");
    return 0;
}
