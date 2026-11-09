#include "transform.h"
#include "veltis/native/veltis_module.h"

#include <stdint.h>
#include <stddef.h>
#include <string.h>

int32_t veltis_transform_apply(
        const int32_t *input,
        size_t input_length,
        int32_t salt,
        int32_t *output,
        size_t output_length) {
    size_t i;
    uint32_t salt_bits;
    size_t byte_count;
    uintptr_t input_start;
    uintptr_t output_start;

    if (input_length != output_length) {
        return -6;
    }
    if (input_length == 0) {
        return 0;
    }
    if (input == NULL || output == NULL || input == output) {
        return -1;
    }
    if (input_length > SIZE_MAX / sizeof(*input)) {
        return -1;
    }
    byte_count = input_length * sizeof(*input);
    input_start = (uintptr_t)input;
    output_start = (uintptr_t)output;
    if (input_start > UINTPTR_MAX - byte_count || output_start > UINTPTR_MAX - byte_count
            || (input_start < output_start + byte_count
                    && output_start < input_start + byte_count)) {
        return -1;
    }

    salt_bits = (uint32_t)salt;
    for (i = 0; i < input_length; ++i) {
        uint32_t mixed = (uint32_t)input[i] * UINT32_C(31)
                + salt_bits + (uint32_t)i;
        memcpy(&output[i], &mixed, sizeof(mixed));
    }
    return 0;
}
