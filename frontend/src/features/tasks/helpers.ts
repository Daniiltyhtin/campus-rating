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
  SUBMISSION_LIMIT_REACHED: 'Достигнут предел сдач по этой задаче. Обратитесь к преподавателю.',
  RATE_LIMITED: 'Слишком много попыток. Подождите минуту и попробуйте снова.',
  FORBIDDEN: 'Нет доступа',
  NOT_FOUND: 'Данные не найдены',
};

export function errorText(error: unknown): string {
  if (error instanceof ApiError)
    return error.code === 'GROUP_NOT_ACCESSIBLE' || error.code === 'INVALID_STATE' || error.code === 'SUBMISSION_LIMIT_REACHED'
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

export async function dataOf<T>(request: Promise<{ data?: T; error?: unknown; response?: Response }>): Promise<T> {
  const response = await request;
  if (response.error) throw response.error;
  if (response.data === undefined) {
    const status = response.response?.status;
    if (status === 403 || status === 404) throw new ApiError({ type: 'about:blank', title: 'Error', status, code: status === 403 ? 'FORBIDDEN' : 'NOT_FOUND' });
    throw new Error('Нет данных');
  }
  return response.data;
}

export function moscowDeadline(local: string) {
  const moment = new Date(`${local.slice(0, 16)}:00+03:00`);
  return Number.isNaN(moment.getTime()) ? local : moment.toISOString();
}

export function invalidateTaskData(client: QueryClient) {
  return client.invalidateQueries({ predicate: (query) => query.queryKey[0] !== 'auth' });
}

export function fileValidation(file: File): string | undefined {
  if (!/\.(pdf|docx|zip|png|jpg)$/i.test(file.name)) return 'Можно загрузить pdf, docx, zip, png или jpg';
  if (file.size > 10485760) return 'Файл больше 10 МБ';
}

export function fileSize(bytes: number): string {
  return bytes >= 1048576 ? `${(bytes / 1048576).toFixed(1).replace('.', ',')} МБ` : `${Math.ceil(bytes / 1024)} КБ`;
}

export function linkValidation(link: string): string | undefined {
  if (!link.trim()) return 'Укажите ссылку на работу';
  try {
    const url = new URL(link);
    if (['http:', 'https:'].includes(url.protocol) && link.length <= 2000) return;
  } catch { return 'Укажите ссылку http или https длиной до 2000 символов'; }
  return 'Укажите ссылку http или https длиной до 2000 символов';
}

export function teacherCommentLabel(author: string, returnedAt: string): string {
  const [surname, ...names] = author.trim().split(/\s+/);
  const name = [surname, ...names.map((part) => `${part[0]}.`)].join(' ');
  const date = new Intl.DateTimeFormat('ru-RU', { timeZone: 'Europe/Moscow', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }).format(new Date(returnedAt));
  return `Замечание преподавателя · ${name} · ${date}`;
}

export function isAccessFailure(error: unknown) {
  return error instanceof ApiError && (error.code === 'FORBIDDEN' || error.code === 'NOT_FOUND');
}
