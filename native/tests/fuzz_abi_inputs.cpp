/*
 * Seeded, bounded differential fuzz of the public ABI surface.
 *
 * Every input here is generated from one 64-bit seed, so a failure prints the
 * seed and the case and can be re-run exactly. This is deliberately *not*
 * libFuzzer: this host's toolchains have no sanitizer runtimes (see
 * docs/TESTING_STRATEGY.md), and a coverage-guided engine without ASan finds
 * far less while adding a framework the module does not need yet. The same
 * corpus shape is what a libFuzzer target would consume once a sanitizer build
 * exists, so converting it later is a wrapper, not a rewrite.
 *
 * What it looks for, that the fixed cases in native_tests.cpp do not: the
 * interaction between the four argument checks. `veltis_native_transform_i32`
 * validates length equality, then zero, then the two nulls, then the byte
 * extent, then alignment, then overlap. Each check is individually tested; a
 * randomized triple that trips two of them at once shows up an implementation
 * that answers with the wrong code, or that inspects a buffer before it has
 * decided the buffer is legal.
 *
 * Input constraints (this is what keeps a fuzzer safe to run against a library
 * that is supposed to reject bad arguments):
 *   - every pointer is inside one of three real, over-aligned allocations, or is
 *     null; a huge length is only ever paired with a defect that the documented
 *     order rejects before any dereference;
 *   - no length that could be accepted exceeds the buffer it points at;
 *   - nothing allocates per round, so a leak in the library is not a leak in the
 *     harness, and the harness cannot exhaust memory on its own;
 *   - execution is bounded by VELTIS_FUZZ_ROUNDS, with a fixed default.
 */
#include "veltis/native/veltis_native.h"

#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits>

namespace {

/* The default seed is part of the file: a green lane means *this* corpus passed. */
constexpr std::uint64_t kDefaultSeed = 0x5EED2026u;
constexpr std::uint64_t kDefaultRounds = 20000;
constexpr std::size_t kCapacity = 4096;      /* elements per buffer */
constexpr std::int32_t kSentinel = 0x7ABC1234;
constexpr std::size_t kStringBound = 255;    /* the header's promise */

int failures = 0;

void fail(const char* message, const char* detail) {
    ++failures;
    std::fprintf(stderr, "FUZZ FAIL: %s%s%s\n", message, detail ? " — " : "", detail ? detail : "");
}

#define CHECK(condition, message) \
    do { if (!(condition)) { fail((message), nullptr); } } while (false)

/* SplitMix64: tiny, deterministic, and the same on every platform. */
struct Random {
    std::uint64_t state;

    std::uint64_t next() {
        state += 0x9E3779B97F4A7C15ull;
        std::uint64_t z = state;
        z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9ull;
        z = (z ^ (z >> 27)) * 0x94D049BB133111EBull;
        return z ^ (z >> 31);
    }

