/* Executes both the pinned JNI wrapper and Android wrapper with deterministic
 * DSP and fault-injectable JNI. Real RNNoise model/lifecycle tests are separate. */
#include <jni.h>
#include <rnnoise.h>
#include <stdlib.h>
#include <stdint.h>

#define DECLARE_API(prefix) \
    jlong prefix##_create(JNIEnv *, jclass, jbyteArray); \
    jint prefix##_frame(JNIEnv *, jclass); \
    jshortArray prefix##_copy(JNIEnv *, jobject, jlong, jshortArray); \
    jfloat prefix##_inplace(JNIEnv *, jobject, jlong, jshortArray); \
    jfloat prefix##_vad(JNIEnv *, jobject, jlong, jshortArray); \
    void prefix##_destroy(JNIEnv *, jobject, jlong)
DECLARE_API(original);
DECLARE_API(optimized);
#define CHECK(value) do { if (!(value)) return __LINE__; } while (0)

static unsigned char heap_slots[32][8192] __attribute__((aligned(16)));
static int live_slots[32], live_allocations, allocation_calls, fail_allocation;
static int allocation_errors, exception, illegal_jni, round_calls, process_calls;
static int fail_jni, fail_model, fail_state, fail_frame;
static int read_calls, write_calls;
enum { FAIL_NONE, FAIL_LENGTH, FAIL_MODEL_READ, FAIL_NEW_ARRAY, FAIL_READ, FAIL_WRITE };

void *mock_malloc(size_t size) {
    allocation_calls++;
    if (allocation_calls == fail_allocation) return NULL;
    if (size > sizeof(heap_slots[0])) { allocation_errors++; return NULL; }
    for (int i = 0; i < 32; i++) if (!live_slots[i]) {
        live_slots[i] = 1;
        live_allocations++;
        for (size_t j = 0; j < sizeof(heap_slots[i]); j++) heap_slots[i][j] = 0xA5;
        return heap_slots[i];
    }
    allocation_errors++;
    return NULL;
}
void *mock_calloc(size_t count, size_t size) {
    unsigned char *p = mock_malloc(count * size);
    if (p) for (size_t i = 0; i < count * size; i++) p[i] = 0;
    return p;
}
void mock_free(void *pointer) {
    if (!pointer) return;
    for (int i = 0; i < 32; i++) if (pointer == heap_slots[i] && live_slots[i]) {
        live_slots[i] = 0;
        live_allocations--;
        return;
    }
    allocation_errors++;
}
long mock_lrintf(float value) {
    round_calls++;
    long result = (long)value;
    float fraction = value - (float)result;
    if (fraction > 0.5f || (fraction == 0.5f && (result & 1))) result++;
    if (fraction < -0.5f || (fraction == -0.5f && (result & 1))) result--;
    return result;
}

struct RNNModel { int unused; };
struct DenoiseState { int frame; };
int rnnoise_get_frame_size(void) { return fail_frame ? 0 : 480; }
RNNModel *rnnoise_model_from_buffer(const void *buffer, int length) {
    (void)buffer; (void)length;
    return fail_model ? NULL : mock_malloc(sizeof(RNNModel));
}
void rnnoise_model_free(RNNModel *model) { mock_free(model); }
DenoiseState *rnnoise_create(RNNModel *model) {
    (void)model;
    DenoiseState *state = fail_state ? NULL : mock_malloc(sizeof(DenoiseState));
    if (state) state->frame = 0;
    return state;
}
void rnnoise_destroy(DenoiseState *state) { mock_free(state); }
float rnnoise_process_frame(DenoiseState *state, float *output, const float *input) {
    process_calls++;
    state->frame++;
    for (int i = 0; i < 480; i++) output[i] = input[i] * 1.125f + (state->frame % 7) - 3.5f;
    output[0] = 40000.f;
    output[1] = -40000.f;
    output[2] = 0.5f;
    output[3] = 1.5f;
    output[4] = -2.5f;
    return (state->frame % 11) / 10.f;
}

