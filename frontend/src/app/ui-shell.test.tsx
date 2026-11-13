import { DEFAULT_THEME, mergeMantineTheme } from '@mantine/core';
import { cleanup, render, screen, within, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { vi } from 'vitest';
import { renderApp } from '../test/render';
import { setViewportWidth } from '../test/setup';
import { authUsers, createAuthScenario, mountAuth } from '../test/authAcceptance';
import { server } from '../mocks/server';
import { CampusThemeProvider } from '../theme/CampusThemeProvider';
import { AsyncContent, PageLayout } from './layouts/PageLayouts';
import { RouteLoading } from './routeElements';
import { readFileSync } from 'node:fs';
import { contrastVariablesResolver, cssVariablesResolver, theme } from '../theme/theme';

const styles = readFileSync('src/theme/theme.css', 'utf8');

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }));
afterEach(() => { cleanup(); server.resetHandlers(); localStorage.clear(); vi.restoreAllMocks(); });
afterAll(() => server.close());

it.each([
  ['STUDENT', '/tasks', '/'], ['TEACHER', '/checkpoints', '/'], ['ADMIN', '/admin/settings', '/admin/directory'],
] as const)('R25: %s brand links to the first role destination with an inline currentColor mark', async (role, path, destination) => {
  renderApp(path, { userId: '2001', role });
  const brand = await screen.findByRole('link', { name: 'Campus Rating' });
  expect(brand).toHaveAttribute('href', destination);
  expect(readFileSync('index.html', 'utf8')).toContain('href="/favicon.svg"');
  expect(brand.querySelector('svg')).toHaveAttribute('fill', 'currentColor');
  expect(brand.querySelector('svg')).toHaveAttribute('aria-hidden', 'true');
  expect(styles).toMatch(/\.cr-brand-mark\s*\{[^}]*width:\s*1\.75rem/);
});

it('R26: page headings have no repeated brand caption', () => {
  render(<CampusThemeProvider><PageLayout title="Задачи">Содержимое</PageLayout></CampusThemeProvider>);
  expect(screen.getByRole('heading', { name: 'Задачи' })).toBeInTheDocument();
  expect(screen.queryByText('Campus Rating')).not.toBeInTheDocument();
});

it.each([360, 1279, 1280])('R27, R38: avatar and compact header at %i px', async (width) => {
  setViewportWidth(width);
  createAuthScenario(authUsers.teacher);
  mountAuth('/');
  const button = await screen.findByRole('button', { name: `Меню пользователя: ${authUsers.teacher.fullName}` });
  expect(within(button).getByText('ПП')).toHaveClass('cr-user-avatar');
  expect(button.querySelector('.cr-user-name')?.textContent ?? null).toBe(width >= 1280 ? 'Петров П. П.' : null);
  expect(styles).toMatch(/\.cr-user-avatar\s*\{[^}]*width:\s*2\.125rem[^}]*height:\s*2\.125rem[^}]*background:\s*var\(--cr-primary-soft\)[^}]*color:\s*var\(--cr-primary\)/);
  expect(Array.from(document.querySelectorAll('style')).some((style) => /--app-shell-header-height:\s*(?:calc\()?3\.5rem/.test(style.textContent ?? ''))).toBe(true);
});

it('R28, R29: user menu preserves links and selected theme after remount', async () => {
  createAuthScenario(authUsers.teacher);
  let view = mountAuth('/');
  await view.user.click(await screen.findByRole('button', { name: /Меню пользователя:/ }));
  expect(screen.getByText(authUsers.teacher.fullName)).toBeInTheDocument();
  expect(screen.getByRole('menuitem', { name: 'Мой профиль' })).toHaveAttribute('href', `/users/${authUsers.teacher.id}`);
  expect(screen.getByRole('menuitem', { name: 'Сменить пароль' })).toHaveAttribute('href', '/password/change');
  expect(screen.getByRole('menuitem', { name: 'Выйти' })).toBeEnabled();
  await view.user.click(screen.getByRole('menuitem', { name: 'Тема' }));
  await view.user.click(await screen.findByRole('menuitemradio', { name: 'Тёмная' }));
  await waitFor(() => expect(document.documentElement.dataset.theme).toBe('dark'));
  expect(JSON.parse(localStorage.getItem('campus-appearance')!)).toBe('dark');
  view.unmount(); view.router.dispose(); view.queryClient.clear();
  view = mountAuth('/');
  await view.user.click(await screen.findByRole('button', { name: /Меню пользователя:/ }));
  await view.user.click(screen.getByRole('menuitem', { name: 'Тема' }));
  expect(await screen.findByRole('menuitemradio', { name: 'Тёмная' })).toHaveAttribute('aria-checked', 'true');
  await view.user.click(screen.getByRole('menuitemradio', { name: 'Светлая' }));
  await waitFor(() => expect(document.documentElement.dataset.theme).toBe('light'));
  expect(screen.queryByLabelText('Тема интерфейса')).not.toBeInTheDocument();
});

