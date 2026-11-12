import type { Session } from './session';

export type NavItem = {
  label: string;
  to: string;
};

export function navigationFor(session: Session): NavItem[] {
  const profile = { label: 'Профиль', to: `/users/${session.userId}` };
  switch (session.role) {
    case 'STUDENT':
      return [
        { label: 'Главная', to: '/' },
        { label: 'Задачи', to: '/tasks' },
        { label: 'Активности', to: '/activities' },
        { label: 'Награды', to: '/awards' },
        profile,
      ];
    case 'TEACHER':
      return [
        { label: 'Главная', to: '/' },
        { label: 'Точки', to: '/checkpoints' },
        { label: 'Активности', to: '/activities' },
        { label: 'Группы', to: '/groups' },
        profile,
      ];
    case 'ADMIN':
      return [
        { label: 'Справочники', to: '/admin/directory' },
        { label: 'Пользователи', to: '/admin/users' },
        { label: 'Импорт', to: '/admin/import' },
        { label: 'Семестры', to: '/admin/semesters' },
        { label: 'Настройки', to: '/admin/settings' },
        { label: 'Бюджеты', to: '/admin/budgets' },
      ];
  }
}

export function isActive(pathname: string, to: string): boolean {
  return to === '/' ? pathname === '/' : pathname === to || pathname.startsWith(`${to}/`);
}
