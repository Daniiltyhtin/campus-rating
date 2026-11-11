import { ApiError } from '../../api/client';

export function activityError(error: unknown): string {
  if (!(error instanceof ApiError)) return 'Не удалось загрузить данные. Проверьте соединение и повторите.';
  switch (error.code) {
    case 'ENROLLMENT_CLOSED': return 'Запись уже закрыта: активность началась.';
    case 'MEMBERSHIP_NOT_ACTIVE': return 'Членство в группе ждёт подтверждения. Пока записаться нельзя.';
    case 'GROUP_NOT_ACCESSIBLE': return 'Одна из групп больше недоступна. Обновите список групп.';
    case 'FORBIDDEN': return 'Нет доступа';
    case 'NOT_FOUND': return 'Активность не найдена';
    case 'INVALID_STATE': return 'Действие сейчас недоступно. Обновите активность.';
    case 'NOT_ENROLLED': return 'Участники не записаны на общественную активность';
    case 'STUDENT_NOT_ELIGIBLE': return 'Участники больше недоступны для этой активности';
    case 'ALREADY_CONFIRMED': return 'Участие уже подтверждено с другим уровнем';
    case 'BUDGET_EXCEEDED': return 'Бюджет не позволяет выполнить подтверждение. Начислений нет.';
    case 'VALIDATION_FAILED': return 'Проверьте поля формы';
    case 'CSRF_INVALID': return 'Защита формы устарела. Обновите страницу и повторите действие.';
    default: return 'Не удалось выполнить действие. Попробуйте снова.';
  }
}
