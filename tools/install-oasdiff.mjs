import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile, chmod } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const platform = process.argv.includes('--linux') ? 'linux' : process.platform;
const archives = {
  'win32-x64': ['windows_amd64', 'd72f1c5c4ba4adddafa4ec3c38f1c34f09df8ddb2d2bdd75869f94145c5bcc6d'],
  'linux-x64': ['linux_amd64', 'eac87307d31d08c36800c58a409c247f1c5db37fbb72e2544402f22a87cbca3c'],
};
const entry = archives[`${platform}-${process.arch}`];
if (!entry) throw new Error(`Unsupported oasdiff platform: ${platform}-${process.arch}`);
const dir = new URL(`../backend/target/tools/${platform}-${process.arch}/`, import.meta.url);
await mkdir(dir, { recursive: true });
const archive = new URL('oasdiff.tar.gz', dir);
let bytes;
try { bytes = await readFile(archive); } catch (error) { if (error.code !== 'ENOENT') throw error; }
if (!bytes || createHash('sha256').update(bytes).digest('hex') !== entry[1]) {
  const response = await fetch(`https://github.com/oasdiff/oasdiff/releases/download/v1.37.0/oasdiff_1.37.0_${entry[0]}.tar.gz`,
    { signal: AbortSignal.timeout(30_000) });
  if (!response.ok) throw new Error(`oasdiff download: HTTP ${response.status}`);
  bytes = Buffer.from(await response.arrayBuffer());
}
if (createHash('sha256').update(bytes).digest('hex') !== entry[1]) throw new Error('oasdiff SHA-256 mismatch');
await writeFile(archive, bytes);
execFileSync('tar', ['-xzf', fileURLToPath(archive), '-C', fileURLToPath(dir)]);
const binary = new URL(platform === 'win32' ? 'oasdiff.exe' : 'oasdiff', dir);
if (platform !== 'win32') await chmod(binary, 0o755);
console.log(`Installed oasdiff 1.37.0 (${platform}-${process.arch}), SHA-256 verified`);
