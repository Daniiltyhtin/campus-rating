import type { components } from '../../../api/schema';

type Schemas = components['schemas'];
export type DirectoryKind = 'faculties' | 'groups' | 'departments';
export type Faculty = Schemas['AdminFaculty'];
export type DirectoryEntry = (Faculty & { kind: 'faculties' }) | (Schemas['AdminStudyGroup'] & { kind: 'groups' }) | (Schemas['AdminDepartment'] & { kind: 'departments' });
export type DirectoryWrite = Schemas['FacultyWrite'] | Schemas['StudyGroupWrite'] | Schemas['DepartmentWrite'];
export type DirectoryFields = { code: string; name: string; facultyId: string; admissionYear: string };
export const kinds: readonly DirectoryKind[] = ['faculties', 'groups', 'departments'];
export const labels = { faculties: { title: 'Факультеты', noun: 'факультет' }, groups: { title: 'Учебные группы', noun: 'группу' }, departments: { title: 'Кафедры', noun: 'кафедру' } };
function invalid(): never { throw new Error('Не удалось подтвердить данные сервера. Обновите список перед повтором действия.'); }
function object(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : invalid();
}
function integer(value: unknown, min = 1, max = Number.MAX_SAFE_INTEGER): number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= min && value <= max ? value : invalid();
}
function text(value: unknown, max: number): string {
  return typeof value === 'string' && value.trim().length > 0 && [...value].length <= max ? value : invalid();
}
function faculty(value: unknown): Faculty {
  const row = object(value); return { id: integer(row.id), code: text(row.code, 64), name: text(row.name, 255) };
}
export function parseDirectoryEntry(kind: DirectoryKind, value: unknown): DirectoryEntry {
  const row = object(value), base = faculty(row);
  if (kind === 'faculties') return { ...base, kind };
  const parent = faculty(row.faculty);
  if (kind === 'groups') return { ...base, kind, faculty: parent, admissionYear: integer(row.admissionYear, 1, 9999) };
  return { ...base, kind, faculty: parent };
}
export function parseDirectoryList(kind: DirectoryKind, value: unknown) {
  const row = object(value);
  return { items: Array.isArray(row.items) ? row.items.map((item: unknown) => parseDirectoryEntry(kind, item)) : invalid() };
}
export function validateDirectory(kind: DirectoryKind, fields: DirectoryFields, faculties: readonly Faculty[]): { ok: true; body: DirectoryWrite } | { ok: false; errors: Record<string, string> } {
  const errors: Record<string, string> = {}, code = fields.code.trim(), name = fields.name.trim();
  if (!code || [...code].length > 64) errors.code = 'Код — от 1 до 64 символов';
  if (!name || [...name].length > 255) errors.name = 'Название — от 1 до 255 символов';
  const facultyId = Number(fields.facultyId), admissionYear = Number(fields.admissionYear);
  if (kind !== 'faculties' && !faculties.some((item) => item.id === facultyId)) errors.facultyId = 'Выберите существующий факультет';
  if (kind === 'groups' && (!fields.admissionYear.trim() || !Number.isInteger(admissionYear) || admissionYear < 1 || admissionYear > 9999)) errors.admissionYear = 'Год набора — целое число от 1 до 9999';
  if (Object.keys(errors).length) return { ok: false, errors };
  if (kind === 'faculties') return { ok: true, body: { code, name } };
  return { ok: true, body: { code, name, facultyId, ...(kind === 'groups' ? { admissionYear } : {}) } };
}
export function fieldsFor(entry?: DirectoryEntry): DirectoryFields {
  return { code: entry?.code ?? '', name: entry?.name ?? '', facultyId: entry && entry.kind !== 'faculties' ? String(entry.faculty.id) : '', admissionYear: entry?.kind === 'groups' ? String(entry.admissionYear) : '' };
}
