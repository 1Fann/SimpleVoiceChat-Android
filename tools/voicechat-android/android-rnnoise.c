/*
 * Android JNI implementation adapted from henkelmax/rnnoise4j
 * native/src/rnnoise.c at adf3a3dba31626a58985b01dbd542c34811d19b9.
 * https://github.com/henkelmax/rnnoise4j/blob/adf3a3dba31626a58985b01dbd542c34811d19b9/native/src/rnnoise.c
 * The pinned wrapper has no separate license notice. The RNNoise dependency's
 * notice is retained as META-INF/voicechat-android/RNNoise-COPYING.txt in the JAR.
 * Local changes: instance-owned work buffers, VAD-only conversion avoidance,
 * checked wrapper allocations/JNI calls, and complete failure cleanup.
 */
#include <jni.h>
#include <math.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <rnnoise.h>
#include "exceptions.h"

typedef struct Denoiser {
    DenoiseState *state;
    RNNModel *model;
    uint8_t *model_buffer;
    int frame_size;
    float *input_buffer;
    float *output_buffer;
    jshort *input_pcm_buffer;
    jshort *output_pcm_buffer;
} Denoiser;

static void throw_out_of_memory(JNIEnv *env) {
    if ((*env)->ExceptionCheck(env)) return;
    jclass type = (*env)->FindClass(env, "java/lang/OutOfMemoryError");
    if (type != NULL) {
        (*env)->ThrowNew(env, type, "Could not allocate RNNoise buffers");
        (*env)->DeleteLocalRef(env, type);
    }
}

static void release_denoiser(Denoiser *denoiser) {
    if (denoiser == NULL) return;
    if (denoiser->state != NULL) rnnoise_destroy(denoiser->state);
    /* Requires the buffer-backed model initialization fix in harden-rnnoise.cmake. */
    if (denoiser->model != NULL) rnnoise_model_free(denoiser->model);
    free(denoiser->model_buffer);
    free(denoiser->input_buffer);
    free(denoiser);
}

static Denoiser *get_denoiser(JNIEnv *env, jlong pointer) {
    if (pointer == 0) {
        throw_runtime_exception(env, "Denoiser is closed");
        return NULL;
    }
    return (Denoiser *)(uintptr_t)pointer;
}

JNIEXPORT jlong JNICALL Java_de_maxhenkel_rnnoise4j_Denoiser_createDenoiser0(
    JNIEnv *env, jclass clazz, jbyteArray model
) {
    (void)clazz;
    if (model == NULL) {
        throw_illegal_argument_exception(env, "Model is null");
        return 0;
    }
    jsize model_length = (*env)->GetArrayLength(env, model);
    if ((*env)->ExceptionCheck(env)) return 0;
    if (model_length <= 0) {
        throw_illegal_argument_exception(env, "Model is empty");
        return 0;
    }
    Denoiser *denoiser = calloc(1, sizeof(*denoiser));
    if (denoiser == NULL) {
        throw_out_of_memory(env);
        return 0;
    }
    denoiser->frame_size = rnnoise_get_frame_size();
    const size_t bytes_per_sample = 2 * sizeof(float) + 2 * sizeof(jshort);
    if (denoiser->frame_size <= 0 || (size_t)denoiser->frame_size > SIZE_MAX / bytes_per_sample) {
        throw_io_exception(env, "Invalid RNNoise frame size");
        goto failed;
    }
    denoiser->model_buffer = malloc((size_t)model_length);
    denoiser->input_buffer = malloc((size_t)denoiser->frame_size * bytes_per_sample);
    if (denoiser->model_buffer == NULL || denoiser->input_buffer == NULL) {
        throw_out_of_memory(env);
        goto failed;
    }
    denoiser->output_buffer = denoiser->input_buffer + denoiser->frame_size;
    denoiser->input_pcm_buffer = (jshort *)(denoiser->output_buffer + denoiser->frame_size);
    denoiser->output_pcm_buffer = denoiser->input_pcm_buffer + denoiser->frame_size;
    (*env)->GetByteArrayRegion(env, model, 0, model_length, (jbyte *)denoiser->model_buffer);
    if ((*env)->ExceptionCheck(env)) goto failed;
    denoiser->model = rnnoise_model_from_buffer(denoiser->model_buffer, model_length);
    if (denoiser->model == NULL) {
        throw_io_exception(env, "Could not load model");
        goto failed;
    }
    denoiser->state = rnnoise_create(denoiser->model);
    if (denoiser->state == NULL) {
        throw_io_exception(env, "Could not create denoiser");
        goto failed;
    }
    return (jlong)(uintptr_t)denoiser;
failed:
    release_denoiser(denoiser);
    return 0;
}