    /* Uniform in [0, bound). A few extra bits of bias do not matter here; range
     * correctness does, so the modulus is taken explicitly rather than by
     * truncating to a smaller type. */
    std::uint64_t below(std::uint64_t bound) {
        return bound == 0 ? 0 : next() % bound;
    }
};

enum class Case : int {
    Valid = 0,
    LengthMismatch,
    ZeroLength,
    NullInput,
    NullOutput,
    Misaligned,
    Overlapping,
    ExtentOverflow,
    Count,
};

const char* case_name(Case c) {
    switch (c) {
        case Case::Valid: return "valid";
        case Case::LengthMismatch: return "length-mismatch";
        case Case::ZeroLength: return "zero-length";
        case Case::NullInput: return "null-input";
        case Case::NullOutput: return "null-output";
        case Case::Misaligned: return "misaligned";
        case Case::Overlapping: return "overlapping";
        case Case::ExtentOverflow: return "extent-overflow";
        default: return "unknown";
    }
}

/* The documented formula, written from ABI_CONTRACT.md rather than by calling
 * back into the library — the point of an oracle is that it disagrees
 * independently. */
std::int32_t expected_value(std::int32_t value, std::int32_t salt, std::size_t index) {
    const auto mixed = static_cast<std::uint32_t>(value) * 31u
        + static_cast<std::uint32_t>(salt)
        + static_cast<std::uint32_t>(index);
    return static_cast<std::int32_t>(mixed);
}

/*
 * The status the contract requires for this case. Derived from the documented
 * order of checks, in the same sequence the implementation promises: length
 * equality, zero, null input, null output, byte extent, alignment, overlap.
 */
int32_t expected_status(Case c) {
    switch (c) {
        case Case::Valid:
        case Case::ZeroLength:
            return VELTIS_NATIVE_OK;
        case Case::LengthMismatch:
            return VELTIS_NATIVE_ERROR_LENGTH_MISMATCH;
        default:
            return VELTIS_NATIVE_ERROR_INVALID_ARGUMENT;
    }
}

/* strlen bounded by the header's promise: a non-terminated string is reported
 * as a violation instead of running off the end of the allocation. */
bool string_within_bound(const char* text) {
    if (text == nullptr) {
        return false;
    }
    for (std::size_t i = 0; i <= kStringBound; ++i) {
        if (text[i] == '\0') {
            return true;
        }
    }
    return false;
}

const std::int32_t documented_codes[] = {
    VELTIS_NATIVE_OK,
    VELTIS_NATIVE_ERROR_INVALID_ARGUMENT,
    VELTIS_NATIVE_ERROR_BUFFER_TOO_SMALL,
    VELTIS_NATIVE_ERROR_NOT_INITIALIZED,
    VELTIS_NATIVE_ERROR_UNSUPPORTED,
    VELTIS_NATIVE_ERROR_INTERNAL,
    VELTIS_NATIVE_ERROR_LENGTH_MISMATCH,
};

bool is_documented(std::int32_t code) {
    for (const std::int32_t known : documented_codes) {
        if (code == known) {
            return true;
        }
    }
    return false;
}

alignas(64) std::int32_t input_buffer[kCapacity];
alignas(64) std::int32_t output_buffer[kCapacity];
/* The overlap case needs two views of one allocation; using a third array keeps
 * the other cases' buffers clean. */
alignas(64) std::int32_t shared_buffer[kCapacity];
alignas(64) std::int32_t shared_snapshot[kCapacity];

struct Choice {
    const std::int32_t* in;
    std::size_t in_length;
    std::int32_t* out;
    std::size_t out_length;
};

/* A pointer a few bytes into an allocation is misaligned for int32_t and still
 * inside the memory the harness owns. */
const std::int32_t* misaligned_in(Random& random, std::size_t length) {
    const std::size_t offset = 1 + random.below(3);
    const std::size_t base = random.below(kCapacity - length);
    return reinterpret_cast<const std::int32_t*>(
        reinterpret_cast<const unsigned char*>(input_buffer) + (base * 4u) + offset);
}

std::int32_t* misaligned_out(Random& random, std::size_t length) {
    const std::size_t offset = 1 + random.below(3);
    const std::size_t base = random.below(kCapacity - length);
    return reinterpret_cast<std::int32_t*>(
        reinterpret_cast<unsigned char*>(output_buffer) + (base * 4u) + offset);
}

Choice pick(Random& random, Case c) {
    Choice choice {};
    switch (c) {
        case Case::Valid: {
            choice.in_length = 1 + random.below(kCapacity);
            choice.out_length = choice.in_length;
            choice.in = input_buffer;
            choice.out = output_buffer;
            break;
        }
        case Case::LengthMismatch: {
            choice.in_length = random.below(kCapacity);
            /* Different by construction, and neither exceeds its buffer. */
            choice.out_length = (choice.in_length + 1 + random.below(kCapacity - 1)) % kCapacity;
            if (choice.out_length == choice.in_length) {
                choice.out_length = (choice.out_length + 1) % kCapacity;
            }
            choice.in = input_buffer;
            choice.out = output_buffer;
            break;
        }
        case Case::ZeroLength: {
            choice.in_length = 0;
            choice.out_length = 0;
            /* Both null and both valid are legal here; which one is decided by a
             * coin, because "zero is checked before the pointers" must hold for
             * both answers. */
            const bool use_null = random.below(2) == 0;
            choice.in = use_null ? nullptr : input_buffer;
            choice.out = use_null ? nullptr : output_buffer;
            break;
        }
        case Case::NullInput: {
            choice.in = nullptr;
            choice.in_length = 1 + random.below(kCapacity);
            choice.out = output_buffer;
            choice.out_length = choice.in_length;
            break;
        }
        case Case::NullOutput: {
            choice.in = input_buffer;
            choice.in_length = 1 + random.below(kCapacity);
            choice.out = nullptr;
            choice.out_length = choice.in_length;
            break;
        }
        case Case::Misaligned: {
            /* Length small enough that base + length stays inside the array. */
            choice.in_length = 1 + random.below(kCapacity / 2);
            choice.out_length = choice.in_length;
            if (random.below(2) == 0) {
                choice.in = misaligned_in(random, choice.in_length);
                choice.out = output_buffer;
            } else {
                choice.in = input_buffer;
                choice.out = misaligned_out(random, choice.out_length);
            }
            break;
        }
        case Case::Overlapping: {
            choice.in_length = 1 + random.below(kCapacity / 2);
            choice.out_length = choice.in_length;
            const std::size_t length = choice.in_length;
            /*
             * Both views come from one allocation and the output start is inside
             * the input range, so the ranges overlap by construction and neither
             * runs past the end of the buffer. A shift of zero is included on
             * purpose: identical buffers overlap too.
             */
            const std::size_t in_base = random.below(kCapacity - length);
            const std::size_t room_after = kCapacity - length - in_base;
            const std::size_t limit = length < room_after ? length : room_after;
            choice.in = shared_buffer + in_base;
            choice.out = shared_buffer + in_base + random.below(limit);
            break;
        }
        case Case::ExtentOverflow: {
            static const std::size_t huge[] = {
                (std::numeric_limits<std::size_t>::max)(),
                (std::numeric_limits<std::size_t>::max)() / 4u + 1u,
                (std::numeric_limits<std::size_t>::max)() / 2u,
                (std::numeric_limits<std::size_t>::max)() - 3u,
            };
            choice.in_length = huge[random.below(sizeof(huge) / sizeof(huge[0]))];
            choice.out_length = choice.in_length;
            choice.in = input_buffer;
            choice.out = output_buffer;
            break;
        }
        default:
            break;
    }
    return choice;
}

void describe(std::uint64_t seed, std::uint64_t round, Case c, const Choice& choice,
              std::int32_t salt, int32_t expected, int32_t actual) {
    char detail[512];
    std::snprintf(detail, sizeof(detail),
                  "seed=%llu round=%llu case=%s in=%p/%zu out=%p/%zu salt=%d "
                  "expected=%d actual=%d — reproduce with: "
                  "VELTIS_FUZZ_SEED=%llu VELTIS_FUZZ_ROUNDS=%llu %s",
                  static_cast<unsigned long long>(seed), static_cast<unsigned long long>(round),
                  case_name(c), static_cast<const void*>(choice.in), choice.in_length,
                  static_cast<void*>(choice.out), choice.out_length, static_cast<int>(salt),
                  static_cast<int>(expected), static_cast<int>(actual),
                  static_cast<unsigned long long>(seed), static_cast<unsigned long long>(round + 1),
                  "native_fuzz_abi_inputs");
    fail("contract violation", detail);
}

std::uint64_t read_unsigned_env(const char* name, std::uint64_t fallback) {
    const char* raw = std::getenv(name);
    if (raw == nullptr || *raw == '\0') {
        return fallback;
    }
    return std::strtoull(raw, nullptr, 10);
}

void fuzz_round(std::uint64_t seed, std::uint64_t round, Random& random) {
    /* Weighting: valid calls are the majority, because the rejected paths are
     * cheap and the accepted path is where a wrong value would be silent. */
    const std::uint64_t roll = random.below(100);
    Case c = Case::Valid;
    if (roll < 45) {
        c = Case::Valid;
    } else if (roll < 55) {
        c = Case::LengthMismatch;
    } else if (roll < 60) {
        c = Case::ZeroLength;
    } else if (roll < 68) {
        c = Case::NullInput;
    } else if (roll < 76) {
        c = Case::NullOutput;
    } else if (roll < 84) {
        c = Case::Misaligned;
    } else if (roll < 92) {
        c = Case::Overlapping;
    } else {
        c = Case::ExtentOverflow;
    }

    const Choice choice = pick(random, c);
    /* Fill the input deterministically from the same stream, so a reproduced
     * seed reproduces the data as well as the arguments. */
    for (std::size_t i = 0; i < kCapacity; ++i) {
        input_buffer[i] = static_cast<std::int32_t>(random.next());
        shared_buffer[i] = static_cast<std::int32_t>(random.next());
    }
    for (std::size_t i = 0; i < kCapacity; ++i) {
        output_buffer[i] = kSentinel;
    }
    /* The overlap case's buffers are the shared array, so its untouched check
     * needs a copy of what was there before the call. */
    std::memcpy(shared_snapshot, shared_buffer, sizeof(shared_buffer));

    const std::int32_t salt = static_cast<std::int32_t>(random.next());
    const int32_t expected = expected_status(c);
    const int32_t actual = veltis_native_transform_i32(choice.in, choice.in_length, salt,
                                                      choice.out, choice.out_length);

    if (actual != expected) {
        describe(seed, round, c, choice, salt, expected, actual);
        return;
    }

    /* An accepted call must produce the contract's values, element for element. */
    if (actual == VELTIS_NATIVE_OK && choice.in_length > 0 && c == Case::Valid) {
        for (std::size_t i = 0; i < choice.in_length; ++i) {
            if (choice.out[i] != expected_value(choice.in[i], salt, i)) {
                char detail[256];
                std::snprintf(detail, sizeof(detail), "seed=%llu round=%llu index=%zu",
                              static_cast<unsigned long long>(seed),
                              static_cast<unsigned long long>(round), i);
                fail("transform returned a wrong value", detail);
                return;
            }
        }
    }

    /* A rejected call must not have written anywhere in the output buffer. The
     * overlap case writes into shared_buffer if the library ever accepts it, so
     * the sentinels checked here are the ones the case could not legitimately
     * touch. */
    if (actual != VELTIS_NATIVE_OK) {
        for (std::size_t i = 0; i < kCapacity; ++i) {
            if (output_buffer[i] != kSentinel) {
                char detail[256];
                std::snprintf(detail, sizeof(detail), "seed=%llu round=%llu index=%zu case=%s",
                              static_cast<unsigned long long>(seed),
                              static_cast<unsigned long long>(round), i, case_name(c));
                fail("a rejected call wrote to the output buffer", detail);
                return;
            }
        }
        if (c == Case::Overlapping
            && std::memcmp(shared_snapshot, shared_buffer, sizeof(shared_buffer)) != 0) {
            char detail[128];
            std::snprintf(detail, sizeof(detail), "seed=%llu round=%llu",
                          static_cast<unsigned long long>(seed),
                          static_cast<unsigned long long>(round));
            fail("a rejected overlapping call modified the caller's buffer", detail);
            return;
        }
    }

    /* Error-slot contract on both branches, on the thread that made the call. */
    const char* message = veltis_native_last_error();
    if (message == nullptr) {
        fail("last_error returned null", nullptr);
    } else if (!string_within_bound(message)) {
        fail("last_error is not terminated inside the documented bound", nullptr);
    } else if (actual == VELTIS_NATIVE_OK && message[0] != '\0') {
        fail("a successful call left a message in the error slot", nullptr);
    } else if (actual != VELTIS_NATIVE_OK && message[0] == '\0') {
        fail("a failed call recorded no message", nullptr);
    }

    /* The naming rule, randomized: an undocumented code must never be reported
     * with a documented name, because that turns one failure into another's
     * support ticket. */
    const std::int32_t code = static_cast<std::int32_t>(random.next());
    const char* name = veltis_native_status_string(code);
    if (name == nullptr || !string_within_bound(name)) {
        fail("status_string returned null or an over-long name", nullptr);
    } else {
        const bool unknown = std::strcmp(name, "unknown status") == 0;
        if (unknown == is_documented(code)) {
            char detail[128];
            std::snprintf(detail, sizeof(detail), "code=%d name=%s", static_cast<int>(code), name);
            fail("status_string disagrees with the documented code set", detail);
        }
    }

    /* State the ABI says is stable must stay stable while all of this runs. */
    if (veltis_native_is_initialized() != 1) {
        fail("the engine stopped being initialized during fuzzing", nullptr);
    }
}

} // namespace

