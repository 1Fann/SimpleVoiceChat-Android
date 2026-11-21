#include <rnnoise.h>
#include <stddef.h>

static unsigned char weights[2 * 1024 * 1024];
unsigned char *lifecycle_weights_buffer(void) { return weights; }

static int create_and_close(const unsigned char *data, int length) {
    RNNModel *model = rnnoise_model_from_buffer(data, length);
    if (model == NULL) return 1;
    DenoiseState *state = rnnoise_create(model);
    int failed = state == NULL;
    if (state != NULL) rnnoise_destroy(state);
    rnnoise_model_free(model);
    return failed;
}

int test_model_lifecycle(void) {
    static const unsigned char invalid_model[1] = {0};
    return create_and_close(invalid_model, sizeof(invalid_model)) ? 0 : -1;
}
int test_valid_model_lifecycle(int length) {
    if (length <= 0 || length > sizeof(weights)) return -1;
    return create_and_close(weights, length);
}
