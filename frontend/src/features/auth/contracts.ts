import type { components } from '../../api/schema';

export type CurrentUser = components['schemas']['CurrentUser'];
export type AuthConfig = components['schemas']['AuthConfig'];
export type RegistrationResult = components['schemas']['RegistrationResult'];
export type Option = { id: number; name: string };

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}
function id(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0;
}
function faculty(value: unknown): boolean {
  return record(value) && id(value.id) && typeof value.name === 'string' && typeof value.code === 'string';
}
function group(value: unknown): boolean {
  return record(value) && id(value.id) && typeof value.name === 'string' && faculty(value.faculty);
}
function department(value: unknown): boolean {
  return group(value) && record(value) && typeof value.code === 'string';
}
function isCurrentUser(value: unknown): value is CurrentUser {
  if (!record(value) || !id(value.id) || typeof value.fullName !== 'string'
    || typeof value.email !== 'string' || typeof value.mustChangePassword !== 'boolean') return false;
  switch (value.role) {
    case 'STUDENT': return group(value.group) && ['ACTIVE', 'PENDING', 'REJECTED'].includes(String(value.membershipState)) && typeof value.groupHead === 'boolean';
    case 'TEACHER': return department(value.department) && typeof value.teacherApproved === 'boolean';
    case 'ADMIN': return true;
    default: return false;
  }
}
export function currentUser(value: unknown): CurrentUser {
  if (!isCurrentUser(value)) throw new Error('Некорректный ответ сессии');
  return value;
}
export function authConfig(value: unknown): AuthConfig {
  if (!record(value) || typeof value.demo !== 'boolean'
    || (value.emailVerification !== 'OFF' && value.emailVerification !== 'REQUIRED')) throw new Error('Некорректные настройки');
  return { demo: value.demo, emailVerification: value.emailVerification };
}
export function registrationResult(value: unknown): RegistrationResult {
  if (!record(value) || !id(value.userId) || (value.nextStep !== 'LOGIN' && value.nextStep !== 'CONFIRM_EMAIL')) throw new Error('Некорректный итог регистрации');
  return { userId: value.userId, nextStep: value.nextStep };
}
export function options(value: unknown): Option[] {
  if (!record(value) || !Array.isArray(value.items)) throw new Error('Некорректный справочник');
  return value.items.map((item: unknown) => {
    if (!record(item) || !id(item.id) || typeof item.name !== 'string') throw new Error('Некорректная запись справочника');
    return { id: item.id, name: item.name };
  });
}
