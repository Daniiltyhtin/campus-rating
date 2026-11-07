import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
const script = fileURLToPath(new URL('../crap.mjs', import.meta.url));
const fixture = name => fileURLToPath(new URL(`fixtures/jacoco-${name}.xml`, import.meta.url));
function run(file) {
  const result = spawnSync(process.execPath, [script, file], { encoding: 'utf8' });
  assert.ifError(result.error);
  return result;
}
async function changed(t, name, transform) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'crap-'));
  t.after(() => fs.rm(root, { recursive: true, force: true }));
  const file = path.join(root, 'jacoco.xml');
  await fs.writeFile(file, transform(await fs.readFile(fixture(name), 'utf8')));
  return run(file);
}
function score(result, name, cc, coverage, crap) {
  const row = result.stdout.split('\n').find(line => line.includes(`#${name}()V\t`));
  assert.ok(row, result.stdout + result.stderr);
  assert.deepEqual(row.split('\t').slice(1), [String(cc), `${coverage}%`, String(crap)]);
}
test('CC 1, coverage 100% gives CRAP 1 and exit 0', () => {
  const result = run(fixture('clean'));
  assert.equal(result.status, 0, result.stderr);
  score(result, '<init>', 1, 100, 1);
});
test('CC 5 without coverage gives CRAP 30, exit 1 and names the method', () => {
  const result = run(fixture('crappy'));
  assert.equal(result.status, 1, result.stderr);
  score(result, 'uncovered', 5, 0, 30);
  assert.match(result.stdout, /CRAP > 10:[\s\S]*Rules#uncovered/);
});
test('CC 5, coverage 100% gives CRAP 5 and passes', () => {
  const result = run(fixture('clean'));
  assert.equal(result.status, 0, result.stderr);
  score(result, 'covered', 5, 100, 5);
});
test('CC 4, coverage 50% gives CRAP 6 using method counters', () => {
  const result = run(fixture('clean'));
  assert.equal(result.status, 0, result.stderr);
  score(result, 'half', 4, 50, 6);
});
test('CRAP exactly 10 passes; 10.1 fails', async t => {
  const clean = run(fixture('clean'));
  assert.equal(clean.status, 0, clean.stderr);
  score(clean, 'boundary', 10, 100, 10);
  const result = await changed(t, 'crappy', xml => xml.replace(/<method name="uncovered"[\s\S]*?<\/method>/, ''));
  assert.equal(result.status, 1, result.stderr);
  score(result, 'justOver', 10, 90, 10.1);
});
test('nested domain is checked; domainless is excluded', async t => {
  assert.equal(run(fixture('crappy')).status, 1);
  const result = await changed(t, 'crappy', xml => xml.replaceAll('ledger/domain/rules', 'domainless'));
  assert.equal(result.status, 0, result.stderr);
  assert.doesNotMatch(result.stdout, /Rules#/);
});
test('complex uncovered methods outside domain do not affect exit status', async t => {
  const result = await changed(t, 'clean', xml => xml.replace('</report>', `
    <package name="ru/campusrating/ledger/service"><class name="ru/campusrating/ledger/service/Service">
      <method name="outside" desc="()V"><counter type="INSTRUCTION" missed="100" covered="0"/>
      <counter type="COMPLEXITY" missed="100" covered="0"/></method>
    </class></package></report>`));
  assert.equal(result.status, 0, result.stderr);
  assert.doesNotMatch(result.stdout, /outside/);
});
test('missing report and broken XML give exit 2 with a diagnostic and no stack', async t => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'crap-missing-'));
  t.after(() => fs.rm(root, { recursive: true, force: true }));
  for (const file of [path.join(root, 'missing.xml'), fixture('broken')]) {
    const result = run(file);
    assert.equal(result.status, 2, result.stderr);
    assert.match(result.stderr, /JaCoCo/);
    assert.doesNotMatch(result.stderr, /\n\s+at |Error:|node:internal/);
  }
});
test('report with no domain methods says нечего проверять and exits 0', async t => {
  const result = await changed(t, 'clean', xml => xml.replace(/<method\b[\s\S]*?<\/method>/g, ''));
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /нечего проверять/i);
});
test('threshold compares the unrounded score', async t => {
  const result = await changed(t, 'crappy', xml => xml
    .replace(/<method name="uncovered"[\s\S]*?<\/method>/, '')
    .replace('missed="1" covered="9"', 'missed="1" covered="999"'));
  assert.equal(result.status, 1, result.stderr);
  assert.match(result.stdout, /CRAP > 10:[\s\S]*Rules#justOver/);
});
test('default package is valid and ignored', async t => {
  const result = await changed(t, 'clean', xml => xml.replace('name="ru/campusrating/ledger/domain"', 'name=""'));
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /нечего проверять/i);
});
for (const [label, transform] of [
  ['empty file', () => ''],
  ['truncated XML', xml => xml.slice(0, -15)],
  ['extra root', xml => xml + '<report/>'],
  ['trailing garbage', xml => xml + 'garbage'],
  ['invalid attribute', xml => xml.replace('missed="0"', 'missed=0')],
  ['unknown entity', xml => xml.replace('&lt;init&gt;', '&unknown;')],
  ['duplicate attribute', xml => xml.replace('missed="0"', 'missed="0" missed="1"')],
  ['missing counter', xml => xml.replace('<counter type="COMPLEXITY" missed="0" covered="1"/>', '')],
  ['invalid counter', xml => xml.replace('missed="0"', 'missed="NaN"')],
  ['zero instruction total', xml => xml.replace('missed="0" covered="3"', 'missed="0" covered="0"')],
]) test(`broken report: ${label}`, async t => {
  const result = await changed(t, 'clean', transform);
  assert.equal(result.status, 2, result.stderr);
  assert.match(result.stderr, /JaCoCo/);
  assert.doesNotMatch(result.stderr, /\n\s+at |Error:|node:internal/);
});
