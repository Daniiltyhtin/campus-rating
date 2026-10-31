import path from 'node:path';
import { rootArgument, expectedSkills, files, read, exists } from './lib/agent-docs.mjs';
try {
  const root = rootArgument();
  const errors = [];
  const report = (file, message) => errors.push(`${path.relative(root, file)}: ${message}`);
  const expected = await expectedSkills(root);
  const destination = path.join(root, '.claude', 'skills');
  for (const [relative, text] of expected) {
    const file = path.join(destination, relative);
    if (!await exists(file)) report(file, 'missing generated copy');
    else if (await read(file) !== text) report(file, 'generated copy differs from source');
  }
  for (const file of await files(destination)) {
    if (!expected.has(path.relative(destination, file))) report(file, 'stale generated copy');
  }
  const all = await files(root);
  const instructions = all.filter(file => ['AGENTS.md', 'CLAUDE.md'].includes(path.basename(file)));
  const sourceRoot = path.join(root, '.agents', 'skills');
  const skillFiles = await files(sourceRoot);
  const names = new Map();
  for (const file of skillFiles.filter(file => path.basename(file) === 'SKILL.md')) {
    const text = await read(file);
    const front = text.match(/^---\n([\s\S]*?)\n---(?:\n|$)/);
    const fields = new Map();
    let invalid = !front;
    for (const line of front?.[1].split('\n') ?? []) {
      const match = line.match(/^(name|description):\s*(.*?)\s*$/);
      if (!match || fields.has(match[1])) { invalid = true; continue; }
      fields.set(match[1], match[2].replace(/^(['"])(.*)\1$/, '$2'));
    }
    if (invalid || fields.size !== 2) report(file, 'frontmatter must contain only name and description');
    const name = fields.get('name') ?? '';
    if (!/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(name) || name !== path.basename(path.dirname(file))) report(file, 'name must match folder and use kebab-case');
    if (names.has(name)) report(file, `duplicate name (also ${path.relative(root, names.get(name))})`);
    names.set(name, file);
    const description = fields.get('description') ?? '';
    if (!description.trim() || [...description].length > 1024) report(file, 'description must contain 1–1024 characters');
  }
  for (const file of instructions.filter(file => path.basename(file) === 'AGENTS.md')) {
    const relative = path.relative(root, file);
    const depth = relative.split(path.sep).length - 1;
    const text = await read(file);
    if (depth > 1) report(file, 'instruction depth exceeds two levels');
    const limit = (depth === 0 ? 10 : 8) * 1024;
    if (Buffer.byteLength(text) > limit) report(file, `instruction budget exceeds ${limit} bytes`);
    const parent = path.join(root, 'AGENTS.md');
    if (depth && await exists(parent) && Buffer.byteLength(text + await read(parent)) > 24 * 1024) report(file, 'chain budget exceeds 24 KiB');
    const claude = path.join(path.dirname(file), 'CLAUDE.md');
    if (!await exists(claude) || !/(?:^|\s)@AGENTS\.md(?:\s|$)/m.test(await read(claude))) report(claude, 'missing @AGENTS.md import');
  }
  const linkFiles = [...instructions.filter(file => path.basename(file) === 'AGENTS.md'), ...skillFiles.filter(file => path.basename(file) === 'SKILL.md')];
  for (const file of linkFiles) {
    const text = await read(file);
    const links = [...text.matchAll(/\]\(\s*<?([^\s)>]+)>?(?:\s+[^)]*)?\)/g)].map(match => match[1]);
    for (const link of links) {
      if (/^(?:[a-z][a-z0-9+.-]*:|#|\/)/i.test(link)) continue;
      const target = decodeURIComponent(link.split('#')[0]);
      if (!await exists(path.resolve(path.dirname(file), target))) report(file, `broken link: ${target}`);
    }
    for (const match of text.matchAll(/`([^`\n]+\.md)`/g)) {
      const target = match[1];
      if (!target.startsWith('references/')) continue;
      if (!await exists(path.resolve(path.dirname(file), target)) && !await exists(path.join(root, target))) report(file, `broken link: ${target}`);
    }
  }
  for (const file of [...instructions, ...skillFiles]) {
    const text = await read(file);
    if (/-----BEGIN (?:[A-Z]+ )?PRIVATE KEY-----|\bgh[pousr]_[A-Za-z0-9]{20,}|\bsk-[A-Za-z0-9_-]{20,}|\b(?:password|passwd|pwd)\s*[:=]\s*["']?[^\s"'`]{3,}/i.test(text)) report(file, 'secret-like value detected [REDACTED]');
  }
  if (errors.length) { console.error(errors.join('\n')); process.exitCode = 1; }
  else console.log('Agent documentation checks passed');
} catch (error) { console.error(error.message); process.exitCode = 1; }