JNIEXPORT jint JNICALL Java_de_maxhenkel_rnnoise4j_Denoiser_getFrameSize0(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    return rnnoise_get_frame_size();
}

static jsize validate_input(JNIEnv *env, const Denoiser *denoiser, jshortArray input) {
    if (input == NULL) {
        throw_illegal_argument_exception(env, "Input array is null");
        return 0;
    }
    jsize length = (*env)->GetArrayLength(env, input);
    if ((*env)->ExceptionCheck(env)) return 0;
    if (length <= 0) {
        throw_illegal_argument_exception(env, "Input array is empty");
        return 0;
    }
    if (length % denoiser->frame_size != 0) {
        throw_illegal_argument_exception(env, "Input array is not a multiple of the frame size");
        return 0;
    }
    return length;
}

static bool process_frames(JNIEnv *env, Denoiser *denoiser, jshortArray input,
    jshortArray output, jsize length, jfloat *probability) {
    float maximum = 0.0f;
    const int frame_size = denoiser->frame_size;
    for (jsize offset = 0; offset < length; offset += frame_size) {
        (*env)->GetShortArrayRegion(env, input, offset, frame_size, denoiser->input_pcm_buffer);
        if ((*env)->ExceptionCheck(env)) return false;
        for (int i = 0; i < frame_size; i++) {
            denoiser->input_buffer[i] = (float)denoiser->input_pcm_buffer[i];
        }
        float speech = rnnoise_process_frame(denoiser->state, denoiser->output_buffer, denoiser->input_buffer);
        if (speech > maximum) maximum = speech;
        if (output != NULL) {
            for (int i = 0; i < frame_size; i++) {
                float sample = denoiser->output_buffer[i];
                if (sample >= 32767.0f) denoiser->output_pcm_buffer[i] = 32767;
                else if (sample <= -32768.0f) denoiser->output_pcm_buffer[i] = -32768;
                else denoiser->output_pcm_buffer[i] = (jshort)lrintf(sample);
            }
            (*env)->SetShortArrayRegion(env, output, offset, frame_size, denoiser->output_pcm_buffer);
            if ((*env)->ExceptionCheck(env)) return false;
        }
    }
    *probability = maximum;
    return true;
}

JNIEXPORT jshortArray JNICALL Java_de_maxhenkel_rnnoise4j_Denoiser_denoise0(
    JNIEnv *env, jobject obj, jlong pointer, jshortArray input
) {
    (void)obj;
    Denoiser *denoiser = get_denoiser(env, pointer);
    if (denoiser == NULL) return NULL;
    jsize length = validate_input(env, denoiser, input);
    if (length == 0) return NULL;
    jshortArray output = (*env)->NewShortArray(env, length);
    if (output == NULL || (*env)->ExceptionCheck(env)) return NULL;
    jfloat probability;
    if (!process_frames(env, denoiser, input, output, length, &probability)) {
        (*env)->DeleteLocalRef(env, output);
        return NULL;
    }
    return output;
}

static jfloat denoise_in_place(JNIEnv *env, bool denoise, jlong pointer, jshortArray input) {
    Denoiser *denoiser = get_denoiser(env, pointer);
    if (denoiser == NULL) return 0;
    jsize length = validate_input(env, denoiser, input);
    if (length == 0) return 0;
    jfloat probability;
    if (!process_frames(env, denoiser, input, denoise ? input : NULL, length, &probability)) return 0;
    return probability;
}

JNIEXPORT jfloat JNICALL Java_de_maxhenkel_rnnoise4j_Denoiser_denoiseInPlace0(
    JNIEnv *env, jobject obj, jlong pointer, jshortArray input
) {
    (void)obj;
    return denoise_in_place(env, true, pointer, input);
}

JNIEXPORT jfloat JNICALL Java_de_maxhenkel_rnnoise4j_Denoiser_getSpeechProbability0(
    JNIEnv *env, jobject obj, jlong pointer, jshortArray input
) {
    (void)obj;
    return denoise_in_place(env, false, pointer, input);
}

JNIEXPORT void JNICALL Java_de_maxhenkel_rnnoise4j_Denoiser_destroyDenoiser0(
    JNIEnv *env, jobject obj, jlong pointer
) {
    (void)env;
    (void)obj;
    release_denoiser((Denoiser *)(uintptr_t)pointer);
}
