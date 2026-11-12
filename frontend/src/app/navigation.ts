import type { NavIconName } from './NavIcon';
import type { Session } from './session';

export type NavItem = {
  label: string;
  to: string;
  icon: NavIconName;
};

export function navigationFor(session: Session): NavItem[] {
  const profile = { label: 'Профиль', to: `/users/${session.userId}`, icon: 'profile' as const };
  switch (session.role) {
    case 'STUDENT':
      return [
        { label: 'Главная', to: '/', icon: 'home' },
        { label: 'Задачи', to: '/tasks', icon: 'tasks' },
        { label: 'Активности', to: '/activities', icon: 'activities' },
        { label: 'Награды', to: '/awards', icon: 'awards' },
        profile,
      ];
    case 'TEACHER':
      return [
        { label: 'Главная', to: '/', icon: 'home' },
        { label: 'Точки', to: '/checkpoints', icon: 'checkpoints' },
        { label: 'Активности', to: '/activities', icon: 'activities' },
        { label: 'Группы', to: '/groups', icon: 'groups' },
        profile,
      ];
    case 'ADMIN':
      return [
        { label: 'Справочники', to: '/admin/directory', icon: 'directory' },
        { label: 'Пользователи', to: '/admin/users', icon: 'users' },
        { label: 'Импорт', to: '/admin/import', icon: 'import' },
        { label: 'Семестры', to: '/admin/semesters', icon: 'semesters' },
        { label: 'Настройки', to: '/admin/settings', icon: 'settings' },
        { label: 'Бюджеты', to: '/admin/budgets', icon: 'budgets' },
      ];
  }
}

export function isActive(pathname: string, to: string): boolean {
  return to === '/' ? pathname === '/' : pathname === to || pathname.startsWith(`${to}/`);
}
