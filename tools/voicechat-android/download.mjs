import { mkdirSync, existsSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const dest = join(root, '.build-tools/voicechat-android');
mkdirSync(dest, { recursive: true });
const repos = [
    ['rnnoise4j', 'henkelmax/rnnoise4j', 'adf3a3dba31626a58985b01dbd542c34811d19b9', '0527ff07c8675ee04ad471ab166704a0f296244119b772b8497835e59683df76'],
    ['speex4j', 'henkelmax/speex4j', 'e4eaec7c6fa6ddd2cdbece2cd79799f0f30dbf03', 'f8cfca6d4af6952c94b07b1a0ffc5b8d7d058e5123a82adfdf672ce629d4ee5c'],
    ['rnnoise', 'xiph/rnnoise', '372f7b4b76cde4ca1ec4605353dd17898a99de38', '40ff1568af151959933699fcbf2db3ee3c62fa9559557dbf61a65e7a12cd335d'],
    ['speexdsp', 'xiph/speexdsp', 'SpeexDSP-1.2.1', 'd17ca363654556a4ff1d02cc13d9eb1fc5a8642c90b40bd54ce266c3807b91a7'],
];
async function download(url, path, expectedHash) {
    if (!existsSync(path)) {
        console.log(`Downloading ${url}`);
        const response = await fetch(url, { signal: AbortSignal.timeout(240000) });
        if (!response.ok) throw new Error(`${response.status}: ${url}`);
        const bytes = Buffer.from(await response.arrayBuffer());
        if (expectedHash && createHash('sha256').update(bytes).digest('hex') !== expectedHash) {
            throw new Error(`Download hash mismatch: ${url}`);
        }
        writeFileSync(path, bytes);
    }
    const hash = createHash('sha256').update(readFileSync(path)).digest('hex');
    if (expectedHash && hash !== expectedHash) throw new Error(`Cache hash mismatch: ${path}`);
    console.log(`${path}: SHA256 ${hash}`);
    return hash;
}
const provenance = [];
for (const [name, repo, ref, expectedHash] of repos) {
    const archive = join(dest, `${name}.tar.gz`);
    const url = `https://codeload.github.com/${repo}/tar.gz/${ref}`;
    const sha256 = await download(url, archive, expectedHash);
    const folder = join(dest, name);
    mkdirSync(folder, { recursive: true });
    const result = spawnSync('tar', ['-xzf', archive, '--strip-components=1', '-C', folder], { encoding: 'utf8' });
    if (result.status !== 0) throw new Error(result.stderr);
    provenance.push({ name, repo, ref, url, sha256 });
}
const modelName = 'rnnoise_data-0b50c45.tar.gz';
const url = `https://media.xiph.org/rnnoise/models/${modelName}`;
const sha256 = await download(url, join(dest, modelName),
    '4ac81c5c0884ec4bd5907026aaae16209b7b76cd9d7f71af582094a2f98f4b43');
const modelDir = join(dest, 'model');
mkdirSync(modelDir, { recursive: true });
const result = spawnSync('tar', ['-xzf', join(dest, modelName), '-C', modelDir,
    'src/rnnoise_data_little.c', 'src/rnnoise_data_little.h'], { encoding: 'utf8' });
if (result.status !== 0) throw new Error(result.stderr);
provenance.push({ name: modelName, url, sha256 });
writeFileSync(join(dest, 'sources.json'), JSON.stringify(provenance, null, 2));
console.log('Pinned native sources ready.');
