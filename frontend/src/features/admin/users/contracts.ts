import type { components, operations } from '../../../api/schema';

type Filters = NonNullable<operations['listAdminUsers']['parameters']['query']>;
export type UserRow = components['schemas']['AdminUser'];
export const statusNames = {
  PENDING: 'Заявка', APPROVED: 'Допущен', MEMBERSHIP_PENDING: 'Ждёт подтверждения', MEMBERSHIP_REJECTED: 'Заявка отклонена', MEMBERSHIP_ACTIVE: 'Членство подтверждено',
};
export function userFilters(params: URLSearchParams): Filters {
  return {
    search: params.get('search') ?? '',
    role: (['STUDENT', 'TEACHER', 'ADMIN'] as const).find((value) => value === params.get('role')),
    status: (['PENDING', 'APPROVED', 'MEMBERSHIP_PENDING', 'MEMBERSHIP_REJECTED', 'MEMBERSHIP_ACTIVE'] as const).find((value) => value === params.get('status')),
    sort: sortKeys.find((value) => value === params.get('sort')) ?? 'lastName',
    direction: (['asc', 'desc'] as const).find((value) => value === params.get('direction')),
  };
}
export const sortKeys = ['lastName', 'firstName', 'middleName', 'email', 'role', 'unit', 'status', 'created', 'lastLogin'] as const;
export type SortKey = typeof sortKeys[number];
export function defaultDirection(key: SortKey): 'asc' | 'desc' { return key === 'created' ? 'desc' : 'asc'; }
export function userTime(value: string | null | undefined): string {
  return value == null ? '—' : new Intl.DateTimeFormat('ru-RU', {
    timeZone: 'Europe/Moscow', day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit',
  }).format(new Date(value)).replace(',', '');
}
export function requestsTitle(count: number): string {
  const tens = count % 100, ones = count % 10;
  if (tens >= 11 && tens <= 14) return `${count} заявок преподавателей ждут решения`;
  if (ones === 1) return `${count} заявка преподавателя ждёт решения`;
  if (ones >= 2 && ones <= 4) return `${count} заявки преподавателей ждут решения`;
  return `${count} заявок преподавателей ждут решения`;
}
