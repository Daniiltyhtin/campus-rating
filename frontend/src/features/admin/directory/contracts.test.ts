import { expect, it } from 'vitest';
import { examples } from '../../../mocks/examples.generated';
import { parseDirectoryList, validateDirectory } from './contracts';

const faculty = { id: 1, code: 'ФИТ', name: 'Факультет информационных технологий' };
const fields = { code: ' IVT-26 ', name: ' ИВТ-26 ', facultyId: '1', admissionYear: '2026' };
it.each(['faculties', 'groups', 'departments'] as const)('parses the complete %s contract list', (kind) => {
  const operationId = { faculties: 'listAdminFaculties', groups: 'listAdminGroups', departments: 'listAdminDepartments' }[kind];
  const body = examples.find((item) => item.operationId === operationId)!.responses.find((item) => item.status === 200)!.body;
  expect(parseDirectoryList(kind, body).items.length).toBeGreaterThan(0);
});
it('trims names and codes and keeps a future admission year', () => {
  expect(validateDirectory('groups', fields, [faculty])).toEqual({ ok: true, body: { code: 'IVT-26', name: 'ИВТ-26', facultyId: 1, admissionYear: 2026 } });
});
it.each(['0', '10000', '2026.5', '', 'Infinity'])('rejects admissionYear %s', (admissionYear) => {
  expect(validateDirectory('groups', { ...fields, admissionYear }, [faculty])).toMatchObject({ ok: false, errors: { admissionYear: expect.any(String) } });
});
it.each(['', ' ', 'X'.repeat(65)])('rejects invalid code %#', (code) => {
  expect(validateDirectory('faculties', { ...fields, code }, [])).toMatchObject({ ok: false, errors: { code: expect.any(String) } });
});
it('checks Unicode code points and both year boundaries', () => {
  for (const admissionYear of ['1', '9999']) expect(validateDirectory('groups', { ...fields, code: '😀'.repeat(64), admissionYear }, [faculty]).ok).toBe(true);
  expect(validateDirectory('faculties', { ...fields, name: 'А'.repeat(256) }, [])).toMatchObject({ ok: false });
});
it('rejects unknown faculty instead of trusting the entered id', () => {
  expect(validateDirectory('departments', { ...fields, facultyId: '2' }, [faculty])).toMatchObject({ ok: false, errors: { facultyId: expect.any(String) } });
});
it('rejects malformed server data instead of creating an empty list', () => {
  for (const value of [null, {}, { items: null }, { items: [{ id: 0, code: 'X', name: 'Name' }] }]) expect(() => parseDirectoryList('faculties', value)).toThrow();
  expect(() => parseDirectoryList('groups', { items: [{ id: 11, code: 'IVT', name: 'ИВТ', faculty, admissionYear: 1.5 }] })).toThrow();
});
