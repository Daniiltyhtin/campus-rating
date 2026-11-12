import { http, HttpResponse } from 'msw';
import type { components } from '../api/schema';
import { examples } from '../mocks/examples.generated';
import { server } from '../mocks/server';
import { authUsers, createAuthScenario, mountAuth, problem } from './authAcceptance';

type S = components['schemas'];
export type Teacher = S['AdminTeacher'];
export type Grant = S['AdminTeacherGrant'];
export type GrantCreate = S['TeacherGrantCreate'];
export type RequestRecord = { method: string; path: string; body: unknown; csrf: string | null; signal: AbortSignal };
function example<T>(operationId: string): T {
  const value = examples.find((item) => item.operationId === operationId)?.responses.find((item) => item.status === 200)?.body;
  if (!value) throw new Error(`Missing teacher example ${operationId}`);
  return structuredClone(value) as T;
}
export const teacherFixtures = {
  list: () => example<S['AdminTeacherList']>('listAdminTeachers'),
  pending: () => example<Teacher>('getAdminTeacher'),
  grants: () => example<S['AdminTeacherGrantList']>('listAdminTeacherGrants'),
  groups: () => example<S['AdminStudyGroupList']>('listAdminGroups'),
  faculties: () => example<S['AdminFacultyList']>('listAdminFaculties'),
  departments: () => example<S['AdminDepartmentList']>('listAdminDepartments'),
};
export const foreignFaculty = { id: 2, code: 'ЭФ', name: 'Экономический факультет' };
export const foreignDepartment = { id: 202, code: 'ЭК', name: 'Кафедра экономики', faculty: foreignFaculty };
export const foreignGroup = { id: 21, code: 'EC-26', name: 'ЭК-26', faculty: foreignFaculty, admissionYear: 2026 };
export { authUsers, problem };
export function teacherJson(value: unknown, status = 200) { return new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } }); }
export function teacherDeferred() {
  let resolve!: () => void;
  const promise = new Promise<void>((done) => { resolve = done; });
  return { promise, resolve };
}
const mounts = new Set<ReturnType<typeof mountAuth>>();
export function mountTeachers(path = '/admin/directory/teachers') {
  const view = mountAuth(path); mounts.add(view); return view;
}
export function disposeTeacherTests() { for (const view of mounts) { view.router.dispose(); view.queryClient.clear(); } mounts.clear(); }
export function createTeachersScenario(viewer: S['CurrentUser'] | null = { ...authUsers.admin, mustChangePassword: false }) {
  const auth = createAuthScenario(viewer);
  const state = {
    auth, teachers: new Map(teacherFixtures.list().items.map((item) => [item.id, item])),
    grants: new Map<number, Grant[]>([[2001, teacherFixtures.grants().items], [2002, []]]),
    scopes: new Map<number, S['StudyGroupRef'][]>([[2001, teacherFixtures.groups().items], [2002, [foreignGroup]]]),
    faculties: [...teacherFixtures.faculties().items, foreignFaculty],
    departments: [...teacherFixtures.departments().items, foreignDepartment],
    groups: [...teacherFixtures.groups().items, foreignGroup],
    requests: [] as RequestRecord[], nextId: 8000,
  };
  async function record(request: Request) {
    const value = { method: request.method, path: new URL(request.url).pathname, body: request.method === 'POST' ? await request.clone().json() as unknown : null,
      csrf: request.headers.get('X-XSRF-TOKEN'), signal: request.signal };
    state.requests.push(value); return value;
  }
  function grant(body: GrantCreate, id: number): Grant {
    switch (body.kind) {
      case 'GROUP': return { id, kind: body.kind, group: state.groups.find((item) => item.id === body.groupId)! };
      case 'FACULTY': return { id, kind: body.kind, faculty: state.faculties.find((item) => item.id === body.facultyId)! };
      case 'FACULTY_COURSE': return { id, kind: body.kind, faculty: state.faculties.find((item) => item.id === body.facultyId)!, course: body.course };
      case 'COURSE': return { id, kind: body.kind, course: body.course };
    }
  }
  function target(item: Grant): GrantCreate {
    switch (item.kind) {
      case 'GROUP': return { kind: item.kind, groupId: item.group.id };
      case 'FACULTY': return { kind: item.kind, facultyId: item.faculty.id };
      case 'FACULTY_COURSE': return { kind: item.kind, facultyId: item.faculty.id, course: item.course };
      case 'COURSE': return { kind: item.kind, course: item.course };
    }
  }
  server.use(
    http.get('*/api/v1/admin/teachers', async ({ request }) => { await record(request); return teacherJson({ items: [...state.teachers.values()] }); }),
    http.get('*/api/v1/admin/teachers/:teacherId', async ({ request, params }) => {
      await record(request); const teacher = state.teachers.get(Number(params.teacherId));
      return teacher ? teacherJson(teacher) : problem('NOT_FOUND', 404);
    }),
    http.post('*/api/v1/admin/teachers/:teacherId/approval', async ({ request, params }) => {
      const event = await record(request), teacher = state.teachers.get(Number(params.teacherId));
      if (!teacher) return problem('NOT_FOUND', 404);
      const body = event.body as { departmentId: number }, department = state.departments.find((item) => item.id === body.departmentId);
      if (!department) return problem('VALIDATION_FAILED', 400, { fieldErrors: [{ field: 'departmentId', code: 'NOT_FOUND', message: 'Выберите кафедру' }] });
      if (teacher.teacherApproved && teacher.department.id !== department.id) return problem('INVALID_STATE', 409);
      const approved = { ...teacher, department, teacherApproved: true }; state.teachers.set(teacher.id, approved); return teacherJson(approved);
    }),
    http.delete('*/api/v1/admin/teachers/:teacherId/application', async ({ request, params }) => {
      await record(request); const teacher = state.teachers.get(Number(params.teacherId));
      if (!teacher) return problem('NOT_FOUND', 404);
      if (teacher.teacherApproved) return problem('INVALID_STATE', 409);
      state.teachers.delete(teacher.id); state.grants.delete(teacher.id); return new HttpResponse(null, { status: 204 });
    }),
    http.get('*/api/v1/admin/teachers/:teacherId/grants', async ({ request, params }) => {
      await record(request); const id = Number(params.teacherId);
      return state.teachers.has(id) ? teacherJson({ items: state.grants.get(id) ?? [] }) : problem('NOT_FOUND', 404);
    }),
    http.post('*/api/v1/admin/teachers/:teacherId/grants', async ({ request, params }) => {
      const event = await record(request), id = Number(params.teacherId);
      if (!state.teachers.has(id)) return problem('NOT_FOUND', 404);
      const rows = state.grants.get(id) ?? [], input = event.body as GrantCreate;
      const existing = rows.find((item) => JSON.stringify(target(item)) === JSON.stringify(input));
      if (existing) return teacherJson(existing);
      const value = grant(input, state.nextId++);
      rows.push(value); state.grants.set(id, rows); return teacherJson(value, 201);
    }),
    http.delete('*/api/v1/admin/teachers/:teacherId/grants/:grantId', async ({ request, params }) => {
      await record(request); const id = Number(params.teacherId), grantId = Number(params.grantId), rows = state.grants.get(id) ?? [];
      if (!state.teachers.has(id) || !rows.some((item) => item.id === grantId)) return problem('NOT_FOUND', 404);
      state.grants.set(id, rows.filter((item) => item.id !== grantId)); return new HttpResponse(null, { status: 204 });
    }),
    http.get('*/api/v1/admin/teachers/:teacherId/accessible-groups', async ({ request, params }) => {
      await record(request); const teacher = state.teachers.get(Number(params.teacherId));
      return teacher ? teacherJson({ items: teacher.teacherApproved ? state.scopes.get(teacher.id) ?? [] : [] }) : problem('NOT_FOUND', 404);
    }),
    http.get('*/api/v1/admin/directory/faculties', async ({ request }) => { await record(request); return teacherJson({ items: state.faculties }); }),
    http.get('*/api/v1/admin/directory/departments', async ({ request }) => { await record(request); return teacherJson({ items: state.departments }); }),
    http.get('*/api/v1/admin/directory/groups', async ({ request }) => { await record(request); return teacherJson({ items: state.groups }); }),
  );
  return state;
}
