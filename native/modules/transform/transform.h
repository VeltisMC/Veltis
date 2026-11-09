#ifndef VELTIS_TRANSFORM_H
#define VELTIS_TRANSFORM_H

#include <stddef.h>
#include <stdint.h>

int32_t veltis_transform_apply(
        const int32_t *input,
        size_t input_length,
        int32_t salt,
        int32_t *output,
        size_t output_length);

#endif
