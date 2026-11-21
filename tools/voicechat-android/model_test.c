#include "rnnoise_data.h"
#include <stdlib.h>

static unsigned char weights[2 * 1024 * 1024];

unsigned char *weights_buffer(void) {
    return weights;
}

int validate_weights(int length) {
    WeightArray *arrays = NULL;
    RNNoise model;
    if (length <= 0 || length > sizeof(weights)) return -2;
    if (parse_weights(&arrays, weights, length) <= 0) return -1;
    int result = init_rnnoise(&model, arrays);
    free(arrays);
    return result;
}
