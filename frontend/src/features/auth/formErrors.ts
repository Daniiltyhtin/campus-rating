import { ApiError } from '../../api/client';

export type FormErrors = Record<string, string>;
export function emailError(email: string): string | undefined {
  return email.length <= 254 && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) ? undefined : 'Укажите корректный email';
}
export function passwordError(password: string): string | undefined {
  if ([...password].length < 8) return 'Пароль должен содержать не меньше 8 символов';
  if (new TextEncoder().encode(password).length > 72) return 'Пароль не должен превышать 72 байта UTF-8';
}
export function fieldErrors(error: unknown): FormErrors {
  if (!(error instanceof ApiError)) return {};
  switch (error.code) {
    case 'EMAIL_TAKEN': return { email: 'Этот email уже зарегистрирован' };
    case 'EMAIL_RESERVED_FOR_TEACHER': return { email: 'Этот email закреплён за преподавателем. Зарегистрируйтесь как преподаватель.' };
    case 'PASSWORD_REUSED': return { newPassword: 'Новый пароль должен отличаться от текущего' };
    case 'VALIDATION_FAILED': return 'fieldErrors' in error.problem
      ? Object.fromEntries(error.problem.fieldErrors.map((item) => [item.field, item.message])) : {};
    default: return {};
  }
}
export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'INVALID_CREDENTIALS': return 'Неверный email или пароль';
      case 'EMAIL_NOT_VERIFIED': return 'Подтвердите email по ссылке из письма';
      case 'CSRF_INVALID': return 'Защита формы устарела. Обновите её и повторите действие.';
      case 'FORBIDDEN': return 'Недостаточно прав для этого действия';
      case 'VALIDATION_FAILED': return 'Проверьте поля формы';
      case 'RATE_LIMITED': return 'Слишком много попыток. Подождите минуту и попробуйте снова.';
    }
  }
  return 'Не удалось выполнить действие. Проверьте соединение и попробуйте снова.';
}
