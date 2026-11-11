import { expect, it } from 'vitest';
import type { Award } from './contracts';
import { newerAwards, uniqueAwards } from './presentation';
const award = (entryId: number, createdAt = '2026-11-03T14:00:00Z'): Award => ({ entryId, createdAt,
  student: { id: 1001, fullName: 'Студент' }, teacher: { id: 2001, fullName: 'Преподаватель' },
  activity: { id: 601, title: 'Конференция' }, amount: 50, currency: 'BOA' });
it('keeps the first snapshot and server order when cursor pages overlap', () => {
  const first = award(3);
  expect(uniqueAwards([first, award(2), { ...first, amount: 99 }, award(1)])).toEqual([first, award(2), award(1)]);
});
it('detects newer entries using timestamp then id without mutating the visible list', () => {
  const visible = [award(3), award(2)];
  expect(newerAwards([award(1, '2026-11-03T14:01:00Z'), award(4), award(3), award(2)], visible)).toEqual([award(1, '2026-11-03T14:01:00Z'), award(4)]);
  expect(visible.map((item) => item.entryId)).toEqual([3, 2]);
  expect(newerAwards([award(1)], [])).toEqual([award(1)]);
});
