// Execute the pinned Opus C sources in Wasm and exchange packets with the mod JAR.
// This tests codec behavior and interoperability, not Android JNI/NEON or microphone capture.
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve, delimiter } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import assert from 'node:assert/strict';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '../..');
const candidates = process.argv.slice(2).map(path => resolve(path));
if (!candidates.length) throw new Error('Usage: node test-opus.mjs <candidate.jar> [candidate.jar ...]');
const base = join(root, '.tmp/voicechat-opus-tests');
mkdirSync(base, { recursive: true });
const work = mkdtempSync(join(base, 'codec-'));
const include = join(work, 'include');
mkdirSync(include);
const sourceRoot = join(root, '.build-tools/voicechat-opus/opus');
const headers = {
    'stdlib.h': '#include <stddef.h>\nvoid *malloc(size_t); void *calloc(size_t,size_t); void *realloc(void*,size_t); void free(void*); __attribute__((noreturn)) void abort(void); int abs(int); long labs(long);\n',
    'string.h': '#include <stddef.h>\nvoid *memcpy(void*,const void*,size_t); void *memmove(void*,const void*,size_t); void *memset(void*,int,size_t); int memcmp(const void*,const void*,size_t);\n',
    'stdio.h': 'typedef struct FILE FILE; extern FILE *stderr; int fprintf(FILE*,const char*,...);\n',
    'math.h': 'double sin(double); double cos(double); double sqrt(double); double exp(double); double log(double); double log10(double); double pow(double,double); double floor(double); double ceil(double); double fabs(double); double acos(double); double atan(double); double atan2(double,double); double fmod(double,double); float sinf(float); float cosf(float); float sqrtf(float); float expf(float); float logf(float); float log10f(float); float powf(float,float); float floorf(float); float ceilf(float); float fabsf(float); float acosf(float); float atan2f(float,float); float fmodf(float,float); long lrint(double); long lrintf(float);\n#define isnan(x) __builtin_isnan(x)\n#define isinf(x) __builtin_isinf(x)\n#define isfinite(x) __builtin_isfinite(x)\n#define M_PI 3.14159265358979323846\n',
};
for (const [name, body] of Object.entries(headers)) writeFileSync(join(include, name), body);
const sources = [];
for (const [file, keys] of [
    ['opus_sources.mk', ['OPUS_SOURCES', 'OPUS_SOURCES_FLOAT']],
    ['celt_sources.mk', ['CELT_SOURCES']],
    ['silk_sources.mk', ['SILK_SOURCES', 'SILK_SOURCES_FLOAT']],
]) {
    const data = readFileSync(join(sourceRoot, file), 'utf8').replace(/\\\r?\n/g, ' ');
    for (const key of keys) {
        const match = data.match(new RegExp('^' + key + ' *= *([^\\r\\n]*)', 'm'));
        if (!match) throw new Error('Missing source list: ' + key);
        sources.push(...match[1].trim().split(/\s+/).map(source => join(sourceRoot, source)));
    }
}
function run(command, args) {
    const result = spawnSync(command, args, { encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 });
    if (result.error || result.status !== 0) throw result.error ?? new Error(result.stderr || result.stdout || command + ' failed');
    if (result.stdout.trim()) console.log(result.stdout.trim());
}
const androidSdk = process.env.VOICECHAT_ANDROID_SDK || 'D:/CodeX/PluginsMaker/VersionSwitch/.build-tools/android-sdk';
const clang = join(androidSdk, 'ndk/27.0.12077973/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe');
const wasmPath = join(work, 'opus.wasm');
run(clang, ['--target=wasm32', '-ffreestanding', '-nostdlib', '-O2', '-DOPUS_BUILD', '-DVAR_ARRAYS',
    '-DPACKAGE_VERSION="1.5.2"', '-DHAVE_LRINT', '-DHAVE_LRINTF', '-DENABLE_HARDENING',
    '-I' + include, ...['', 'include', 'celt', 'silk', 'silk/float'].map(path => '-I' + join(sourceRoot, path)),
    ...sources, '-o', wasmPath, '-Wl,--no-entry', '-Wl,--allow-undefined', '-Wl,--initial-memory=16777216',
    '-Wl,-z,stack-size=2097152', ...['opus_encoder_create', 'opus_decoder_create', 'opus_encode', 'opus_decode',
    'opus_encoder_destroy', 'opus_decoder_destroy', '__heap_base'].map(name => '-Wl,--export=' + name)]);
const module = await WebAssembly.compile(readFileSync(wasmPath));
const java = process.env.VOICECHAT_JAVA || 'D:/Java/jdk-21/bin/java.exe';
const javac = process.env.VOICECHAT_JAVAC || 'D:/Java/jdk-21/bin/javac.exe';
run(javac, ['--release', '8', '-Xlint:-options', '-proc:none', '-cp', candidates[0], '-d', work, join(here, 'OpusInteropTest.java')]);

