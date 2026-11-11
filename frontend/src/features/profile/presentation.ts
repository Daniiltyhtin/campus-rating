import type { CurrentUser } from '../auth/contracts';
import type { Currency, ProfileTask } from './contracts';
export const currencyNames: Record<Currency, string> = { AR: 'АР', BUA: 'БУА', BOA: 'БОА' };
export function signedAmount(amount: number, currency: Currency) { return `${amount < 0 ? '−' : '+'}${Math.abs(amount)} ${currencyNames[currency]}`; }
export function taskDestination(task: ProfileTask, viewer: CurrentUser, ownerId: number): string | null {
  if (task.view === 'OWN' && viewer.id === ownerId) return `/tasks/${task.assignmentId}`;
  if (task.view === 'ADMIN' && viewer.role === 'ADMIN') return `/admin/submissions/${task.assignmentId}`;
  return null;
}