static int trigger(int operation) {
    if (exception) illegal_jni++;
    if (fail_jni == operation) {
        fail_jni = FAIL_NONE;
        exception = 1;
        return 1;
    }
    return 0;
}
static jsize get_length(JNIEnv *env, jarray array) {
    (void)env;
    return trigger(FAIL_LENGTH) ? 0 : array->length;
}
static void get_bytes(JNIEnv *env, jbyteArray array, jsize start, jsize length, jbyte *output) {
    (void)env;
    if (trigger(FAIL_MODEL_READ)) return;
    for (int i = 0; i < length; i++) output[i] = ((jbyte *)array->samples)[start + i];
}
static void get_shorts(JNIEnv *env, jshortArray array, jsize start, jsize length, jshort *output) {
    (void)env;
    read_calls++;
    if (trigger(FAIL_READ)) return;
    for (int i = 0; i < length; i++) output[i] = array->samples[start + i];
}
static void set_shorts(JNIEnv *env, jshortArray array, jsize start, jsize length, const jshort *input) {
    (void)env;
    write_calls++;
    if (trigger(FAIL_WRITE)) return;
    for (int i = 0; i < length; i++) array->samples[start + i] = input[i];
}
static struct FakeArray outputs[4];
static int next_output;
static jshortArray new_shorts(JNIEnv *env, jsize length) {
    (void)env;
    if (trigger(FAIL_NEW_ARRAY)) return NULL;
    struct FakeArray *result = &outputs[(next_output++) % 4];
    result->length = length;
    for (int i = 0; i < length; i++) result->samples[i] = 0;
    return result;
}
static jshort *get_elements(JNIEnv *env, jshortArray array, jboolean *copy) {
    (void)env; (void)copy;
    return array->samples;
}
static void release_elements(JNIEnv *env, jshortArray array, jshort *elements, jint mode) {
    (void)env; (void)array; (void)elements; (void)mode;
}
static jboolean check_exception(JNIEnv *env) { (void)env; return exception != 0; }
static jclass find_class(JNIEnv *env, const char *name) { (void)env; (void)name; return (jclass)(uintptr_t)1; }
static jint throw_new(JNIEnv *env, jclass type, const char *message) {
    (void)env; (void)type; (void)message; exception = 1; return 0;
}
static void delete_reference(JNIEnv *env, jobject object) { (void)env; (void)object; }
void throw_runtime_exception(JNIEnv *env, const char *message) { (void)env; (void)message; exception = 1; }
void throw_io_exception(JNIEnv *env, const char *message) { (void)env; (void)message; exception = 1; }
void throw_illegal_argument_exception(JNIEnv *env, const char *message) { (void)env; (void)message; exception = 1; }

static const struct JNINativeInterface_ functions = {
    get_length, get_bytes, get_shorts, set_shorts, new_shorts,
    get_elements, release_elements, check_exception, find_class, throw_new, delete_reference
};
static JNIEnv environment = &functions;
static struct FakeArray model = { 1, { 7 } };
static struct FakeArray input_a, input_b, saved_input;
static void fill(struct FakeArray *array, int seed) {
    array->length = (seed % 2) ? 480 : 960;
    for (int i = 0; i < array->length; i++) array->samples[i] = (jshort)((i * 7919 + seed * 239) % 65536 - 32768);
}
static int equal(jshortArray a, jshortArray b) {
    if (a->length != b->length) return 0;
    for (int i = 0; i < a->length; i++) if (a->samples[i] != b->samples[i]) return 0;
    return 1;
}
static void reset(void) {
    allocation_calls = fail_allocation = allocation_errors = exception = illegal_jni = 0;
    round_calls = process_calls = fail_jni = fail_model = fail_state = fail_frame = 0;
    read_calls = write_calls = next_output = 0;
}

int test_wrapper_equivalence(void) {
    CHECK(live_allocations == 0);
    reset();
    jlong reference = original_create(&environment, NULL, &model);
    jlong changed = optimized_create(&environment, NULL, &model);
    CHECK(reference && changed && !exception);
    CHECK(optimized_frame(&environment, NULL) == original_frame(&environment, NULL));
    for (int i = 0; i < 600; i++) {
        fill(&input_a, i); input_b = input_a; saved_input = input_a;
        int mode = i % 3;
        if (mode == 0) {
            jshortArray a = original_copy(&environment, NULL, reference, &input_a);
            jshortArray b = optimized_copy(&environment, NULL, changed, &input_b);
            CHECK(a && b && equal(a, b));
            CHECK(equal(&input_a, &saved_input) && equal(&input_b, &saved_input));
        } else if (mode == 1) {
            float a = original_inplace(&environment, NULL, reference, &input_a);
            float b = optimized_inplace(&environment, NULL, changed, &input_b);
            CHECK(a == b && equal(&input_a, &input_b));
        } else {
            float a = original_vad(&environment, NULL, reference, &input_a);
            round_calls = write_calls = 0;
            float b = optimized_vad(&environment, NULL, changed, &input_b);
            CHECK(a == b && equal(&input_b, &saved_input));
            CHECK(round_calls == 0 && write_calls == 0);
        }
        CHECK(!exception && !illegal_jni && !allocation_errors);
    }
    original_destroy(&environment, NULL, reference);
    optimized_destroy(&environment, NULL, changed);
    CHECK(live_allocations == 0 && allocation_errors == 0);
    return 0;
}

