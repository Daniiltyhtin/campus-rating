import { authUsers } from '../../test/authAcceptance';
import { authConfig, currentUser, options, registrationResult } from './contracts';

it.each(Object.values(authUsers))('accepts the contract user $role/$id', (user) => {
  expect(currentUser(user)).toEqual(user);
});
it.each([null, { ...authUsers.student, role: 'ROOT' }, { ...authUsers.student, id: '1001' },
  { ...authUsers.student, mustChangePassword: 'false' }, { ...authUsers.student, group: { id: 11 } },
  { ...authUsers.teacher, teacherApproved: 'yes' }])('rejects an invalid external session %#', (value) => {
  expect(() => currentUser(value)).toThrow();
});
it('does not invent OFF for an invalid configuration or login for an unknown next step', () => {
  expect(() => authConfig({ demo: true, emailVerification: 'off' })).toThrow();
  expect(() => authConfig({ demo: true })).toThrow();
  expect(() => registrationResult({ userId: 1001, nextStep: 'AUTO_LOGIN' })).toThrow();
  expect(registrationResult({ userId: 1001, nextStep: 'CONFIRM_EMAIL' })).toEqual({ userId: 1001, nextStep: 'CONFIRM_EMAIL' });
});
it('rejects a malformed directory instead of submitting a fabricated id', () => {
  expect(() => options({ items: [{ id: '11', name: 'ИВТ-21' }] })).toThrow();
  expect(() => options({ items: null })).toThrow();
  expect(options({ items: [] })).toEqual([]);
});
