import * as fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { existsSync } from 'node:fs';
export const normalize = text => text.replace(/\r\n/g, '\n');
export const header = '<!-- сгенерировано из `.agents/skills`, правь там -->\n';
export function generated(text) {
  return normalize(text).replace(/^(---\n[\s\S]*?\n---\n)/, `$1${header}`);
}
export function rootArgument() {
  if (Number(process.versions.node.split('.')[0]) < 22) throw new Error('Node 22 or newer is required');
  const args = process.argv.slice(2);
  if (args.length) {
    if (args.length !== 2 || args[0] !== '--root') throw new Error('Usage: --root <repository>');
    return path.resolve(args[1]);
  }
  let directory = path.dirname(fileURLToPath(import.meta.url));
  while (!existsSync(path.join(directory, '.git'))) {
    const parent = path.dirname(directory);
    if (parent === directory) throw new Error('Cannot locate git root; use --root');
    directory = parent;
  }
  return directory;
}
export async function files(root) {
  let entries;
  try { entries = await fs.readdir(root, { withFileTypes: true }); }
  catch (error) { if (error.code === 'ENOENT') return []; throw error; }
  const result = [];
  for (const entry of entries) {
    if (['.git', 'node_modules', 'target', 'dist'].includes(entry.name) || entry.name.startsWith('.env')) continue;
    const full = path.join(root, entry.name);
    if (entry.isSymbolicLink()) throw new Error(`${full}: symbolic links are unsupported`);
    if (entry.isDirectory()) result.push(...await files(full));
    else if (entry.isFile()) result.push(full);
  }
  return result.sort();
}
export async function read(file) { return normalize(await fs.readFile(file, 'utf8')); }
export async function exists(file) {
  try { return (await fs.stat(file)).isFile(); }
  catch (error) { if (error.code === 'ENOENT') return false; throw error; }
}
export async function expectedSkills(root) {
  const source = path.join(root, '.agents', 'skills');
  const result = new Map();
  for (const file of await files(source)) {
    const relative = path.relative(source, file);
    const text = await read(file);
    result.set(relative, path.basename(file) === 'SKILL.md' ? generated(text) : text);
  }
  return result;
}