it.each(['/login', '/register'])('R30, R29: guest theme switch on %s carries the theme into login', async (path) => {
  const state = createAuthScenario(); state.loginUser = authUsers.teacher;
  const view = mountAuth(path);
  const toggle = await screen.findByRole('button', { name: 'Тема' });
  expect(toggle).toHaveAttribute('aria-expanded', 'false');
  await view.user.click(toggle);
  expect(toggle).toHaveAttribute('aria-expanded', 'true');
  const options = within(screen.getByRole('group', { name: 'Выбор темы' })).getAllByRole('button');
  expect(options.map((option) => option.getAttribute('aria-label'))).toEqual(['Светлая', 'Тёмная', 'Системная']);
  await view.user.click(screen.getByRole('button', { name: 'Тёмная' }));
  await waitFor(() => expect(document.documentElement.dataset.theme).toBe('dark'));
  expect(toggle).toHaveAttribute('aria-expanded', 'false');
  expect(toggle).toHaveFocus();
  await view.user.click(toggle);
  expect(screen.getByRole('button', { name: 'Тёмная' })).toHaveAttribute('aria-pressed', 'true');
  await view.user.keyboard('{Escape}');
  expect(toggle).toHaveAttribute('aria-expanded', 'false');
  if (path === '/register') await view.user.click(screen.getByRole('link', { name: 'Войти' }));
  await view.user.type(await screen.findByLabelText('Email'), 'teacher@campus.test');
  await view.user.type(screen.getByLabelText('Пароль', { exact: true }), 'password');
  await view.user.click(screen.getByRole('button', { name: 'Войти' }));
  await view.user.click(await screen.findByRole('button', { name: /Меню пользователя:/ }));
  await view.user.click(screen.getByRole('menuitem', { name: 'Тема' }));
  expect(await screen.findByRole('menuitemradio', { name: 'Тёмная' })).toHaveAttribute('aria-checked', 'true');
  expect(screen.queryByRole('menuitemradio', { name: 'Высокий контраст' })).not.toBeInTheDocument();
});

it('R29: system theme follows the operating system color scheme', async () => {
  const original = window.matchMedia;
  window.matchMedia = (query: string) => ({ ...original(query), matches: query.includes('prefers-color-scheme: dark') || original(query).matches });
  try {
    localStorage.setItem('campus-appearance', JSON.stringify('system'));
    createAuthScenario();
    const view = mountAuth('/login');
    await waitFor(() => expect(document.documentElement.dataset.theme).toBe('dark'));
    await view.user.click(await screen.findByRole('button', { name: 'Тема' }));
    expect(screen.getByRole('button', { name: 'Системная' })).toHaveAttribute('aria-pressed', 'true');
  } finally {
    window.matchMedia = original;
  }
});