int main() {
    const std::uint64_t seed = read_unsigned_env("VELTIS_FUZZ_SEED", kDefaultSeed);
    const std::uint64_t rounds = read_unsigned_env("VELTIS_FUZZ_ROUNDS", kDefaultRounds);

    if (veltis_native_init() != VELTIS_NATIVE_OK) {
        std::fprintf(stderr, "fuzz: init failed; nothing to fuzz\n");
        return 2;
    }

    std::printf("fuzz: seed=%llu rounds=%llu\n", static_cast<unsigned long long>(seed),
                static_cast<unsigned long long>(rounds));

    Random random {seed};
    for (std::uint64_t round = 0; round < rounds; ++round) {
        fuzz_round(seed, round, random);
        if (failures > 0 && round > 32) {
            /* A violation is a bug, not a statistic: stop with the context that
             * reproduces it instead of printing ten thousand identical lines. */
            std::fprintf(stderr, "fuzz: stopping after the first violation\n");
            break;
        }
    }

    if (failures == 0) {
        std::printf("fuzz: %llu rounds, no contract violations\n",
                    static_cast<unsigned long long>(rounds));
        veltis_native_shutdown();
        return 0;
    }
    std::fprintf(stderr, "fuzz: %d violations (seed %llu)\n", failures,
                 static_cast<unsigned long long>(seed));
    return 1;
}