async function testSource(fixtureDir) {
    let instance, heap;
    const memory = () => new Uint8Array(instance.exports.memory.buffer);
    const allocate = size => {
        const pointer = heap;
        heap = Math.ceil((heap + size) / 16) * 16;
        assert.ok(size >= 0 && heap < memory().length, 'Wasm test heap exhausted');
        return pointer;
    };
    const roundEven = value => {
        const floor = Math.floor(value);
        return value - floor === 0.5 ? (floor % 2 === 0 ? floor : floor + 1) : Math.round(value);
    };
    const env = {
        fabs: Math.abs, abs: Math.abs, exp: Math.exp, sqrt: Math.sqrt, floor: Math.floor,
        log: Math.log, log10: Math.log10, cos: Math.cos, pow: Math.pow, lrintf: roundEven,
        malloc: allocate, free: () => {},
        memcpy: (target, from, length) => { memory().copyWithin(target, from, from + length); return target; },
        memmove: (target, from, length) => { memory().copyWithin(target, from, from + length); return target; },
        memset: (target, value, length) => { memory().fill(value, target, target + length); return target; },
        fprintf: () => 0, abort: () => { throw new Error('Opus assertion aborted'); },
    };
    instance = await WebAssembly.instantiate(module, { env });
    heap = instance.exports.__heap_base.value;
    const codec = instance.exports;
    const error = allocate(4), pcm = allocate(1920), packet = allocate(1275), output = allocate(1920);
    const encoder = codec.opus_encoder_create(48000, 1, 2048, error);
    assert.ok(encoder > 0);
    const packets = [];
    for (let frame = 0; frame < 100; frame++) {
        const audio = new Int16Array(instance.exports.memory.buffer, pcm, 960);
        for (let i = 0; i < audio.length; i++) {
            const t = (frame * 960 + i) / 48000;
            audio[i] = 9000 * Math.sin(2 * Math.PI * 440 * t) + 3000 * Math.sin(2 * Math.PI * 173 * t);
        }
        const bytes = codec.opus_encode(encoder, pcm, 960, packet, 1275);
        assert.ok(bytes > 0 && bytes <= 1275);
        const length = Buffer.alloc(4);
        length.writeInt32BE(bytes);
        packets.push(length, Buffer.from(memory().subarray(packet, packet + bytes)));
    }
    writeFileSync(join(fixtureDir, 'wasm-packets.bin'), Buffer.concat(packets));
    assert.ok(codec.opus_encode(encoder, pcm, 959, packet, 1275) < 0, 'Invalid PCM frame accepted');
    codec.opus_encoder_destroy(encoder);
    for (const name of ['java', 'native']) {
        const decoder = codec.opus_decoder_create(48000, 1, error);
        assert.ok(decoder > 0);
        const data = readFileSync(join(fixtureDir, name + '-packets.bin'));
        let offset = 0, energy = 0;
        for (let frame = 0; frame < 100; frame++) {
            const size = data.readInt32BE(offset); offset += 4;
            assert.ok(size > 0 && size <= 1275 && offset + size <= data.length);
            memory().set(data.subarray(offset, offset + size), packet); offset += size;
            assert.equal(codec.opus_decode(decoder, packet, size, output, 960, 0), 960);
            for (const sample of new Int16Array(instance.exports.memory.buffer, output, 960)) energy += sample * sample;
        }
        assert.equal(offset, data.length);
        assert.ok(energy > 100000000, 'Silent decoded signal');
        assert.equal(codec.opus_decode(decoder, 0, 0, output, 960, 0), 960, 'PLC failed');
        memory().set([255, 63], packet);
        assert.ok(codec.opus_decode(decoder, packet, 2, output, 960, 0) < 0, 'Malformed packet accepted');
        codec.opus_decoder_destroy(decoder);
        console.log('PASS ' + name + ' JAR encoder -> source-built Opus: 100 frames, audio energy, PLC, malformed packet rejection');
    }
}
for (let index = 0; index < candidates.length; index++) {
    const candidate = candidates[index];
    const fixtures = join(work, 'fixture-' + index);
    mkdirSync(fixtures);
    const classpath = [work, candidate].join(delimiter);
    run(java, ['-Xverify:all', '-cp', classpath, 'OpusInteropTest', 'generate', fixtures]);
    await testSource(fixtures);
    run(java, ['-Xverify:all', '-cp', classpath, 'OpusInteropTest', 'check', fixtures]);
    console.log('PASS Opus source/JAR interoperability: ' + candidate);
}
