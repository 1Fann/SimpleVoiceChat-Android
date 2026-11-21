// Execute the same C parser/model initializer used in the Android library.
// This validates model compatibility, not Android audio capture or JNI execution.
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import assert from 'node:assert/strict';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '../..');
const build = join(root, '.tmp/voicechat-model-tests');
const paths = process.argv.slice(2);
if (paths.length === 0) throw new Error('Usage: node test-model.mjs <weights.bin> [weights.bin ...]');
mkdirSync(build, { recursive: true });
const work = mkdtempSync(join(build, 'model-test-'));
const include = join(work, 'include');
mkdirSync(include, { recursive: true });
writeFileSync(join(include, 'stdlib.h'), '#include <stddef.h>\nvoid* calloc(size_t,size_t);\nvoid* realloc(void*,size_t);\nvoid free(void*);\n');
writeFileSync(join(include, 'string.h'), '#include <stddef.h>\nint strcmp(const char*,const char*);\nvoid* memcpy(void*,const void*,size_t);\n');
const native = process.env.VOICECHAT_RNNOISE_SOURCE || join(root, '.tmp/voicechat-android/native-arm64/rnnoise/src');
const output = join(work, 'model-test.wasm');
const androidSdk = process.env.VOICECHAT_ANDROID_SDK || 'D:/CodeX/PluginsMaker/VersionSwitch/.build-tools/android-sdk';
const clang = join(androidSdk, 'ndk/27.0.12077973/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe');
const result = spawnSync(clang, ['--target=wasm32', '-ffreestanding', '-nostdlib', '-O2',
    '-DUSE_WEIGHTS_FILE', '-I', include, '-I', native,
    join(here, 'model_test.c'), join(native, 'parse_lpcnet_weights.c'), join(native, 'rnnoise_data.c'),
    '-o', output, '-Wl,--no-entry', '-Wl,--allow-undefined', '-Wl,--initial-memory=4194304',
    '-Wl,--export=weights_buffer', '-Wl,--export=validate_weights', '-Wl,--export=__heap_base'], { encoding: 'utf8' });
if (result.status !== 0) throw new Error(result.stderr);
const module = await WebAssembly.compile(readFileSync(output));
for (const path of paths) {
    const data = readFileSync(path);
    assert.ok(data.length > 0 && data.length <= 2 * 1024 * 1024, 'Model must fit in the C test buffer');
    let instance, heap;
    const sizes = new Map();
    const allocate = size => {
        const address = heap;
        heap += (size + 15) & ~15;
        if (heap > instance.exports.memory.buffer.byteLength) throw new Error('Test heap full');
        sizes.set(address, size);
        return address;
    };
    const env = {
        calloc: (count, size) => {
            const address = allocate(count * size);
            new Uint8Array(instance.exports.memory.buffer, address, count * size).fill(0);
            return address;
        },
        realloc: (old, size) => {
            const address = allocate(size);
            new Uint8Array(instance.exports.memory.buffer).copyWithin(address, old, old + Math.min(sizes.get(old) || 0, size));
            sizes.delete(old);
            return address;
        },
        free: address => { sizes.delete(address); },
        strcmp: (a, b) => {
            const memory = new Uint8Array(instance.exports.memory.buffer);
            while (memory[a] === memory[b] && memory[a] !== 0) { a++; b++; }
            return memory[a] - memory[b];
        },
    };
    instance = await WebAssembly.instantiate(module, { env });
    heap = instance.exports.__heap_base.value;
    const pointer = instance.exports.weights_buffer();
    const bytes = new Uint8Array(instance.exports.memory.buffer, pointer, data.length);
    bytes.set(data);
    assert.equal(instance.exports.validate_weights(data.length), 0, `Model initialization failed: ${path}`);
    assert.notEqual(instance.exports.validate_weights(data.length - 1), 0, 'Truncated model was not rejected');
    // Rename a required bias array, not the optional float-weight representation.
    let record = 0;
    while (record < data.length && data.subarray(record + 20, record + 64).toString().split('\0')[0] !== 'conv1_bias') {
        assert.ok(record + 64 <= data.length, 'Truncated model record');
        const size = data.readInt32LE(record + 16);
        assert.ok(size > 0 && record + 64 + size <= data.length, 'Invalid model record size');
        record += 64 + size;
    }
    assert.ok(record < data.length, 'Required bias array was not found');
    bytes[record + 20] ^= 1;
    assert.notEqual(instance.exports.validate_weights(data.length), 0, 'Wrong model layout was not rejected');
    bytes.set(data);
    record = 0;
    while (record < data.length && data.subarray(record + 20, record + 64).toString().split('\0')[0] !== 'gru1_input_weights_idx') {
        assert.ok(record + 64 <= data.length, 'Truncated sparse record');
        const size = data.readInt32LE(record + 16);
        assert.ok(size > 0 && record + 64 + size <= data.length, 'Invalid sparse record size');
        record += 64 + size;
    }
    assert.ok(record + 72 <= data.length, 'Sparse weight indices not found');
    const view = new DataView(instance.exports.memory.buffer, pointer, data.length);
    for (const [offset, value, label] of [
        [record + 68, -4, 'negative sparse index'],
        [record + 68, 2147483647, 'overflowing sparse index'],
        [record + 64, -1, 'negative sparse block count'],
        [record + 64, 2147483647, 'overflowing sparse block count'],
    ]) {
        view.setInt32(offset, value, true);
        assert.notEqual(instance.exports.validate_weights(data.length), 0, label + ' was not rejected');
        bytes.set(data);
    }
    console.log(`PASS model initialization + 6 negative checks: ${path}`);
}
