import { screen, within } from '@testing-library/react';
import { renderApp } from '../test/render';
import { setViewportWidth } from '../test/setup';
import type { Session } from './session';

const student: Session = { userId: 'student-1', role: 'STUDENT' };
const teacher: Session = { userId: 'teacher-1', role: 'TEACHER' };
const admin: Session = { userId: 'admin-1', role: 'ADMIN' };

function linkNames(navigation: HTMLElement) {
  return within(navigation)
    .getAllByRole('link')
    .map((link) => link.textContent);
}

describe('AppShell', () => {
  it('на десктопе показывает боковое меню студента и не показывает нижнее', async () => {
    renderApp('/', student);

    const side = await screen.findByRole('navigation', { name: 'Боковое меню' });
    expect(linkNames(side)).toEqual(['Главная', 'Задачи', 'Активности', 'Награды', 'Профиль']);
    expect(screen.queryByRole('navigation', { name: 'Нижнее меню' })).not.toBeInTheDocument();
  });

  it('на мобильном показывает нижнее меню и не показывает боковое', async () => {
    setViewportWidth(360);
    renderApp('/', student);

    const bottom = await screen.findByRole('navigation', { name: 'Нижнее меню' });
    expect(screen.queryByRole('navigation', { name: 'Боковое меню' })).not.toBeInTheDocument();
    for (const name of ['Главная', 'Задачи', 'Активности', 'Награды', 'Профиль']) {
      expect(within(bottom).getByRole('link', { name })).toBeInTheDocument();
    }
  });

  it('ведёт ссылку «Профиль» на профиль текущего пользователя', async () => {
    renderApp('/', student);

    const side = await screen.findByRole('navigation', { name: 'Боковое меню' });
    expect(within(side).getByRole('link', { name: 'Профиль' })).toHaveAttribute(
      'href',
      '/users/student-1',
    );
  });

  it('даёт преподавателю пункты преподавателя', async () => {
    renderApp('/', teacher);

    const side = await screen.findByRole('navigation', { name: 'Боковое меню' });
    expect(linkNames(side)).toEqual(['Главная', 'Точки', 'Активности', 'Группы', 'Профиль']);
  });

  it('даёт администратору пункты администратора', async () => {
    renderApp('/admin/directory', admin);

    const side = await screen.findByRole('navigation', { name: 'Боковое меню' });
    expect(linkNames(side)).toEqual(['Справочники', 'Импорт', 'Семестры', 'Настройки', 'Старосты']);
  });

  it('показывает заглушку раздела внутри AppShell', async () => {
    renderApp('/tasks', student);

    expect(await screen.findByText('Раздел появится')).toBeInTheDocument();
    expect(screen.getByRole('navigation', { name: 'Боковое меню' })).toBeInTheDocument();
  });
});
