import type { components } from '../../../api/schema';
import { parseDirectoryEntry } from '../directory/contracts';

type Schemas = components['schemas'];
export type Teacher = Schemas['AdminTeacher'];
export type Grant = Schemas['AdminTeacherGrant'];
export type GrantCreate = Schemas['TeacherGrantCreate'];
export type GrantKind = Grant['kind'];
export type Faculty = Schemas['FacultyRef'];
export type StudyGroup = Schemas['StudyGroupRef'];
export type GrantFields = { kind: string; groupId: string; facultyId: string; course: string };
export const grantKinds: readonly GrantKind[] = ['GROUP', 'FACULTY', 'FACULTY_COURSE', 'COURSE'];
export const grantNames: Record<GrantKind, string> = { GROUP: 'Группа', FACULTY: 'Факультет', FACULTY_COURSE: 'Курс факультета', COURSE: 'Курс университета' };
function invalid(): never { throw new Error('Не удалось подтвердить данные сервера. Обновите карточку перед повтором действия.'); }
function object(value: unknown): Record<string, unknown> { return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : invalid(); }
function id(value: unknown): number { return typeof value === 'number' && Number.isSafeInteger(value) && value > 0 ? value : invalid(); }
function text(value: unknown): string { return typeof value === 'string' && value.trim().length > 0 ? value : invalid(); }
function course(value: unknown): number { const number = id(value); return number <= 6 ? number : invalid(); }
function only(row: Record<string, unknown>, keys: readonly string[]) { if (Object.keys(row).some((key) => !keys.includes(key))) invalid(); }
function list<T extends { id: number }>(value: unknown, parse: (value: unknown) => T): { items: T[] } {
  const row = object(value); if (!Array.isArray(row.items)) return invalid();
  const items = row.items.map(parse);
  if (new Set(items.map((item) => item.id)).size !== items.length) return invalid();
  return { items };
}
function faculty(value: unknown): Faculty { const row = object(value); return { id: id(row.id), code: text(row.code), name: text(row.name) }; }
function group(value: unknown): StudyGroup { const row = object(value); return { id: id(row.id), name: text(row.name), faculty: faculty(row.faculty) }; }
export function parseTeacher(value: unknown): Teacher {
  const row = object(value), department = parseDirectoryEntry('departments', row.department), email = text(row.email);
  if (department.kind !== 'departments' || typeof row.teacherApproved !== 'boolean' || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) return invalid();
  return { id: id(row.id), fullName: text(row.fullName), email, teacherApproved: row.teacherApproved,
    department: { id: department.id, code: department.code, name: department.name, faculty: department.faculty } };
}
export function parseGrant(value: unknown): Grant {
  const row = object(value), identity = id(row.id);
  switch (row.kind) {
    case 'GROUP': only(row, ['id', 'kind', 'group']); return { id: identity, kind: row.kind, group: group(row.group) };
    case 'FACULTY': only(row, ['id', 'kind', 'faculty']); return { id: identity, kind: row.kind, faculty: faculty(row.faculty) };
    case 'FACULTY_COURSE': only(row, ['id', 'kind', 'faculty', 'course']); return { id: identity, kind: row.kind, faculty: faculty(row.faculty), course: course(row.course) };
    case 'COURSE': only(row, ['id', 'kind', 'course']); return { id: identity, kind: row.kind, course: course(row.course) };
    default: return invalid();
  }
}
export function parseTeachers(value: unknown) { return list(value, parseTeacher); }
export function parseGrants(value: unknown) { return list(value, parseGrant); }
export function parseScope(value: unknown) { return list(value, group); }
export function validateGrant(fields: GrantFields, groups: readonly StudyGroup[], faculties: readonly Faculty[]): { ok: true; body: GrantCreate } | { ok: false; errors: Record<string, string> } {
  const kind = grantKinds.find((value) => value === fields.kind), errors: Record<string, string> = {};
  if (!kind) return { ok: false, errors: { kind: 'Выберите вид доступа' } };
  const groupId = Number(fields.groupId), facultyId = Number(fields.facultyId), selectedCourse = Number(fields.course);
  if (kind === 'GROUP' && !groups.some((item) => item.id === groupId)) errors.groupId = 'Выберите существующую группу';
  if ((kind === 'FACULTY' || kind === 'FACULTY_COURSE') && !faculties.some((item) => item.id === facultyId)) errors.facultyId = 'Выберите существующий факультет';
  if ((kind === 'FACULTY_COURSE' || kind === 'COURSE') && (!fields.course.trim() || !Number.isInteger(selectedCourse) || selectedCourse < 1 || selectedCourse > 6)) errors.course = 'Курс — целое число от 1 до 6';
  if (Object.keys(errors).length) return { ok: false, errors };
  if (kind === 'GROUP') return { ok: true, body: { kind, groupId } };
  if (kind === 'FACULTY') return { ok: true, body: { kind, facultyId } };
  return { ok: true, body: kind === 'COURSE' ? { kind, course: selectedCourse } : { kind, facultyId, course: selectedCourse } };
}
export function grantDescription(grant: Grant): string {
  switch (grant.kind) {
    case 'GROUP': return `Группа · ${grant.group.name} · ${grant.group.faculty.name}`;
    case 'FACULTY': return `Факультет · ${grant.faculty.name}`;
    case 'FACULTY_COURSE': return `Курс факультета · ${grant.faculty.name} · ${grant.course} курс`;
    case 'COURSE': return `Курс университета · ${grant.course} курс`;
  }
}
