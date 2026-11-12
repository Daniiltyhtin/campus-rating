import { http, HttpResponse } from 'msw';
import { parseGrants, parseTeachers, validateGrant } from '../features/admin/teachers/contracts';
import type { Grant, GrantCreate, Teacher } from '../features/admin/teachers/contracts';
import { activityClock } from '../features/activities/time';
import type { Clock } from '../features/activities/time';
import { readUser, validCsrf } from './auth';
import { createDirectoryMockState } from './directory';
import { examples } from './examples.generated';
import { mockTeacherScope } from './teacherScope';

function example(operationId: string): unknown {
  const value = examples.find((row) => row.operationId === operationId)?.responses.find((row) => row.status === 200)?.body;
  if (!value) throw new Error(`Missing teacher example: ${operationId}`);
  return structuredClone(value);
}
function problem(code: string, status: number, details = {}) { return HttpResponse.json({ type: 'about:blank', title: 'Ошибка', status, code, ...details }, { status, headers: { 'Content-Type': 'application/problem+json' } }); }
function validation(errors: Record<string, string>) { return problem('VALIDATION_FAILED', 400, { fieldErrors: Object.entries(errors).map(([field, message]) => ({ field, code: 'INVALID', message })) }); }
function object(value: unknown): value is Record<string, unknown> { return value !== null && typeof value === 'object' && !Array.isArray(value); }
async function body(request: Request): Promise<Record<string, unknown>> { try { const value: unknown = await request.json(); return object(value) ? value : {}; } catch { return {}; } }
function access(request: Request) {
  const viewer = readUser();
  if (!viewer) return problem('UNAUTHENTICATED', 401);
  if (viewer.mustChangePassword) return problem('PASSWORD_CHANGE_REQUIRED', 403);
  if (viewer.role !== 'ADMIN') return problem('FORBIDDEN', 403);
  return request.method !== 'GET' && !validCsrf(request) ? problem('CSRF_INVALID', 403) : null;
}
function target(grant: Grant): GrantCreate {
  switch (grant.kind) {
    case 'GROUP': return { kind: grant.kind, groupId: grant.group.id };
    case 'FACULTY': return { kind: grant.kind, facultyId: grant.faculty.id };
    case 'FACULTY_COURSE': return { kind: grant.kind, facultyId: grant.faculty.id, course: grant.course };
    case 'COURSE': return { kind: grant.kind, course: grant.course };
  }
}
export function createTeacherMockHandlers(directory = createDirectoryMockState(), clock: Clock = activityClock) {
  const teachers = new Map(parseTeachers(example('listAdminTeachers')).items.map((row) => [row.id, row]));
  const grants = new Map<number, Grant[]>([[2001, parseGrants(example('listAdminTeacherGrants')).items], [2002, []]]);
  let nextId = 8000;
  const faculties = () => directory.faculties.map(({ id, code, name }) => ({ id, code, name }));
  const groups = () => directory.groups.flatMap((row) => row.kind === 'groups' ? [{ id: row.id, code: row.code, name: row.name, admissionYear: row.admissionYear,
    faculty: faculties().find((faculty) => faculty.id === row.faculty.id) ?? row.faculty }] : []);
  function department(id: number): Teacher['department'] | undefined {
    const row = directory.departments.find((row) => row.id === id);
    return row?.kind === 'departments' ? { id: row.id, code: row.code, name: row.name, faculty: faculties().find((faculty) => faculty.id === row.faculty.id) ?? row.faculty } : undefined;
  }
  const view = (teacher: Teacher): Teacher => ({ ...teacher, department: department(teacher.department.id) ?? teacher.department });
  function syncReferences() {
    directory.references.departments = new Set([101, ...[...teachers.values()].map((teacher) => teacher.department.id)]);
    const values = [...grants.values()].flat();
    directory.references.groups = new Set([11, ...values.flatMap((grant) => grant.kind === 'GROUP' ? [grant.group.id] : [])]);
    directory.references.faculties = new Set(values.flatMap((grant) => grant.kind === 'FACULTY' || grant.kind === 'FACULTY_COURSE' ? [grant.faculty.id] : []));
  }
  syncReferences();
  function grantView(input: GrantCreate, id: number): Grant {
    if (input.kind === 'GROUP') { const group = groups().find((row) => row.id === input.groupId)!; return { id, kind: input.kind, group: { id: group.id, name: group.name, faculty: group.faculty } }; }
    if (input.kind === 'COURSE') return { id, kind: input.kind, course: input.course };
    const faculty = faculties().find((row) => row.id === input.facultyId)!;
    return input.kind === 'FACULTY' ? { id, kind: input.kind, faculty } : { id, kind: input.kind, faculty, course: input.course };
  }
  async function createGrant(request: Request, teacherId: number) {
    const raw = await body(request);
    const permitted: Record<string, string[]> = { GROUP: ['kind', 'groupId'], FACULTY: ['kind', 'facultyId'], FACULTY_COURSE: ['kind', 'facultyId', 'course'], COURSE: ['kind', 'course'] };
    const kind = typeof raw.kind === 'string' ? raw.kind : '';
    if (!permitted[kind] || Object.keys(raw).some((key) => !permitted[kind]!.includes(key))) return validation({ kind: 'Поля не соответствуют виду доступа' });
    const checked = validateGrant({ kind, groupId: typeof raw.groupId === 'number' ? String(raw.groupId) : '', facultyId: typeof raw.facultyId === 'number' ? String(raw.facultyId) : '', course: typeof raw.course === 'number' ? String(raw.course) : '' }, groups(), faculties());
    if (!checked.ok) return validation(checked.errors);
    const rows = grants.get(teacherId) ?? [], signature = JSON.stringify(checked.body);
    const existing = rows.find((row) => JSON.stringify(target(row)) === signature);
    if (existing) return HttpResponse.json(grantView(target(existing), existing.id));
    const grant = grantView(checked.body, nextId++); rows.push(grant); grants.set(teacherId, rows); syncReferences();
    return HttpResponse.json(grant, { status: 201 });
  }
  const authenticated = (request: Request, teacherId: number) => access(request) ?? (!teachers.has(teacherId) ? problem('NOT_FOUND', 404) : null);
  return [
    http.get('*/api/v1/admin/teachers', ({ request }) => access(request) ?? HttpResponse.json({ items: [...teachers.values()].map(view).sort((a, b) => a.fullName.localeCompare(b.fullName, 'ru') || a.id - b.id) })),
    http.get('*/api/v1/admin/teachers/:teacherId', ({ request, params }) => { const id = Number(params.teacherId); return authenticated(request, id) ?? HttpResponse.json(view(teachers.get(id)!)); }),
    http.post('*/api/v1/admin/teachers/:teacherId/approval', async ({ request, params }) => {
      const id = Number(params.teacherId), error = authenticated(request, id); if (error) return error;
      const raw = await body(request), selected = typeof raw.departmentId === 'number' ? department(raw.departmentId) : undefined;
      if (!selected || Object.keys(raw).some((key) => key !== 'departmentId')) return validation({ departmentId: 'Выберите существующую кафедру' });
      const teacher = teachers.get(id)!;
      if (teacher.teacherApproved && teacher.department.id !== selected.id) return problem('INVALID_STATE', 409);
      const approved = { ...teacher, department: selected, teacherApproved: true }; teachers.set(id, approved); syncReferences(); return HttpResponse.json(view(approved));
    }),
    http.delete('*/api/v1/admin/teachers/:teacherId/application', ({ request, params }) => {
      const id = Number(params.teacherId), error = authenticated(request, id); if (error) return error;
      if (teachers.get(id)!.teacherApproved) return problem('INVALID_STATE', 409);
      teachers.delete(id); grants.delete(id); syncReferences(); return new HttpResponse(null, { status: 204 });
    }),
    http.get('*/api/v1/admin/teachers/:teacherId/grants', ({ request, params }) => {
      const id = Number(params.teacherId); return authenticated(request, id) ?? HttpResponse.json({ items: (grants.get(id) ?? []).map((grant) => grantView(target(grant), grant.id)).sort((a, b) => a.id - b.id) });
    }),
    http.post('*/api/v1/admin/teachers/:teacherId/grants', ({ request, params }) => { const id = Number(params.teacherId); return authenticated(request, id) ?? createGrant(request, id); }),
    http.delete('*/api/v1/admin/teachers/:teacherId/grants/:grantId', ({ request, params }) => {
      const id = Number(params.teacherId), error = authenticated(request, id); if (error) return error;
      const rows = grants.get(id) ?? [], grantId = Number(params.grantId);
      if (!rows.some((row) => row.id === grantId)) return problem('NOT_FOUND', 404);
      grants.set(id, rows.filter((row) => row.id !== grantId)); syncReferences(); return new HttpResponse(null, { status: 204 });
    }),
    http.get('*/api/v1/admin/teachers/:teacherId/accessible-groups', ({ request, params }) => {
      const id = Number(params.teacherId), error = authenticated(request, id); if (error) return error;
      const teacher = view(teachers.get(id)!); return HttpResponse.json({ items: mockTeacherScope(teacher.teacherApproved, teacher.department.faculty.id, groups(), grants.get(id) ?? [], clock.now()) });
    }),
  ];
}
