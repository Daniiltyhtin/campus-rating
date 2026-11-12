import type { UseQueryResult } from '@tanstack/react-query';
import { ApiError } from '../../api/client';
import type { components } from '../../api/schema';
import type { LoadState } from '../../app/layouts/PageLayouts';
import type { QueryClient } from '@tanstack/react-query';
export type Schemas = components['schemas'];
export const statuses = {
  IN_PROGRESS: 'В процессе',
  OVERDUE: 'Просрочено',
  SUBMITTED: 'Сдано на проверку',
  ACCEPTED: 'Принято',
};

export function dateTime(value: string) {
  return new Intl.DateTimeFormat('ru-RU', {
    timeZone: 'Europe/Moscow',
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value));
}
const messages: Partial<Record<ApiError['code'], string>> = {
  FILE_TOO_LARGE: 'Файл больше 10 МБ',
  FILE_TYPE_NOT_ALLOWED: 'Можно pdf, docx, zip, png или jpg',
  GROUP_NOT_ACCESSIBLE: 'Группа вам недоступна',
  VALIDATION_FAILED: 'Проверьте заполнение полей',
  INVALID_STATE: 'Состояние работы изменилось. Обновите данные.',
  FORBIDDEN: 'Нет доступа',
  NOT_FOUND: 'Данные не найдены',
};

export function errorText(error: unknown): string {
  if (error instanceof ApiError)
    return error.code === 'GROUP_NOT_ACCESSIBLE' || error.code === 'INVALID_STATE'
      ? (error.problem.detail ?? messages[error.code]!)
      : (messages[error.code] ?? 'Не удалось выполнить действие. Попробуйте снова.');
  return 'Не удалось выполнить действие. Проверьте соединение и попробуйте снова.';
}

export function fieldError(error: unknown, field: string): string | undefined {
  if (!(error instanceof ApiError)) return;
  if (field === 'file' && ['FILE_TOO_LARGE', 'FILE_TYPE_NOT_ALLOWED'].includes(error.code)) return errorText(error);
  const problem = error.problem;
  if ('fieldErrors' in problem && Array.isArray(problem.fieldErrors)) {
    const found: unknown = problem.fieldErrors.find(
      (entry: unknown) => !!entry && typeof entry === 'object' && 'field' in entry && entry.field === field,
    );
    if (found && typeof found === 'object' && 'message' in found && typeof found.message === 'string')
      return found.message;
  }
}

export function loadState(
  query: Pick<UseQueryResult, 'isPending' | 'isError' | 'error' | 'refetch'>,
  empty = false,
  message = 'Здесь пока нет данных',
): LoadState {
  if (query.isPending) return { status: 'loading' };
  if (query.isError)
    return {
      status: 'error',
      message: errorText(query.error),
      retry: () => {
        void query.refetch();
      },
    };
  return empty ? { status: 'empty', message } : { status: 'ready' };
}

export async function dataOf<T>(request: Promise<{ data?: T; error?: unknown }>): Promise<T> {
  const response = await request;
  if (response.data === undefined) throw new Error('Нет данных');
  return response.data;
}

export function moscowDeadline(local: string) {
  const moment = new Date(`${local.slice(0, 16)}:00+03:00`);
  return Number.isNaN(moment.getTime()) ? local : moment.toISOString();
}

export function invalidateTaskData(client: QueryClient) {
  return client.invalidateQueries({ predicate: (query) => query.queryKey[0] !== 'auth' });
}
