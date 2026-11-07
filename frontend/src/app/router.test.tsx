import { screen } from '@testing-library/react';
import { renderApp } from '../test/render';

describe('маршруты без сессии', () => {
  it('ведёт с главной на /login', async () => {
    const router = renderApp('/');

    expect(await screen.findByRole('button', { name: 'Войти' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/login');
  });

  it('показывает на /login поля Email, Пароль и кнопку Войти', async () => {
    renderApp('/login');

    expect(await screen.findByLabelText('Email')).toBeInTheDocument();
    expect(screen.getByLabelText('Пароль')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Войти' })).toBeInTheDocument();
  });

  it('ведёт с неизвестного маршрута на /login', async () => {
    const router = renderApp('/no/such/page');

    expect(await screen.findByRole('button', { name: 'Войти' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/login');
  });
});
