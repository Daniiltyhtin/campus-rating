import { http, HttpResponse } from 'msw';
import { kinds, parseDirectoryEntry, parseDirectoryList, validateDirectory } from '../features/admin/directory/contracts';
import type { DirectoryEntry, DirectoryKind, Faculty } from '../features/admin/directory/contracts';
import { readUser, validCsrf } from './auth';
import { examples } from './examples.generated';

function example(operationId: string): unknown {
  const response = examples.find((item) => item.operationId === operationId)?.responses.find((item) => item.status >= 200 && item.status < 300);
  if (!response) throw new Error(`Missing directory example: ${operationId}`);
  return structuredClone(response.body);
}
function problem(code: string, status: number, details = {}) {
  return HttpResponse.json({ type: 'about:blank', title: 'Ошибка справочника', code, status, ...details }, { status, headers: { 'Content-Type': 'application/problem+json' } });
}
function validation(errors: Record<string, string>) {
  return problem('VALIDATION_FAILED', 400, { fieldErrors: Object.entries(errors).map(([field, message]) => ({ field, code: 'INVALID', message })) });
}
function object(value: unknown): value is Record<string, unknown> { return value !== null && typeof value === 'object' && !Array.isArray(value); }
function input(kind: DirectoryKind, value: unknown, faculties: readonly Faculty[]) {
  if (!object(value)) return { ok: false as const, errors: { code: 'Некорректные поля' } };
  return validateDirectory(kind, { code: typeof value.code === 'string' ? value.code : '', name: typeof value.name === 'string' ? value.name : '',
    facultyId: typeof value.facultyId === 'number' ? String(value.facultyId) : '', admissionYear: typeof value.admissionYear === 'number' ? String(value.admissionYear) : '' }, faculties);
}
function access(request: Request) {
  const viewer = readUser();
  if (!viewer) return problem('UNAUTHENTICATED', 401);
  if (viewer.mustChangePassword) return problem('PASSWORD_CHANGE_REQUIRED', 403);
  if (viewer.role !== 'ADMIN') return problem('FORBIDDEN', 403);
  return request.method !== 'GET' && !validCsrf(request) ? problem('CSRF_INVALID', 403) : null;
}
export function createDirectoryMockHandlers() {
  const rows: Record<DirectoryKind, DirectoryEntry[]> = {
    faculties: parseDirectoryList('faculties', example('listFaculties')).items,
    groups: parseDirectoryList('groups', example('listAdminGroups')).items,
    departments: parseDirectoryList('departments', example('listAdminDepartments')).items,
  };
  const publicGroups = example('listFacultyGroups');
  if (object(publicGroups) && Array.isArray(publicGroups.items)) for (const group of publicGroups.items) {
    if (object(group) && !rows.groups.some((row) => row.id === group.id)) rows.groups.push(parseDirectoryEntry('groups', { ...group, code: group.name, admissionYear: 2025 }));
  }
  let nextId = 5000;
  const faculties = (): Faculty[] => rows.faculties.map(({ id, code, name }) => ({ id, code, name }));
  const wire = (row: DirectoryEntry) => {
    const value = { id: row.id, code: row.code, name: row.name };
    if (row.kind === 'faculties') return value;
    const facultyId = row.faculty.id;
    return { ...value, faculty: faculties().find((item) => item.id === facultyId), ...(row.kind === 'groups' ? { admissionYear: row.admissionYear } : {}) };
  };
  const ordered = (kind: DirectoryKind) => [...rows[kind]].sort((a, b) => a.name.localeCompare(b.name, 'ru') || a.id - b.id).map(wire);
  const inUse = (kind: DirectoryKind, id: number) => kind === 'groups' ? id === 11 : kind === 'departments' ? id === 101
    : [...rows.groups, ...rows.departments].some((row) => row.kind !== 'faculties' && row.faculty.id === id);
  async function save(kind: DirectoryKind, request: Request, id?: number) {
    const denied = access(request); if (denied) return denied;
    if (id !== undefined && !rows[kind].some((row) => row.id === id)) return problem('NOT_FOUND', 404);
    let body: unknown;
    try { body = await request.json(); } catch { return validation({ code: 'Некорректное тело запроса' }); }
    const checked = input(kind, body, faculties());
    if (!checked.ok) return validation(checked.errors);
    if (rows[kind].some((row) => row.id !== id && row.code === checked.body.code)) return problem('DIRECTORY_CODE_TAKEN', 409);
    const facultyId = 'facultyId' in checked.body ? checked.body.facultyId : undefined;
    const parent = faculties().find((row) => row.id === facultyId);
    const row = parseDirectoryEntry(kind, { ...checked.body, id: id ?? nextId++, ...(parent ? { faculty: parent } : {}) });
    if (id === undefined) rows[kind].push(row); else rows[kind] = rows[kind].map((old) => old.id === id ? row : old);
    return HttpResponse.json(wire(row), { status: id === undefined ? 201 : 200 });
  }
  return [
    ...kinds.flatMap((kind) => [
      http.get(`*/api/v1/admin/directory/${kind}`, ({ request }) => access(request) ?? HttpResponse.json({ items: ordered(kind) })),
      http.post(`*/api/v1/admin/directory/${kind}`, ({ request }) => save(kind, request)),
      http.put(`*/api/v1/admin/directory/${kind}/:id`, ({ request, params }) => save(kind, request, Number(params.id))),
      http.delete(`*/api/v1/admin/directory/${kind}/:id`, ({ request, params }) => {
        const denied = access(request); if (denied) return denied;
        const id = Number(params.id);
        if (!rows[kind].some((row) => row.id === id)) return problem('NOT_FOUND', 404);
        if (inUse(kind, id)) return problem('DIRECTORY_IN_USE', 409);
        rows[kind] = rows[kind].filter((row) => row.id !== id);
        return new HttpResponse(null, { status: 204 });
      }),
    ]),
    http.get('*/api/v1/directory/faculties', () => HttpResponse.json({ items: ordered('faculties') })),
    http.get('*/api/v1/directory/faculties/:facultyId/groups', ({ params }) => HttpResponse.json({ items: rows.groups.filter((row) => row.kind === 'groups' && row.faculty.id === Number(params.facultyId)).map((row) => ({ id: row.id, name: row.name, faculty: row.kind !== 'faculties' ? faculties().find((item) => item.id === row.faculty.id) : undefined })) })),
    http.get('*/api/v1/directory/faculties/:facultyId/departments', ({ params }) => HttpResponse.json({ items: rows.departments.filter((row) => row.kind === 'departments' && row.faculty.id === Number(params.facultyId)).map(wire) })),
  ];
}
