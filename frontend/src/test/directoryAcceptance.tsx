import { http, HttpResponse } from 'msw';
import type { components } from '../api/schema';
import { examples } from '../mocks/examples.generated';
import { server } from '../mocks/server';
import { authUsers, createAuthScenario, mountAuth, problem } from './authAcceptance';

type Schemas = components['schemas'];
export type DirectoryKind = 'faculties' | 'groups' | 'departments';
export type DirectoryItem = Schemas['AdminFaculty'] | Schemas['AdminStudyGroup'] | Schemas['AdminDepartment'];
export type DirectoryRequest = { method: string; path: string; csrf: string | null; body: unknown };
function example<T>(operationId: string): T {
  const response = examples.find((item) => item.operationId === operationId)?.responses.find((item) => item.status === 200);
  if (!response) throw new Error(`Missing directory example ${operationId}`);
  return structuredClone(response.body) as T;
}
export const directoryFixtures = {
  faculties: () => example<Schemas['AdminFacultyList']>('listAdminFaculties'),
  groups: () => example<Schemas['AdminStudyGroupList']>('listAdminGroups'),
  departments: () => example<Schemas['AdminDepartmentList']>('listAdminDepartments'),
};
export { authUsers, problem };
export function directoryJson(body: unknown, status = 200) { return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }); }
export function directoryDeferred() {
  let resolve!: () => void;
  const promise = new Promise<void>((done) => { resolve = done; });
  return { promise, resolve };
}
const mounted = new Set<ReturnType<typeof mountAuth>>();
export function mountDirectory(path = '/admin/directory') {
  const view = mountAuth(path);
  mounted.add(view);
  return view;
}
export function disposeDirectoryTests() {
  for (const view of mounted) { view.router.dispose(); view.queryClient.clear(); }
  mounted.clear();
}
export function createDirectoryScenario(viewer: Schemas['CurrentUser'] | null = { ...authUsers.admin, mustChangePassword: false }) {
  const auth = createAuthScenario(viewer);
  const state = {
    auth,
    lists: { faculties: directoryFixtures.faculties().items, groups: directoryFixtures.groups().items, departments: directoryFixtures.departments().items } as Record<DirectoryKind, DirectoryItem[]>,
    requests: [] as DirectoryRequest[],
    used: new Set(['faculties:1', 'groups:11', 'departments:101']),
    nextId: 700,
    async record(request: Request) {
      const value = { method: request.method, path: new URL(request.url).pathname, csrf: request.headers.get('X-XSRF-TOKEN'),
        body: ['POST', 'PUT'].includes(request.method) ? await request.clone().json() as unknown : null };
      state.requests.push(value);
      return value;
    },
  };
  function written(kind: DirectoryKind, body: Record<string, unknown>, id: number): DirectoryItem {
    const common = { id, code: String(body.code), name: String(body.name) };
    if (kind === 'faculties') return common;
    const faculty = state.lists.faculties.find((item) => item.id === Number(body.facultyId));
    if (!faculty) throw new Error('Test fixture expected an existing faculty');
    if (kind === 'groups') return { ...common, faculty, admissionYear: Number(body.admissionYear) };
    return { ...common, faculty };
  }
  for (const kind of ['faculties', 'groups', 'departments'] as const) {
    server.use(
      http.get(`*/api/v1/admin/directory/${kind}`, async ({ request }) => { await state.record(request); return directoryJson({ items: state.lists[kind] }); }),
      http.post(`*/api/v1/admin/directory/${kind}`, async ({ request }) => {
        const event = await state.record(request), body = event.body as Record<string, unknown>;
        if (state.lists[kind].some((item) => item.code === body.code)) return problem('DIRECTORY_CODE_TAKEN', 409);
        const value = written(kind, body, state.nextId++);
        state.lists[kind].push(value);
        return directoryJson(value, 201);
      }),
      http.put(`*/api/v1/admin/directory/${kind}/:id`, async ({ request, params }) => {
        const event = await state.record(request), index = state.lists[kind].findIndex((item) => item.id === Number(params.id));
        if (index === -1) return problem('NOT_FOUND', 404);
        const value = written(kind, event.body as Record<string, unknown>, Number(params.id));
        state.lists[kind][index] = value;
        return directoryJson(value);
      }),
      http.delete(`*/api/v1/admin/directory/${kind}/:id`, async ({ request, params }) => {
        await state.record(request);
        if (state.used.has(`${kind}:${params.id}`)) return problem('DIRECTORY_IN_USE', 409);
        const index = state.lists[kind].findIndex((item) => item.id === Number(params.id));
        if (index === -1) return problem('NOT_FOUND', 404);
        state.lists[kind].splice(index, 1);
        return new HttpResponse(null, { status: 204 });
      }),
    );
  }
  return state;
}
