import { expect, it } from 'vitest';
import { examples } from '../../../mocks/examples.generated';
import { parseTeacher, parseTeachers, parseGrants, validateGrant } from './contracts';

function example(id: string): unknown { return structuredClone(examples.find((row) => row.operationId === id)!.responses.find((row) => row.status === 200)!.body); }
const faculty = { id: 1, code: 'ФИТ', name: 'ФИТ' }, group = { id: 11, name: 'ИВТ-21', faculty };
const fields = { kind: 'GROUP', groupId: '11', facultyId: '1', course: '2' };
it('parses contract teachers and all four grant variants', () => {
  expect(parseTeachers(example('listAdminTeachers')).items).toHaveLength(2);
  expect(parseGrants(example('listAdminTeacherGrants')).items.map((row) => row.kind)).toEqual(['GROUP', 'FACULTY', 'FACULTY_COURSE', 'COURSE']);
});
it('rejects malformed identities/status/ref instead of trusting the teacher email', () => {
  const teacher = parseTeacher(example('getAdminTeacher'));
  for (const bad of [{ id: 0 }, { teacherApproved: 'true' }, { email: 'not email' }, { department: null }]) expect(() => parseTeacher({ ...teacher, ...bad })).toThrow();
});
it('emits only fields of the selected variant', () => {
  expect(validateGrant(fields, [group], [faculty])).toEqual({ ok: true, body: { kind: 'GROUP', groupId: 11 } });
  expect(validateGrant({ ...fields, kind: 'FACULTY' }, [group], [faculty])).toEqual({ ok: true, body: { kind: 'FACULTY', facultyId: 1 } });
  expect(validateGrant({ ...fields, kind: 'FACULTY_COURSE' }, [group], [faculty])).toEqual({ ok: true, body: { kind: 'FACULTY_COURSE', facultyId: 1, course: 2 } });
  expect(validateGrant({ ...fields, kind: 'COURSE' }, [], [])).toEqual({ ok: true, body: { kind: 'COURSE', course: 2 } });
});
it.each(['0', '7', '1.5', '', 'Infinity'])('rejects invalid course %s', (course) => {
  expect(validateGrant({ ...fields, kind: 'COURSE', course }, [], [])).toMatchObject({ ok: false, errors: { course: expect.any(String) } });
});
it.each(['1', '6'])('accepts course boundary %s', (course) => {
  expect(validateGrant({ ...fields, kind: 'COURSE', course }, [], []).ok).toBe(true);
});
it('rejects mixed external variants, unsafe ids and unknown selected targets', () => {
  for (const row of [{ id: 1, kind: 'GROUP', group, faculty }, { id: 2, kind: 'COURSE', course: 7 }, { id: Number.MAX_SAFE_INTEGER + 1, kind: 'COURSE', course: 1 }]) expect(() => parseGrants({ items: [row] })).toThrow();
  expect(validateGrant({ ...fields, groupId: '999' }, [group], [faculty])).toMatchObject({ ok: false });
});