int test_wrapper_hot_path(void) {
    CHECK(live_allocations == 0);
    reset();
    jlong changed = optimized_create(&environment, NULL, &model);
    CHECK(changed && !exception);
    int initial = allocation_calls;
    for (int i = 0; i < 1000; i++) {
        fill(&input_a, i);
        if (i % 3 == 0) CHECK(optimized_copy(&environment, NULL, changed, &input_a) != NULL);
        else if (i % 3 == 1) optimized_inplace(&environment, NULL, changed, &input_a);
        else optimized_vad(&environment, NULL, changed, &input_a);
        CHECK(allocation_calls == initial && !exception);
    }
    optimized_destroy(&environment, NULL, changed);
    optimized_destroy(&environment, NULL, 0);
    CHECK(live_allocations == 0 && allocation_errors == 0);
    return 0;
}

int test_wrapper_failures(void) {
    CHECK(live_allocations == 0);
    for (int i = 1; i <= 5; i++) {
        reset(); fail_allocation = i;
        CHECK(optimized_create(&environment, NULL, &model) == 0);
        CHECK(exception && live_allocations == 0 && !allocation_errors && !illegal_jni);
    }
    for (int i = 0; i < 5; i++) {
        reset();
        if (i == 0) fail_jni = FAIL_LENGTH;
        if (i == 1) fail_jni = FAIL_MODEL_READ;
        if (i == 2) fail_model = 1;
        if (i == 3) fail_state = 1;
        if (i == 4) fail_frame = 1;
        CHECK(optimized_create(&environment, NULL, &model) == 0);
        CHECK(exception && live_allocations == 0 && !allocation_errors && !illegal_jni);
    }
    reset();
    CHECK(optimized_create(&environment, NULL, NULL) == 0 && exception);
    reset(); model.length = 0;
    CHECK(optimized_create(&environment, NULL, &model) == 0 && exception);
    model.length = 1;
    reset();
    jlong changed = optimized_create(&environment, NULL, &model);
    CHECK(changed && !exception);
    int initial = allocation_calls;
    for (int mode = 0; mode < 3; mode++) for (int failure = 0; failure < 7; failure++) {
        exception = 0; fill(&input_a, 0); process_calls = read_calls = write_calls = 0;
        jshortArray input = &input_a;
        jlong pointer = changed;
        if (failure == 0) input = NULL;
        if (failure == 1) input_a.length = 0;
        if (failure == 2) input_a.length = 479;
        if (failure == 3) pointer = 0;
        if (failure == 4) fail_jni = FAIL_LENGTH;
        if (failure == 5) fail_jni = FAIL_READ;
        if (failure == 6) { if (mode == 2) continue; fail_jni = FAIL_WRITE; }
        if (mode == 0) CHECK(optimized_copy(&environment, NULL, pointer, input) == NULL);
        if (mode == 1) CHECK(optimized_inplace(&environment, NULL, pointer, input) == 0);
        if (mode == 2) CHECK(optimized_vad(&environment, NULL, pointer, input) == 0);
        CHECK(exception && !illegal_jni && allocation_calls == initial);
        if (failure != 6) CHECK(process_calls == 0 && write_calls == 0);
        else CHECK(process_calls == 1 && write_calls == 1 && read_calls == 1);
    }
    exception = 0; fail_jni = FAIL_NEW_ARRAY; process_calls = 0;
    CHECK(optimized_copy(&environment, NULL, changed, &input_a) == NULL);
    CHECK(exception && process_calls == 0 && !illegal_jni);
    exception = 0;
    fill(&input_a, 3);
    optimized_inplace(&environment, NULL, changed, &input_a);
    CHECK(!exception && allocation_calls == initial);
    optimized_destroy(&environment, NULL, changed);
    CHECK(live_allocations == 0 && !allocation_errors);
    return 0;
}