it.each(['light', 'dark', 'system'])('R40: mobile active item uses primary tokens and every item has a decorative icon in %s', async (appearance) => {
  localStorage.setItem('campus-appearance', JSON.stringify(appearance));
  setViewportWidth(360);
  renderApp('/tasks', { userId: '1001', role: 'STUDENT' });
  const nav = await screen.findByRole('navigation', { name: 'Нижнее меню' });
  const active = within(nav).getByRole('link', { name: 'Задачи' });
  expect(active).toHaveAttribute('aria-current', 'page');
  expect(active).toHaveClass('cr-bottom-link');
  expect(active.querySelector('[data-position]')).toBeNull();
  for (const link of within(nav).getAllByRole('link')) expect(link.querySelector('svg.cr-nav-icon[aria-hidden="true"]')).not.toBeNull();
  expect(styles).toMatch(/\.cr-bottom-link\s*\{[^}]*justify-content:\s*center[^}]*text-align:\s*center/);
  expect(styles).toMatch(/\.cr-bottom-link\[data-active\]\s*\{[^}]*background(?:-color)?:\s*var\(--cr-primary-soft\)[^}]*color:\s*var\(--cr-primary\)/);
});

it('R31: all themes share the 1920px root scale and scalable size tokens', () => {
  expect(styles).toMatch(/@media\s*\(min-width:\s*1920px\)\s*\{\s*html\s*\{\s*font-size:\s*112\.5%/);
  for (const [resolver, body, control] of [[cssVariablesResolver, '0.9375rem', '2.75rem'], [contrastVariablesResolver, '1.0625rem', '3.25rem']] as const) {
    const values = resolver(mergeMantineTheme(DEFAULT_THEME, theme)).variables;
    expect(values['--cr-body-size']).toBe(body);
    expect(values['--cr-touch-target']).toBe(control);
    for (const [key, value] of Object.entries(values)) if (key !== '--cr-motion-duration') expect(value).not.toMatch(/\dpx/);
  }
});

it('R41: pending session displays the centered full-cycle 96px loader', async () => {
  createAuthScenario();
  server.use(http.get('*/api/v1/auth/me', () => new Promise<ReturnType<typeof HttpResponse.json>>(() => {})));
  mountAuth('/login');
  const loading = await screen.findByRole('status', { name: 'Загрузка' });
  expect(loading).toHaveClass('cr-session-loading');
  expect(loading.querySelector('svg animate')).not.toBeNull();
  expect(loading.querySelector('.cr-loader')).toHaveStyle({ width: '6rem', height: '6rem' });
});

it.each([false, true])('R41: AsyncContent uses accessible 48px wait animation, reduced motion=%s', (reduce) => {
  const original = window.matchMedia;
  vi.spyOn(window, 'matchMedia').mockImplementation((query) => ({ ...original(query), matches: query.includes('prefers-reduced-motion') && reduce }));
  render(<CampusThemeProvider><AsyncContent state={{ status: 'loading' }}>Готово</AsyncContent></CampusThemeProvider>);
  const loading = screen.getByRole('status', { name: 'Загрузка' });
  expect(loading.querySelector('.cr-loader')).toHaveStyle({ width: '3rem', height: '3rem' });
  expect(loading.querySelector('svg')).toHaveAttribute('fill', 'currentColor');
  expect(Boolean(loading.querySelector('animate, animateTransform'))).toBe(!reduce);
  expect(loading.querySelector('.mantine-Skeleton-root')).toBeNull();
  expect(screen.queryByText('Готово')).not.toBeInTheDocument();
});

it('R41: reduced-motion session loader is static', () => {
  const original = window.matchMedia;
  vi.spyOn(window, 'matchMedia').mockImplementation((query) => ({ ...original(query), matches: query.includes('prefers-reduced-motion') }));
  render(<CampusThemeProvider><RouteLoading /></CampusThemeProvider>);
  expect(screen.getByRole('status', { name: 'Загрузка' }).querySelector('animate, animateTransform')).toBeNull();
  expect(screen.getByRole('status', { name: 'Загрузка' }).querySelector('svg path')).not.toBeNull();
});


it('Radio labels and bodies use the 44px touch target', () => {
  expect(theme.components?.Radio?.styles).toMatchObject({
    body: { minHeight: 'var(--cr-touch-target)', alignItems: 'center' },
    label: { minHeight: 'var(--cr-touch-target)', display: 'flex', alignItems: 'center' },
  });
  expect(styles).toMatch(/\.mantine-Radio-label[^{}]*\{[^}]*position:\s*relative/);
  expect(styles).toMatch(/\.mantine-Radio-label::before[^{}]*\{[^}]*inset:\s*0[^}]*inset-inline-start:/);
});
