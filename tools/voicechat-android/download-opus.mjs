import { mkdirSync, existsSync, readFileSync, writeFileSync, renameSync, unlinkSync } from 'node:fs';
import { dirname, resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash, randomUUID } from 'node:crypto';
import { spawnSync } from 'node:child_process';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const dest = join(root, '.build-tools/voicechat-opus');
const sources = [
    {
        name: 'opus4j', version: '2.1.3', repo: 'henkelmax/opus4j',
        ref: 'fb825a992a3aaa61ed4ae4b47ec562394c5715e2',
        url: 'https://codeload.github.com/henkelmax/opus4j/tar.gz/fb825a992a3aaa61ed4ae4b47ec562394c5715e2',
        archive: 'opus4j.tar.gz',
        sha256: '942487a6aa968e7b9cbd3eca2b696ac81c3d42552e0b9da3a692b3d6f2db12c0',
        license: 'opus4j/src/main/resources/OPUS_LICENSE',
    },
    {
        name: 'opus', version: '1.5.2', repo: 'xiph/opus', ref: 'v1.5.2',
        url: 'https://downloads.xiph.org/releases/opus/opus-1.5.2.tar.gz',
        archive: 'opus-1.5.2.tar.gz',
        sha256: '65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1',
        license: 'opus/COPYING',
    },
];

function checkHash(bytes, source) {
    const actual = createHash('sha256').update(bytes).digest('hex');
    if (actual !== source.sha256) throw new Error(`SHA256 mismatch for ${source.name}: ${actual}`);
}

async function getArchive(source) {
    const archive = join(dest, source.archive);
    if (existsSync(archive)) {
        checkHash(readFileSync(archive), source);
        return archive;
    }
    console.log(`Downloading ${source.name} ${source.version}`);
    let bytes;
    for (let attempt = 1; attempt <= 3; attempt++) {
        try {
            const response = await fetch(source.url, { signal: AbortSignal.timeout(60000) });
            if (!response.ok) throw new Error(`HTTP ${response.status}: ${source.url}`);
            bytes = Buffer.from(await response.arrayBuffer());
            break;
        } catch (error) {
            if (attempt === 3) throw error;
        }
    }
    checkHash(bytes, source);
    const temporary = `${archive}.${randomUUID()}.tmp`;
    try {
        writeFileSync(temporary, bytes, { flag: 'wx' });
        renameSync(temporary, archive);
    } finally {
        if (existsSync(temporary)) unlinkSync(temporary);
    }
    return archive;
}

mkdirSync(dest, { recursive: true });
for (const source of sources) {
    const archive = await getArchive(source);
    const folder = join(dest, source.name);
    mkdirSync(folder, { recursive: true });
    // Extraction follows the pinned archive verification. No configure-time network is used.
    const result = spawnSync('tar', ['-xzf', archive, '--strip-components=1', '-C', folder], { encoding: 'utf8' });
    if (result.error || result.status !== 0) throw result.error ?? new Error(result.stderr || 'tar failed');
    if (!existsSync(join(dest, source.license))) throw new Error(`Missing license: ${source.license}`);
    console.log(`${source.name} ${source.version}: SHA256 ${source.sha256}`);
}
writeFileSync(join(dest, 'sources.json'), JSON.stringify(sources, null, 2) + '\n');
console.log('Pinned Opus sources ready.');
