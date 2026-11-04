/*
 * Host-side verification of the built shared library.
 *
 * The test does two things the compile-time build cannot prove:
 *
 *   1. it reads the first bytes of the artifact and proves the binary format is
 *      the one the host operating system loads (PE on Windows, ELF on Linux and
 *      the BSDs, Mach-O on macOS), so a mislabelled cross-build or a stale file
 *      from another platform cannot silently pass. Mach-O magics are checked in
 *      both byte orders, and a universal container is rejected on macOS because
 *      the release rule is one architecture per artifact; and
 *   2. it loads the library with the host loader and resolves every public C
 *      export by name, then proves the internal C++/C bridge symbols are *not*
 *      visible.
 *
 * It is deliberately written against the platform loader rather than
 * objdump/readelf/dumpbin so it runs unchanged on every supported host and adds
 * no toolchain dependency. Usage: native_export_check <path-to-library>
 */
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#if defined(_WIN32)
#  include <windows.h>
#else
#  include <dlfcn.h>
#endif

#define ARRAY_LENGTH(array) (sizeof(array) / sizeof((array)[0]))

/*
 * Exactly the symbols declared in include/veltis/native/veltis_native.h. The
 * list is intentionally spelled out instead of generated: a new export must be
 * added here and to the header together, which is the review the ABI needs.
 */
static const char* const k_public_exports[] = {
    "veltis_native_abi_version",
    "veltis_native_version_major",
    "veltis_native_version_minor",
    "veltis_native_version_patch",
    "veltis_native_version_string",
    "veltis_native_status_string",
    "veltis_native_last_error",
    "veltis_native_init",
    "veltis_native_is_initialized",
    "veltis_native_shutdown",
    "veltis_native_capabilities",
    "veltis_native_self_test",
    "veltis_native_transform_i32",
};

/* Never exported: the hidden-visibility internal bridge used by the C unit. */
static const char* const k_internal_symbols[] = {
    "veltis_native_set_last_error",
    "veltis_native_clear_last_error",
};

/*
 * Names that must not exist. They are the shapes this ABI is explicitly not:
 *
 *   - an allocator or a release function, because every buffer is caller-owned
 *     and no export may hand memory to a caller it expects to free;
 *   - a suffix variant of a real export, because a v2 or _ex spelling added next
 *     to the original is an ABI fork that the loader cannot detect;
 *   - a near-miss spelling, because a stale consumer must fail at resolution
 *     rather than silently bind to something adjacent.
 *
 * This is a probe list, not an enumeration: LoadLibrary/dlsym can resolve a name
 * but cannot list a binary's exports portably, and this test deliberately adds
 * no objdump/readelf/dumpbin dependency. The complete "no unexpected export"
 * rule is enforced by the CI job that diffs the binary's export table against
 * the header, and by CMake's hidden visibility plus --no-undefined at link time.
 */
static const char* const k_forbidden_symbols[] = {
    "veltis_native_alloc",
    "veltis_native_free",
    "veltis_native_release",
    "veltis_native_last_error_free",
    "veltis_native_transform",
    "veltis_native_transform_i32_ex",
    "veltis_native_transform_i32_v2",
    "veltis_native_init_ex",
    "veltis_native_shutdown_all",
    "veltis_native_capabilities_v2",
    "veltis_native_self_test_ex",
};

static uint32_t read_u32_le(const unsigned char* bytes) {
    return (uint32_t)bytes[0]
        | ((uint32_t)bytes[1] << 8)
        | ((uint32_t)bytes[2] << 16)
        | ((uint32_t)bytes[3] << 24);
}

static uint32_t read_u32_be(const unsigned char* bytes) {
    return ((uint32_t)bytes[0] << 24)
        | ((uint32_t)bytes[1] << 16)
        | ((uint32_t)bytes[2] << 8)
        | (uint32_t)bytes[3];
}

/* The values, not the byte images: each is compared against both readings. */
#define MACHO_MAGIC 0xFEEDFACEu    /* mach_header,      32-bit */
#define MACHO_MAGIC_64 0xFEEDFACFu /* mach_header_64,   64-bit */
#define MACHO_FAT_MAGIC 0xCAFEBABEu /* fat_header, always big-endian */

typedef enum binary_kind {
    KIND_UNKNOWN = 0,
    KIND_PE,
    KIND_ELF,
    KIND_MACHO_THIN,
    KIND_MACHO_FAT
} binary_kind;

/*
 * A Mach-O header is written in its own byte order, so every magic has to be
 * tried twice: CF FA ED FE is a little-endian 64-bit header and FE ED FA CF is
 * the same header big-endian. Reading only the little-endian interpretation —
 * which is what a single u32le comparison does — recognises the byte-swapped fat
 * magic that no toolchain emits, and rejects a real universal binary whose bytes
 * are CA FE BA BE.
 */
static binary_kind classify_binary(const unsigned char* bytes, size_t length) {
    if (length >= 2 && bytes[0] == 'M' && bytes[1] == 'Z') {
        return KIND_PE;
    }
    if (length >= 4 && bytes[0] == 0x7F && bytes[1] == 'E' && bytes[2] == 'L' && bytes[3] == 'F') {
        return KIND_ELF;
    }
    if (length < 4) {
        return KIND_UNKNOWN;
    }
    const uint32_t little = read_u32_le(bytes);
    const uint32_t big = read_u32_be(bytes);
    if (little == MACHO_MAGIC || little == MACHO_MAGIC_64
            || big == MACHO_MAGIC || big == MACHO_MAGIC_64) {
        return KIND_MACHO_THIN;
    }
    if (big == MACHO_FAT_MAGIC) {
        return KIND_MACHO_FAT;
    }
    return KIND_UNKNOWN;
}

