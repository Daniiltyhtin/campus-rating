import * as fs from 'node:fs/promises';
import path from 'node:path';
import { rootArgument, expectedSkills, files, read } from './lib/agent-docs.mjs';
try {
  const root = rootArgument();
  const destination = path.join(root, '.claude', 'skills');
  const expected = await expectedSkills(root);
  for (const file of await files(destination)) {
    if (!expected.has(path.relative(destination, file))) await fs.rm(file);
  }
  async function prune(directory) {
    let entries;
    try { entries = await fs.readdir(directory, { withFileTypes: true }); }
    catch (error) { if (error.code === 'ENOENT') return; throw error; }
    for (const entry of entries) if (entry.isDirectory()) await prune(path.join(directory, entry.name));
    if (!(await fs.readdir(directory)).length) await fs.rmdir(directory);
  }
  await prune(destination);
  for (const [relative, text] of expected) {
    const file = path.join(destination, relative);
    let current;
    try { current = await read(file); } catch (error) { if (error.code !== 'ENOENT') throw error; }
    if (current === text) continue;
    await fs.mkdir(path.dirname(file), { recursive: true });
    await fs.writeFile(file, text);
  }
  console.log('Skills synchronized');
} catch (error) { console.error(error.message); process.exitCode = 1; }
