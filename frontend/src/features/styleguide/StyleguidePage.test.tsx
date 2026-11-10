import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';
import { renderApp } from '../../test/render';

describe('styleguide', () => {
  afterEach(() => localStorage.removeItem('campus-appearance'));

  it('opens without changing access to product pages and presents all templates', async () => {
    const router = renderApp('/styleguide');
    expect(await screen.findByRole('heading', { name: 'Редакция' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/styleguide');
    expect(screen.getByRole('heading', { name: 'Список задач' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Карточка студента' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Форма' })).toBeInTheDocument();
    expect(screen.getByRole('table', { name: 'Работы студентов' })).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: /Название/ })).toBeInTheDocument();
    await router.navigate('/');
    await waitFor(() => expect(router.state.location.pathname).toBe('/login'));
  });

  it('shows loading, empty and error states and retries', async () => {
    const user = userEvent.setup();
    renderApp('/styleguide');
    await user.click(await screen.findByRole('radio', { name: 'Загрузка' }));
    expect(screen.getAllByRole('status').length).toBeGreaterThan(0);
    await user.click(screen.getByRole('radio', { name: 'Пусто' }));
    expect(screen.getAllByText('Здесь пока нет данных').length).toBeGreaterThan(0);
    await user.click(screen.getByRole('radio', { name: 'Ошибка' }));
    expect(screen.getAllByRole('alert').length).toBeGreaterThan(0);
    await user.click(screen.getAllByRole('button', { name: 'Повторить' })[0]!);
    expect(screen.getAllByText('Лабораторная 3. Деревья поиска').length).toBeGreaterThan(0);
  });

  it('offers the final contrast theme and reports partial acceptance without losing selection', async () => {
    const user = userEvent.setup();
    renderApp('/styleguide');
    await user.click(await screen.findByRole('combobox', { name: 'Тема интерфейса' }));
    await user.keyboard('{ArrowDown}{ArrowDown}{Enter}');
    expect(screen.getByRole('combobox', { name: 'Тема интерфейса' })).toHaveValue('Высокий контраст');
    expect(localStorage.getItem('campus-appearance')).toBe('"contrast"');
    await user.click(screen.getByRole('button', { name: 'Принять' }));
    expect(screen.getByRole('status')).toHaveTextContent('Принято 1 из 2.');
    expect(screen.getByRole('checkbox', { name: 'Смирнова Анна Олеговна' })).toBeChecked();
    await user.click(screen.getByRole('button', { name: 'Снять выбор' }));
    expect(screen.getByRole('button', { name: 'Принять' })).toBeDisabled();
  });
});
