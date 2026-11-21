// Compare the actual Android JNI wrapper against the pinned original wrapper.
// DSP and JNI are deterministic mocks; real model/lifecycle tests run separately.
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import assert from 'node:assert/strict';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '../..');
const build = join(root, '.tmp/voicechat-wrapper-tests');
const include = join(build, 'include');
mkdirSync(include, { recursive: true });
writeFileSync(join(include, 'jni.h'), `#pragma once
#include <stdint.h>
#include <stddef.h>
typedef int8_t jbyte;
typedef int16_t jshort;
typedef int32_t jint;
typedef int64_t jlong;
typedef int32_t jsize;
typedef float jfloat;
typedef unsigned char jboolean;
typedef void *jobject;
typedef jobject jclass;
struct FakeArray { jsize length; jshort samples[960]; };
typedef struct FakeArray *jarray;
typedef jarray jshortArray;
typedef jarray jbyteArray;
typedef const struct JNINativeInterface_ *JNIEnv;
#define JNIEXPORT
#define JNICALL
#define JNI_ABORT 2
struct JNINativeInterface_ {
    jsize (*GetArrayLength)(JNIEnv *, jarray);
    void (*GetByteArrayRegion)(JNIEnv *, jbyteArray, jsize, jsize, jbyte *);
    void (*GetShortArrayRegion)(JNIEnv *, jshortArray, jsize, jsize, jshort *);
    void (*SetShortArrayRegion)(JNIEnv *, jshortArray, jsize, jsize, const jshort *);
    jshortArray (*NewShortArray)(JNIEnv *, jsize);
    jshort *(*GetShortArrayElements)(JNIEnv *, jshortArray, jboolean *);
    void (*ReleaseShortArrayElements)(JNIEnv *, jshortArray, jshort *, jint);
    jboolean (*ExceptionCheck)(JNIEnv *);
    jclass (*FindClass)(JNIEnv *, const char *);
    jint (*ThrowNew)(JNIEnv *, jclass, const char *);
    void (*DeleteLocalRef)(JNIEnv *, jobject);
};
`);
writeFileSync(join(include, 'stdlib.h'), '#include <stddef.h>\nvoid* mock_malloc(size_t);\nvoid* mock_calloc(size_t,size_t);\nvoid mock_free(void*);\n#define malloc mock_malloc\n#define calloc mock_calloc\n#define free mock_free\n');
writeFileSync(join(include, 'math.h'), 'long mock_lrintf(float);\n#define lrintf mock_lrintf\n');
writeFileSync(join(include, 'rnnoise.h'), `#pragma once
#include <stddef.h>
typedef struct RNNModel RNNModel;
typedef struct DenoiseState DenoiseState;
int rnnoise_get_frame_size(void);
RNNModel *rnnoise_model_from_buffer(const void *, int);
void rnnoise_model_free(RNNModel *);
DenoiseState *rnnoise_create(RNNModel *);
void rnnoise_destroy(DenoiseState *);
float rnnoise_process_frame(DenoiseState *, float *, const float *);
`);
const androidSdk = process.env.VOICECHAT_ANDROID_SDK || 'D:/CodeX/PluginsMaker/VersionSwitch/.build-tools/android-sdk';
const clang = join(androidSdk, 'ndk/27.0.12077973/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe');
const upstream = join(root, '.build-tools/voicechat-android/rnnoise4j/native');
const options = ['--target=wasm32', '-ffreestanding', '-nostdlib', '-O2', '-I', include, '-I', join(upstream, 'include')];
const methods = { createDenoiser0: 'create', getFrameSize0: 'frame', denoise0: 'copy', denoiseInPlace0: 'inplace', getSpeechProbability0: 'vad', destroyDenoiser0: 'destroy' };
function compile(args) {
    const result = spawnSync(clang, [...options, ...args], { encoding: 'utf8' });
    if (result.status !== 0) throw new Error(result.error?.message || result.stderr);
}
for (const [prefix, source] of [['original', join(upstream, 'src/rnnoise.c')], ['optimized', join(here, 'android-rnnoise.c')]]) {
    const renames = Object.entries(methods).map(([from, to]) => `-DJava_de_maxhenkel_rnnoise4j_Denoiser_${from}=${prefix}_${to}`);
    compile([...renames, '-c', source, '-o', join(build, `${prefix}.o`)]);
}
const output = join(build, 'wrapper-test.wasm');
compile([join(here, 'rnnoise_wrapper_test.c'), join(build, 'original.o'), join(build, 'optimized.o'),
    '-o', output, '-Wl,--no-entry', '-Wl,--allow-undefined', '-Wl,--initial-memory=1048576',
    '-Wl,--export=test_wrapper_equivalence', '-Wl,--export=test_wrapper_hot_path', '-Wl,--export=test_wrapper_failures']);
let instance;
const compiled = await WebAssembly.compile(readFileSync(output));
const env = {
    memcpy: (destination, source, size) => {
        new Uint8Array(instance.exports.memory.buffer).copyWithin(destination, source, source + size);
        return destination;
    },
    memset: (destination, value, size) => {
        new Uint8Array(instance.exports.memory.buffer, destination, size).fill(value);
        return destination;
    },
};
instance = await WebAssembly.instantiate(compiled, { env });
for (const [test, description] of [
    ['test_wrapper_equivalence', '600 alternating frames/arrays: PCM clipping/rounding and maximum VAD probability match original'],
    ['test_wrapper_hot_path', '1000 continuous calls: no native wrapper allocation; VAD skips PCM conversion/write'],
    ['test_wrapper_failures', 'constructor allocation failures, JNI errors, invalid input and cleanup'],
]) {
    const line = instance.exports[test]();
    assert.equal(line, 0, `${test} failed at rnnoise_wrapper_test.c:${line}`);
    console.log(`PASS ${description}`);
}
