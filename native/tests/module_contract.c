#include "transform.h"
#include "veltis/native/veltis_module.h"

#include <stddef.h>
#include <stdint.h>

int main(void) {
    static const int32_t input[] = {0, 1, -1, 123456789};
    static const int32_t expected[] = {17, 49, -12, -467806817};
    int32_t output[4] = {0};
    size_t i;

    if (veltis_transform_apply(input, 4, 17, output, 4) != 0) {
        return 1;
    }
    for (i = 0; i < 4; ++i) {
        if (output[i] != expected[i]) {
            return 2;
        }
    }
    if (veltis_transform_apply(input, 4, 17, output, 3) != -6) {
        return 3;
    }
    if (veltis_transform_apply(NULL, 0, 17, NULL, 0) != 0) {
        return 4;
    }
    return VELTIS_MODULE_ABI_VERSION == 1 ? 0 : 5;
}
