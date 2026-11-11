import { authUsers } from '../../test/authAcceptance';
import { signedAmount, taskDestination } from './presentation';
import type { ProfileTask } from './contracts';

it('formats actual signed amounts in all currencies', () => {
  expect(signedAmount(50, 'BOA')).toBe('+50 БОА');
  expect(signedAmount(-20, 'AR')).toBe('−20 АР');
  expect(signedAmount(38, 'BUA')).toBe('+38 БУА');
});
const base = { title: 'Task', deadline: '2026-11-12T09:00:00Z', status: 'OVERDUE' as const, charged: 40 };
it('permits only an own task or administrator material link', () => {
  const own: ProfileTask = { ...base, view: 'OWN', assignmentId: 7001 };
  const admin: ProfileTask = { ...base, view: 'ADMIN', assignmentId: 7001 };
  expect(taskDestination(own, authUsers.student, 1001)).toBe('/tasks/7001');
  expect(taskDestination(own, authUsers.teacher, 1001)).toBeNull();
  expect(taskDestination(admin, authUsers.teacher, 1001)).toBeNull();
  expect(taskDestination(admin, authUsers.admin, 1001)).toBe('/admin/submissions/7001');
  expect(taskDestination({ ...base, view: 'PUBLIC' }, authUsers.admin, 1001)).toBeNull();
});
