import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const deck = await readFile(new URL('../../frontend/public/presentation/index.html', import.meta.url), 'utf8');
const sources = await readFile(new URL('../../docs/presentation/slides/sources.md', import.meta.url), 'utf8');
const slides = [...deck.matchAll(/<section class="slide"([^>]*)>([\s\S]*?)<\/section>/g)].map(([, attrs, body]) => ({ attrs, body }));

test('U20: презентация — 21 слайд, разделы 1–11 по порядку, без слайдов-разделителей', () => {
  assert.equal(slides.length, 21);
  const order = slides.map(s => Number(s.attrs.match(/data-section="(\d+)"/)[1]));
  assert.deepEqual([...new Set(order)], [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11]);
  assert.deepEqual(order, [...order].sort((a, b) => a - b));
  assert.equal(slides.filter(s => /data-demo-step="\d"/.test(s.attrs)).length, 7);
});

test('U20: в колонке не больше 5 пунктов, кроме таблицы модулей ТЗ', () => {
  for (const [i, { attrs, body }] of slides.entries()) {
    const groups = [...body.matchAll(/<(ul|table)\b[^>]*>([\s\S]*?)<\/\1>/g)];
    for (const [, tag, inner] of groups) {
      const count = (inner.match(tag === 'ul' ? /<li\b/g : /<tr\b/g) ?? []).length;
      const limit = /data-section="3"/.test(attrs) ? 11 : 5;
      assert.ok(count <= limit, `слайд ${i + 1}: ${count} пунктов`);
    }
  }
});

test('U20: у каждого слайда заметки с выступающим и секундами, сумма не больше 840 с', () => {
  let total = 0;
  for (const [i, { body }] of slides.entries()) {
    const notes = body.match(/<aside class="notes">([\s\S]*?)<\/aside>/);
    assert.ok(notes, `слайд ${i + 1} без заметок`);
    const head = notes[1].match(/^(Даниил|Александр), (\d+) с\./);
    assert.ok(head, `слайд ${i + 1}: нет «Имя, N с.»`);
    total += Number(head[2]);
  }
  assert.ok(total <= 840, `сумма ${total} с`);
});

test('U20: нет localhost, docker compose up и паролей демо-аккаунтов', () => {
  for (const text of [deck, sources]) {
    assert.doesNotMatch(text, /localhost|docker compose up|demo-(student|teacher|admin)-1/);
  }
});

test('U20: каждая цифра о проекте со слайдов есть в таблице источников', () => {
  const plain = (html) => html.replace(/<[^>]+>/g, '').replace(/\s+/g, ' ').trim();
  const numbers = [...deck.matchAll(/<div class="num"[^>]*>([\s\S]*?)<\/div>/g)].flatMap(([, html]) => plain(html).split(' · '));
  const adr = deck.match(/(\d+) ADR</)[1];
  const slice = deck.match(/Срез: ([0-9a-f]{7})/)[1];
  const values = [...numbers, adr, slice];
  assert.ok(numbers.length >= 9, `на слайдах найдено ${numbers.length} цифр`);
  for (const value of values) {
    assert.match(value, /^[0-9a-f]+( млн)?$/, `не цифра: «${value}»`);
    assert.ok(sources.includes(`| ${value} |`), `нет строки источника для ${value}`);
  }
});
