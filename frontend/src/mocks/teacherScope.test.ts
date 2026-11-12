import { expect, it } from 'vitest';
import type { components } from '../api/schema';
import { mockTeacherScope } from './teacherScope';

type Schemas = components['schemas'];
const f1 = { id: 1, code: 'ФИТ', name: 'ФИТ' }, f2 = { id: 2, code: 'ЭФ', name: 'Экономический факультет' };
const group = (id: number, admissionYear: number, faculty = f2): Schemas['AdminStudyGroup'] => ({ id, code: String(id), name: `Группа ${id}`, admissionYear, faculty });
const groups = [group(11, 2025, f1), group(21, 2026), group(22, 2025), group(23, 2027), group(24, 2010)];
const now = new Date('2026-11-11T09:00:00Z');
const ids = (grants: Schemas['AdminTeacherGrant'][], date = now) => mockTeacherScope(true, 1, groups, grants, date).map((row) => row.id);
it('an unapproved teacher has no groups even with a grant', () => {
  expect(mockTeacherScope(false, 1, groups, [{ id: 1, kind: 'FACULTY', faculty: f2 }], now)).toEqual([]);
});
it('unites the department faculty with all four kinds and removes overlap', () => {
  expect(ids([{ id: 1, kind: 'GROUP', group: groups[1]! }])).toEqual([11, 21]);
  expect(ids([{ id: 1, kind: 'FACULTY', faculty: f2 }])).toEqual([11, 21, 22, 23, 24]);
  expect(ids([{ id: 1, kind: 'FACULTY_COURSE', faculty: f2, course: 1 }])).toEqual([11, 21]);
  expect(ids([{ id: 1, kind: 'COURSE', course: 2 }, { id: 2, kind: 'GROUP', group: groups[2]! }])).toEqual([11, 22]);
});
it('moves course grants at September 1 Moscow and excludes future or graduated cohorts', () => {
  const grant: Schemas['AdminTeacherGrant'][] = [{ id: 1, kind: 'FACULTY_COURSE', faculty: f2, course: 1 }];
  expect(ids(grant, new Date('2026-08-31T20:59:59Z'))).toEqual([11, 22]);
  expect(ids(grant, new Date('2026-08-31T21:00:00Z'))).toEqual([11, 21]);
  expect(ids([{ id: 1, kind: 'COURSE', course: 6 }])).toEqual([11]);
});
it('removing a grant does not remove a group available by the base department', () => {
  expect(ids([])).toEqual([11]);
});
