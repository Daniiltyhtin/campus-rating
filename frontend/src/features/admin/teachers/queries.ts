import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../../../api/client';
import { authKeys, csrfToken } from '../../auth/authQueries';
import { parseGrants, parseGrant, parseScope, parseTeacher, parseTeachers } from './contracts';
import type { GrantCreate } from './contracts';
import type { LoadState } from '../../../app/layouts/PageLayouts';

async function read<T>(pending: Promise<{ response: Response; data?: unknown }>, parse: (value: unknown) => T) {
  const { data, response } = await pending;
  if (!response.ok) throw new Error('Не удалось получить данные');
  return parse(data);
}
export function teacherError(error: unknown) {
  if (error instanceof ApiError) switch (error.code) {
    case 'NOT_FOUND': return 'Преподаватель или выдача доступа не найдены';
    case 'FORBIDDEN': return 'Нет доступа';
    case 'INVALID_STATE': return 'Данные изменились. Обновите карточку.';
    case 'CSRF_INVALID': return 'Защита формы устарела. Обновите защиту и повторите действие.';
    case 'VALIDATION_FAILED': return 'Проверьте поля формы';
  }
  return 'Не удалось подтвердить данные. Проверьте соединение и обновите карточку перед повтором действия.';
}
export function teacherState(query: { isPending: boolean; isError: boolean; error: unknown; refetch: () => Promise<unknown> }, empty = false, message = 'Записей пока нет'): LoadState {
  if (query.isPending) return { status: 'loading' };
  if (query.isError) return { status: 'error', message: teacherError(query.error), retry: () => { void query.refetch(); } };
  return empty ? { status: 'empty', message } : { status: 'ready' };
}
export function useTeachers(viewerId: number) {
  return useQuery({ queryKey: ['admin-teachers', viewerId, 'list'], retry: false, staleTime: 30_000,
    queryFn: ({ signal }) => read(api.GET('/api/v1/admin/teachers', { signal }), parseTeachers) });
}
export function useTeacher(viewerId: number, teacherId: number) {
  return useQuery({ queryKey: ['admin-teachers', viewerId, 'detail', teacherId], retry: false, queryFn: async ({ signal }) => {
    const result = await read(api.GET('/api/v1/admin/teachers/{teacherId}', { params: { path: { teacherId } }, signal }), parseTeacher);
    if (result.id !== teacherId) throw new Error('Ответ содержит другого преподавателя');
    return result;
  } });
}
export function useTeacherGrants(viewerId: number, teacherId: number) {
  return useQuery({ queryKey: ['admin-teachers', viewerId, 'grants', teacherId], retry: false,
    queryFn: ({ signal }) => read(api.GET('/api/v1/admin/teachers/{teacherId}/grants', { params: { path: { teacherId } }, signal }), parseGrants) });
}
export function useTeacherScope(viewerId: number, teacherId: number) {
  return useQuery({ queryKey: ['admin-teachers', viewerId, 'scope', teacherId], retry: false,
    queryFn: ({ signal }) => read(api.GET('/api/v1/admin/teachers/{teacherId}/accessible-groups', { params: { path: { teacherId } }, signal }), parseScope) });
}
export type TeacherCommand = { kind: 'approve'; departmentId: number } | { kind: 'reject' } | { kind: 'grant'; body: GrantCreate } | { kind: 'remove'; grantId: number };
async function write(teacherId: number, command: TeacherCommand) {
  const params = { path: { teacherId } };
  if (command.kind === 'approve') {
    const teacher = await read(api.POST('/api/v1/admin/teachers/{teacherId}/approval', { params, body: { departmentId: command.departmentId } }), parseTeacher);
    if (teacher.id !== teacherId || !teacher.teacherApproved || teacher.department.id !== command.departmentId) throw new Error('Не удалось подтвердить допуск');
    return;
  }
  if (command.kind === 'grant') {
    const grant = await read(api.POST('/api/v1/admin/teachers/{teacherId}/grants', { params, body: command.body }), parseGrant);
    const body = command.body;
    const matches = body.kind === 'GROUP' && grant.kind === 'GROUP' && body.groupId === grant.group.id
      || body.kind === 'FACULTY' && grant.kind === 'FACULTY' && body.facultyId === grant.faculty.id
      || body.kind === 'FACULTY_COURSE' && grant.kind === 'FACULTY_COURSE' && body.facultyId === grant.faculty.id && body.course === grant.course
      || body.kind === 'COURSE' && grant.kind === 'COURSE' && body.course === grant.course;
    if (!matches) throw new Error('Ответ содержит другую выдачу доступа');
    return;
  }
  const result = command.kind === 'reject' ? await api.DELETE('/api/v1/admin/teachers/{teacherId}/application', { params })
    : await api.DELETE('/api/v1/admin/teachers/{teacherId}/grants/{grantId}', { params: { path: { teacherId, grantId: command.grantId } } });
  if (!result.response.ok) throw new Error('Не удалось выполнить действие');
}
export function useTeacherAction(teacherId: number, finished: (command: TeacherCommand) => void) {
  const client = useQueryClient();
  const invalidate = () => { void client.invalidateQueries({ predicate: (query) => ['admin-teachers', 'profile', 'activities', 'group-rating'].includes(String(query.queryKey[0])) }); };
  const mutation = useMutation({ retry: false, mutationFn: (command: TeacherCommand) => write(teacherId, command), onSuccess: (_data, command) => { invalidate(); finished(command); } });
  const refresh = useMutation({ retry: false, mutationFn: async () => { await csrfToken(); client.setQueryData(authKeys.csrf, true); mutation.reset(); } });
  const reload = () => { invalidate(); mutation.reset(); };
  return { mutation, refresh, reload };
}