static const char* kind_name(binary_kind kind) {
    switch (kind) {
        case KIND_PE:
            return "PE";
        case KIND_ELF:
            return "ELF";
        case KIND_MACHO_THIN:
        case KIND_MACHO_FAT:
            return "Mach-O";
        case KIND_UNKNOWN:
            return NULL;
    }
    return NULL;
}

static const char* expected_format(void) {
#if defined(_WIN32)
    return "PE";
#elif defined(__APPLE__)
    return "Mach-O";
#else
    return "ELF";
#endif
}

static int check_binary_format(const char* path) {
    unsigned char magic[8];
    FILE* file = fopen(path, "rb");
    if (file == NULL) {
        fprintf(stderr, "FAIL: cannot open %s\n", path);
        return 0;
    }
    const size_t read = fread(magic, 1, sizeof(magic), file);
    fclose(file);
    if (read < 4) {
        fprintf(stderr, "FAIL: %s is only %zu bytes, too short to be a shared library\n",
                path, read);
        return 0;
    }

    const binary_kind kind = classify_binary(magic, read);
    const char* format = kind_name(kind);
    if (format == NULL) {
        fprintf(stderr, "FAIL: %s has no recognised PE/ELF/Mach-O signature\n", path);
        return 0;
    }
#if defined(__APPLE__)
    /*
     * One artifact per architecture is the release rule: a fat container would
     * claim both slices while only one had ever been built and tested here. CI
     * asserts the same thing with `lipo -archs`, so failing early is the honest
     * behaviour rather than a surprise at publication time.
     */
    if (kind == KIND_MACHO_FAT) {
        fprintf(stderr,
                "FAIL: %s is a universal (fat) Mach-O. This project publishes one "
                "single-architecture artifact per macOS target; rebuild with "
                "-DCMAKE_OSX_ARCHITECTURES=arm64 or =x86_64\n", path);
        return 0;
    }
#endif
    if (strcmp(format, expected_format()) != 0) {
        fprintf(stderr, "FAIL: %s is %s but this host loads %s\n",
                path, format, expected_format());
        return 0;
    }
    printf("binary format: %s (expected %s)\n", format, expected_format());
    return 1;
}

#if defined(_WIN32)
static void* open_library(const char* path) {
    return (void*)LoadLibraryA(path);
}

static void close_library(void* handle) {
    (void)FreeLibrary((HMODULE)handle);
}

static void* resolve(void* handle, const char* name) {
    /*
     * GetProcAddress yields a function pointer and the caller only needs a
     * non-null answer. Casting a function pointer straight to void* is forbidden
     * by ISO C, so it goes through uintptr_t, which the standard does define as a
     * round-trippable integer type for object pointers and which every supported
     * target also uses for function pointers.
     */
    return (void*)(uintptr_t)GetProcAddress((HMODULE)handle, name);
}
#else
static void* open_library(const char* path) {
    return dlopen(path, RTLD_NOW | RTLD_LOCAL);
}

static void close_library(void* handle) {
    (void)dlclose(handle);
}

static void* resolve(void* handle, const char* name) {
    return dlsym(handle, name);
}
#endif

static int check_symbols(const char* path) {
    void* handle = open_library(path);
    if (handle == NULL) {
#if defined(_WIN32)
        fprintf(stderr, "FAIL: LoadLibrary(%s) failed with error %lu\n",
                path, (unsigned long)GetLastError());
#else
        fprintf(stderr, "FAIL: dlopen(%s) failed: %s\n", path, dlerror());
#endif
        return 0;
    }

    int ok = 1;
    size_t present = 0;
    for (size_t i = 0; i < ARRAY_LENGTH(k_public_exports); ++i) {
        if (resolve(handle, k_public_exports[i]) != NULL) {
            ++present;
        } else {
            fprintf(stderr, "FAIL: missing export %s\n", k_public_exports[i]);
            ok = 0;
        }
    }
    printf("public exports resolved: %zu/%zu\n", present, ARRAY_LENGTH(k_public_exports));

    for (size_t i = 0; i < ARRAY_LENGTH(k_internal_symbols); ++i) {
        if (resolve(handle, k_internal_symbols[i]) != NULL) {
            fprintf(stderr, "FAIL: internal symbol %s leaked into the ABI\n",
                    k_internal_symbols[i]);
            ok = 0;
        }
    }

    for (size_t i = 0; i < ARRAY_LENGTH(k_forbidden_symbols); ++i) {
        if (resolve(handle, k_forbidden_symbols[i]) != NULL) {
            fprintf(stderr, "FAIL: unexpected export %s is present\n",
                    k_forbidden_symbols[i]);
            ok = 0;
        }
    }
    printf("rejected symbols absent: %zu internal + %zu forbidden\n",
           ARRAY_LENGTH(k_internal_symbols), ARRAY_LENGTH(k_forbidden_symbols));

    close_library(handle);
    return ok;
}

int main(int argc, char** argv) {
    if (argc != 2) {
        fprintf(stderr, "usage: %s <path-to-shared-library>\n", argv[0]);
        return 2;
    }

    if (!check_binary_format(argv[1])) {
        return 1;
    }
    if (!check_symbols(argv[1])) {
        return 1;
    }

    printf("export check passed: %s\n", argv[1]);
    return 0;
}
