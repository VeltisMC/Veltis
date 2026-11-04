/*
 * Link probe for VELTIS_NATIVE_SANITIZERS.
 *
 * Nothing here is a test. The point is that a compiler invocation with the
 * requested -fsanitize flag must both compile and *link*, because a toolchain
 * that accepts the flag but ships no sanitizer runtime fails at ld — and a build
 * that quietly produced an uninstrumented binary would later be read as evidence
 * that a sanitizer had run.
 *
 * The body allocates and frees, which is enough to pull in the runtime's
 * interception symbols on every supported toolchain.
 */
#include <cstdlib>

int main(int argc, char** argv) {
    if (argc > 100000) {
        int* block = static_cast<int*>(std::malloc(sizeof(int) * 4));
        if (block == nullptr) {
            return 1;
        }
        block[0] = argv[0] == nullptr ? 0 : 1;
        const int value = block[0];
        std::free(block);
        return value;
    }
    return 0;
}
