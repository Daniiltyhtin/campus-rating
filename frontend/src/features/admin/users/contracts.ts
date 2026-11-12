import type { components, operations } from '../../../api/schema';

type Filters = NonNullable<operations['listAdminUsers']['parameters']['query']>;
export type UserRow = components['schemas']['AdminUser'];
export const statusNames = {
  PENDING: 'Заявка', APPROVED: 'Допущен', MEMBERSHIP_PENDING: 'Ждёт подтверждения', MEMBERSHIP_ACTIVE: 'Членство подтверждено',
};
export function userFilters(params: URLSearchParams): Filters {
  return {
    search: params.get('search') ?? '',
    role: (['STUDENT', 'TEACHER', 'ADMIN'] as const).find((value) => value === params.get('role')),
    status: (['PENDING', 'APPROVED', 'MEMBERSHIP_PENDING', 'MEMBERSHIP_ACTIVE'] as const).find((value) => value === params.get('status')),
    sort: (['name', 'created', 'lastLogin'] as const).find((value) => value === params.get('sort')) ?? 'name',
  };
}
export function userTime(value: string | null | undefined): string {
  return value == null ? '—' : new Intl.DateTimeFormat('ru-RU', {
    timeZone: 'Europe/Moscow', day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit',
  }).format(new Date(value)).replace(',', '');
}
