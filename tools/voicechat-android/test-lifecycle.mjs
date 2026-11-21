// Run the actual RNNoise constructor/failure cleanup with poisoned allocations.
// This covers native ownership, not Android JNI loading or audio capture.
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import assert from 'node:assert/strict';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '../..');
const native = resolve(process.env.VOICECHAT_RNNOISE_SOURCE || join(root, '.tmp/voicechat-android/native-arm64/rnnoise/src'));
const parent = join(root, '.tmp/voicechat-lifecycle-tests');
mkdirSync(parent, { recursive: true });
const build = mkdtempSync(join(parent, 'run-'));
const include = join(build, 'include');
mkdirSync(include, { recursive: true });
writeFileSync(join(include, 'stdlib.h'), '#include <stddef.h>\nvoid* malloc(size_t);\nvoid* calloc(size_t,size_t);\nvoid* realloc(void*,size_t);\nvoid free(void*);\n');
writeFileSync(join(include, 'string.h'), '#include <stddef.h>\nint strcmp(const char*,const char*);\nvoid* memcpy(void*,const void*,size_t);\nvoid* memmove(void*,const void*,size_t);\nvoid* memset(void*,int,size_t);\n');
writeFileSync(join(include, 'math.h'), 'double sqrt(double);\ndouble log10(double);\n');
writeFileSync(join(include, 'stdio.h'), '#pragma once\n#include <stddef.h>\ntypedef struct test_file FILE;\n#define SEEK_SET 0\n#define SEEK_END 2\nFILE* fopen(const char*,const char*);\nint fclose(FILE*);\nint fseek(FILE*,long,int);\nlong ftell(FILE*);\nsize_t fread(void*,size_t,size_t,FILE*);\n');
const androidSdk = process.env.VOICECHAT_ANDROID_SDK || 'D:/CodeX/PluginsMaker/VersionSwitch/.build-tools/android-sdk';
const clang = join(androidSdk, 'ndk/27.0.12077973/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe');
const output = join(build, 'lifecycle-test.wasm');
const result = spawnSync(clang, ['--target=wasm32', '-ffreestanding', '-nostdlib', '-O2', '-ffunction-sections', '-fdata-sections',
    '-DUSE_WEIGHTS_FILE', '-I', include, '-I', native, '-I', join(native, '../include'),
    join(here, 'lifecycle_test.c'), join(native, 'denoise.c'), join(native, 'parse_lpcnet_weights.c'), join(native, 'rnnoise_data.c'),
    '-o', output, '-Wl,--gc-sections', '-Wl,--no-entry', '-Wl,--allow-undefined', '-Wl,--initial-memory=4194304',
    '-Wl,--export=test_model_lifecycle', '-Wl,--export=test_valid_model_lifecycle', '-Wl,--export=lifecycle_weights_buffer', '-Wl,--export=__heap_base'], { encoding: 'utf8' });
if (result.status !== 0) throw new Error(result.error?.message || result.stderr);
let instance, heap, closeCalls = 0, allocationCalls = 0, failAt = 0;
const sizes = new Map();
const allocate = (size, fill) => {
    if (++allocationCalls === failAt) return 0;
    const address = heap;
    heap += (size + 15) & ~15;
    if (heap > instance.exports.memory.buffer.byteLength) throw new Error('Test heap full');
    sizes.set(address, size);
    new Uint8Array(instance.exports.memory.buffer, address, size).fill(fill);
    return address;
};
const env = {
    malloc: size => allocate(size, 0xA5),
    calloc: (count, size) => allocate(count * size, 0),
    realloc: (old, size) => {
        const address = allocate(size, 0xA5);
        if (!address) return 0;
        new Uint8Array(instance.exports.memory.buffer).copyWithin(address, old, old + Math.min(sizes.get(old) || 0, size));
        sizes.delete(old);
        return address;
    },
    free: address => { if (address) assert.ok(sizes.delete(address), 'Unknown or double free'); },
    memset: (address, value, length) => { new Uint8Array(instance.exports.memory.buffer, address, length).fill(value); return address; },
    strcmp: (a, b) => {
        const memory = new Uint8Array(instance.exports.memory.buffer);
        while (memory[a] === memory[b] && memory[a] !== 0) { a++; b++; }
        return memory[a] - memory[b];
    },
    fclose: () => { closeCalls++; return 0; },
};
const compiled = await WebAssembly.compile(readFileSync(output));

async function run(data, failAllocation) {
    failAt = failAllocation; allocationCalls = 0; closeCalls = 0; sizes.clear();
    instance = await WebAssembly.instantiate(compiled, { env });
    heap = instance.exports.__heap_base.value;
    if (data) {
        assert.ok(data.length > 0 && data.length <= 2 * 1024 * 1024, 'Model exceeds test buffer');
        new Uint8Array(instance.exports.memory.buffer, instance.exports.lifecycle_weights_buffer(), data.length).set(data);
        assert.equal(instance.exports.test_valid_model_lifecycle(data.length), failAt ? 1 : 0, 'Valid model / allocation failure status');
    } else {
        assert.equal(instance.exports.test_model_lifecycle(), 0, 'Invalid model was accepted');
    }
    assert.equal(closeCalls, 0, 'Buffer-backed cleanup attempted to close an unowned FILE');
    assert.equal(sizes.size, 0, 'Model cleanup leaked allocations');
    return allocationCalls;
}
const invalidAllocations = await run(null, 0);
for (let i = 1; i <= invalidAllocations; i++) await run(null, i);
console.log('PASS invalid model cleanup + ' + invalidAllocations + ' allocation failures: no fclose, leaks or double frees');
for (const file of process.argv.slice(2)) {
    const data = readFileSync(file);
    const allocations = await run(data, 0);
    for (let i = 1; i <= allocations; i++) await run(data, i);
    console.log('PASS valid model lifecycle + ' + allocations + ' allocation failures: ' + file);
}
