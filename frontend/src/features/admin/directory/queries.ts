import { useTimedMutation } from '../../../app/actionTiming';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../../../api/client';
import { authKeys, csrfToken } from '../../auth/authQueries';
import { parseDirectoryEntry, parseDirectoryList } from './contracts';
import type { DirectoryKind, DirectoryWrite } from './contracts';

const collection = { faculties: '/api/v1/admin/directory/faculties', groups: '/api/v1/admin/directory/groups', departments: '/api/v1/admin/directory/departments' } as const;
const individual = { faculties: '/api/v1/admin/directory/faculties/{id}', groups: '/api/v1/admin/directory/groups/{id}', departments: '/api/v1/admin/directory/departments/{id}' } as const;
export function directoryError(error: unknown) {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'DIRECTORY_IN_USE': return 'Запись используется. Сначала устраните связи: группу со студентами удалить нельзя.';
      case 'DIRECTORY_CODE_TAKEN': return 'Не удалось сохранить запись: проверьте код';
      case 'NOT_FOUND': return 'Запись уже удалена или больше не существует';
      case 'FORBIDDEN': return 'Нет доступа к этому действию';
      case 'CSRF_INVALID': return 'Защита формы устарела. Обновите защиту и повторите действие.';
      case 'VALIDATION_FAILED': return 'Проверьте поля формы';
    }
  }
  return 'Не удалось подтвердить результат. Проверьте соединение и обновите список перед повтором действия.';
}
export function useDirectory(kind: DirectoryKind, viewerId: number) {
  return useQuery({ queryKey: ['admin-directory', viewerId, kind], retry: false, queryFn: async ({ signal }) => {
    const { data, response } = await api.GET(collection[kind], { signal });
    if (!response.ok) throw new Error('Не удалось загрузить справочник');
    return parseDirectoryList(kind, data);
  } });
}
type Command = { action: 'save'; id?: number; body: DirectoryWrite } | { action: 'delete'; id: number };
async function write(kind: DirectoryKind, command: Command) {
  if (command.action === 'delete') {
    const result = await api.DELETE(individual[kind], { params: { path: { id: command.id } } });
    if (!result.response.ok) throw new Error('Не удалось удалить запись');
    return;
  }
  const result = command.id === undefined ? await api.POST(collection[kind], { body: command.body })
    : await api.PUT(individual[kind], { params: { path: { id: command.id } }, body: command.body });
  if (!result.response.ok) throw new Error('Не удалось сохранить запись');
  const entry = parseDirectoryEntry(kind, result.data);
  if (command.id !== undefined && entry.id !== command.id) throw new Error('Ответ содержит другую запись');
}
export function useDirectoryAction(kind: DirectoryKind, finished: (message: string) => void) {
  const client = useQueryClient();
  const invalidate = () => {
    void client.invalidateQueries({ predicate: (query) => ['admin-directory', 'directory', 'profile'].includes(String(query.queryKey[0]))
      || (query.queryKey[0] === 'activities' && query.queryKey[2] === 'groups') });
  };
  const mutation = useTimedMutation({ retry: false, mutationFn: (command: Command) => write(kind, command),
    onSuccess: (_data, command) => { finished(command.action === 'delete' ? 'Запись удалена' : 'Запись сохранена'); invalidate(); },
    onError: (error) => { if (error instanceof ApiError && error.code === 'NOT_FOUND') { finished('Запись уже удалена или больше не существует'); invalidate(); } } });
  const refresh = useTimedMutation({ retry: false, mutationFn: async () => { await csrfToken(); client.setQueryData(authKeys.csrf, true); mutation.reset(); } });
  return { mutation, refresh };
}
