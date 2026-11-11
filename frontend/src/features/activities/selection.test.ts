import { parseParticipation } from './contracts';
import type { Level } from './contracts';
import { updateSelection } from './selection';

const full = Object.fromEntries(Array.from({ length: 500 }, (_, index) => [index + 1, 100 as Level]));
it('keeps 500 selections and refuses the 501st without changing the original', () => {
  const beforeLast = { ...full }; delete beforeLast[500];
  expect(Object.keys(updateSelection(beforeLast, 500, 100))).toHaveLength(500);
  expect(updateSelection(full, 501, 100)).toEqual(full);
  expect(full[501]).toBeUndefined();
});
it('allows changing a level and freeing a slot at the limit', () => {
  expect(updateSelection(full, 500, 25)[500]).toBe(25);
  const reduced = updateSelection(full, 500, undefined);
  expect(reduced[500]).toBeUndefined();
  expect(Object.keys(updateSelection(reduced, 501, 50))).toHaveLength(500);
  expect(full[500]).toBe(100);
});
it('checks the request boundary: 500 unique participants accepted, 501 or duplicate refused', () => {
  const items = Object.entries(full).map(([id, level]) => ({ studentId: Number(id), level }));
  expect(parseParticipation({ items }).items).toHaveLength(500);
  expect(() => parseParticipation({ items: [...items, { studentId: 501, level: 100 }] })).toThrow();
  expect(() => parseParticipation({ items: [items[0], items[0]] })).toThrow();
  expect(() => parseParticipation({ items: [] })).toThrow();
});
