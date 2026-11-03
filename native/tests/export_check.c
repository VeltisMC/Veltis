/*
 * Host-side verification of the built shared library.
 *
 * The test does two things the compile-time build cannot prove:
 *
 *   1. it reads the first bytes of the artifact and proves the binary format is
 *      the one the host operating system loads (PE on Windows, ELF on Linux and
 *      the BSDs, Mach-O on macOS), so a mislabelled cross-build or a stale file
 *      from another platform cannot silently pass; and
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

static uint32_t read_u32_le(const unsigned char* bytes) {
    return (uint32_t)bytes[0]
        | ((uint32_t)bytes[1] << 8)
        | ((uint32_t)bytes[2] << 16)
        | ((uint32_t)bytes[3] << 24);
}

static const char* detect_format(const unsigned char* bytes) {
    if (bytes[0] == 0x4Du && bytes[1] == 0x5Au) {
        return "PE";
    }
    if (bytes[0] == 0x7Fu && bytes[1] == 'E' && bytes[2] == 'L' && bytes[3] == 'F') {
        return "ELF";
    }
    switch (read_u32_le(bytes)) {
        case 0xFEEDFACEu: /* MH_MAGIC */
        case 0xFEEDFACFu: /* MH_MAGIC_64 */
        case 0xCAFEBABEu: /* FAT_MAGIC, universal binary */
            return "Mach-O";
        default:
            return NULL;
    }
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
    unsigned char magic[4];
    FILE* file = fopen(path, "rb");
    if (file == NULL) {
        fprintf(stderr, "FAIL: cannot open %s\n", path);
        return 0;
    }
    const size_t read = fread(magic, 1, sizeof(magic), file);
    fclose(file);
    if (read != sizeof(magic)) {
        fprintf(stderr, "FAIL: %s is too short to be a shared library\n", path);
        return 0;
    }

    const char* format = detect_format(magic);
    if (format == NULL) {
        fprintf(stderr, "FAIL: %s has no recognised PE/ELF/Mach-O signature\n", path);
        return 0;
    }
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
    return (void*)GetProcAddress((HMODULE)handle, name);
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
