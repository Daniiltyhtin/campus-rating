import { cleanup, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../mocks/server';
import { authUsers, choose, createAuthScenario, fillRegistration, logoutAction, mountAuth, openUserMenu, problem } from '../../test/authAcceptance';

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }));
afterEach(() => {
  cleanup();
  server.resetHandlers();
  document.cookie = 'XSRF-TOKEN=; Max-Age=0; Path=/';
  localStorage.clear();
});
afterAll(() => server.close());

describe('U5 frontend: сессия и вход — R3, KTD8', () => {
  it('восстанавливает серверную сессию по me без локальной копии пользователя', async () => {
    createAuthScenario(authUsers.student);
    mountAuth('/tasks');
    expect(await screen.findByRole('navigation', { name: 'Боковое меню' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Профиль' })).toHaveAttribute('href', '/users/1001');
    expect(localStorage.getItem('session')).toBeNull();
  });

  it('не выдаёт сбой me за гостя и позволяет повторить загрузку', async () => {
    createAuthScenario(authUsers.student);
    let failed = true;
    server.use(http.get('*/api/v1/auth/me', () => failed ? problem('INTERNAL_ERROR', 500) : HttpResponse.json(authUsers.student)));
    const { user } = mountAuth('/tasks');
    const retry = await screen.findByRole('button', { name: /повторить/i });
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Войти' })).not.toBeInTheDocument();
    failed = false;
    await user.click(retry);
    expect(await screen.findByRole('navigation', { name: 'Боковое меню' })).toBeInTheDocument();
  });

  it('не раскрывает защищённый контент до завершения me и направляет гостя на вход', async () => {
    createAuthScenario();
    let finish: (() => void) | undefined;
    const pending = new Promise<void>((resolve) => { finish = resolve; });
    server.use(http.get('*/api/v1/auth/me', async () => { await pending; return problem('UNAUTHENTICATED', 401); }));
    const { router } = mountAuth('/tasks');
    expect(screen.queryByText('Раздел появится')).not.toBeInTheDocument();
    expect(await screen.findByRole('status')).toBeInTheDocument();
    finish?.();
    expect(await screen.findByRole('button', { name: 'Войти' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/login');
  });

  it('входит с коротким старым паролем, передаёт CSRF и обновляет токен после успеха', async () => {
    const state = createAuthScenario();
    const { user, router } = mountAuth();
    await user.type(await screen.findByLabelText('Email'), 'smirnova@student.campus.test');
    await user.type(screen.getByLabelText('Пароль', { exact: true }), 'old');
    await user.click(screen.getByRole('button', { name: 'Войти' }));
    expect(await screen.findByRole('navigation', { name: 'Боковое меню' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/');
    expect(state.events.filter((event) => event.path.endsWith('/login'))).toEqual([
      expect.objectContaining({ csrf: 'acceptance-csrf-1', body: { email: 'smirnova@student.campus.test', password: 'old' } }),
    ]);
    expect(state.csrfCount).toBeGreaterThanOrEqual(2);
  });

  it('показывает одинаковую понятную ошибку INVALID_CREDENTIALS и сохраняет email', async () => {
    createAuthScenario();
    let posts = 0;
    server.use(http.post('*/api/v1/auth/login', () => { posts++; return problem('INVALID_CREDENTIALS', 401); }));
    const { user } = mountAuth();
    await user.type(await screen.findByLabelText('Email'), 'unknown@campus.test');
    await user.type(screen.getByLabelText('Пароль', { exact: true }), 'some-password');
    await user.click(screen.getByRole('button', { name: 'Войти' }));
    expect(await screen.findByText(/неверный email или пароль/i)).toBeInTheDocument();
    expect(screen.getByLabelText('Email')).toHaveValue('unknown@campus.test');
    expect(posts).toBe(1);
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument();
  });

  it('при 429 от nginx хоста показывает «Слишком много попыток»', async () => {
    createAuthScenario();
    server.use(http.post('*/api/v1/auth/login', () => new HttpResponse('<html>429</html>', { status: 429, headers: { 'Content-Type': 'text/html' } })));
    const { user } = mountAuth();
    await user.type(await screen.findByLabelText('Email'), 'unknown@campus.test');
    await user.type(screen.getByLabelText('Пароль', { exact: true }), 'some-password');
    await user.click(screen.getByRole('button', { name: 'Войти' }));
    expect(await screen.findByText('Слишком много попыток. Подождите минуту и попробуйте снова.')).toBeInTheDocument();
  });

  it('блокирует повторную отправку пока login выполняется', async () => {
    createAuthScenario();
    let posts = 0;
    let finish: (() => void) | undefined;
    const pending = new Promise<void>((resolve) => { finish = resolve; });
    server.use(http.post('*/api/v1/auth/login', async () => { posts++; await pending; return problem('INVALID_CREDENTIALS', 401); }));
    const { user } = mountAuth();
    await user.type(await screen.findByLabelText('Email'), 'unknown@campus.test');
    await user.type(screen.getByLabelText('Пароль', { exact: true }), 'some-password');
    const button = screen.getByRole('button', { name: 'Войти' });
    await user.click(button);
    await waitFor(() => expect(posts).toBe(1));
    expect(button).toBeDisabled();
    await user.click(button);
    expect(posts).toBe(1);
    finish?.();
    expect(await screen.findByText(/неверный email или пароль/i)).toBeInTheDocument();
  });

  it('при сбое CSRF после успешного login повторяет только обновление токена, не login', async () => {
    const state = createAuthScenario();
    let gets = 0;
    let failRefresh = true;
    server.use(http.get('*/api/v1/auth/csrf', () => {
      gets++;
      if (gets > 1 && failRefresh) return problem('INTERNAL_ERROR', 500);
      state.token = `refresh-${gets}`;
      document.cookie = `XSRF-TOKEN=${state.token}; Path=/`;
      return new HttpResponse(null, { status: 204 });
    }));
    const { user } = mountAuth();
    await user.type(await screen.findByLabelText('Email'), 'smirnova@student.campus.test');
    await user.type(screen.getByLabelText('Пароль'), 'some-password');
    await user.click(screen.getByRole('button', { name: 'Войти' }));
    const retry = await screen.findByRole('button', { name: /повторить/i });
    expect(state.events.filter((event) => event.path.endsWith('/login'))).toHaveLength(1);
    failRefresh = false;
    await user.click(retry);
    await waitFor(() => expect(gets).toBeGreaterThanOrEqual(3));
    expect(await screen.findByRole('navigation', { name: 'Боковое меню' })).toBeInTheDocument();
    expect(state.events.filter((event) => event.path.endsWith('/login'))).toHaveLength(1);
  });
});

// Посимвольный ввод нескольких полей в jsdom может занимать больше стандартных пяти секунд.
describe('U5 frontend: регистрация — R1, R2, R47, R48', { timeout: 15_000 }, () => {
  it('регистрирует студента, передаёт ids, не входит автоматически и очищает пароль', async () => {
    const state = createAuthScenario();
    const { user } = mountAuth('/register');
    await fillRegistration(user);
    await user.click(screen.getByRole('button', { name: /зарегистрироваться/i }));
    expect(await screen.findByText(/регистрация завершена/i)).toBeInTheDocument();
    expect(state.events.filter((event) => event.path.includes('/register/'))).toEqual([
      expect.objectContaining({ path: '/api/v1/auth/register/student', csrf: 'acceptance-csrf-1', body: {
        fullName: 'Тестов Алексей Иванович', email: 'acceptance@student.campus.test', password: 'valid-password-1', facultyId: 1, groupId: 11,
      } }),
    ]);
    expect(state.events.some((event) => event.path.endsWith('/login'))).toBe(false);
    expect(screen.queryByDisplayValue('valid-password-1')).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: /войти/i })).toHaveAttribute('href', '/login');
  });

  it('при смене факультета сбрасывает группу и не отправляет старый выбор', async () => {
    const state = createAuthScenario();
    const { user } = mountAuth('/register');
    await fillRegistration(user);
    await choose(user, 'Факультет', 'Экономический факультет');
    await waitFor(() => expect(screen.getByRole('combobox', { name: 'Группа' })).not.toHaveValue('11'));
    expect(await screen.findByText(/групп.*нет|нет.*групп|группы.*отсутствуют/i)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /зарегистрироваться/i }));
    expect(state.events.filter((event) => event.path.includes('/register/'))).toHaveLength(0);
  });

  it('регистрирует преподавателя с departmentId без student-полей', async () => {
    const state = createAuthScenario();
    const { user } = mountAuth('/register');
    await user.click(await screen.findByText('Преподаватель', { exact: true }));
    await user.type(screen.getByLabelText('ФИО'), 'Тестов Алексей Иванович');
    await user.type(screen.getByLabelText('Email'), 'teacher@campus.test');
    await user.type(screen.getByLabelText('Пароль', { exact: true }), 'teacher-password');
    await choose(user, 'Факультет', 'Факультет информационных технологий');
    await choose(user, 'Кафедра', 'Кафедра программной инженерии');
    await user.click(screen.getByRole('button', { name: /зарегистрироваться/i }));
    expect(await screen.findByText(/регистрация завершена/i)).toBeInTheDocument();
    expect(state.events.find((event) => event.path.endsWith('/register/teacher'))?.body).toEqual({
      fullName: 'Тестов Алексей Иванович', email: 'teacher@campus.test', password: 'teacher-password', departmentId: 101,
    });
  });

  it.each(['short-7', 'я'.repeat(37)])('не отправляет недопустимый новый пароль: %s', async (password) => {
    const state = createAuthScenario();
    const { user } = mountAuth('/register');
    await fillRegistration(user, password);
    await user.click(screen.getByRole('button', { name: /зарегистрироваться/i }));
    expect(screen.getByLabelText('Пароль', { exact: true })).toHaveAttribute('aria-invalid', 'true');
    expect(state.events.filter((event) => event.path.includes('/register/'))).toHaveLength(0);
  });

  it('принимает ровно 72 UTF-8 байта, не обрезая пароль', async () => {
    const state = createAuthScenario();
    const { user } = mountAuth('/register');
    await fillRegistration(user, 'я'.repeat(36));
    await user.click(screen.getByRole('button', { name: /зарегистрироваться/i }));
    expect(await screen.findByText(/регистрация завершена/i)).toBeInTheDocument();
    expect(state.events.find((event) => event.path.includes('/register/'))?.body).toEqual(expect.objectContaining({ password: 'я'.repeat(36) }));
  });

  it.each([
    ['EMAIL_TAKEN', /email.*уже|уже.*зарегистрирован|email.*занят/i],
    ['EMAIL_RESERVED_FOR_TEACHER', /email.*закрепл|зарегистрируйтесь.*преподавател|email.*преподавател/i],
  ])('объясняет конфликт %s', async (code, message) => {
    createAuthScenario();
    server.use(http.post('*/api/v1/auth/register/student', () => problem(code, 409)));
    const { user } = mountAuth('/register');
    await fillRegistration(user);
    await user.click(screen.getByRole('button', { name: /зарегистрироваться/i }));
    expect(await screen.findByText(message)).toBeInTheDocument();
    expect(screen.queryByText(/регистрация завершена/i)).not.toBeInTheDocument();
  });

  it('отображает fieldErrors у нужного поля и снимает ошибку при исправлении', async () => {
    createAuthScenario();
    server.use(http.post('*/api/v1/auth/register/student', () => problem('VALIDATION_FAILED', 400, {
      fieldErrors: [{ field: 'groupId', code: 'MISMATCH', message: 'Группа не принадлежит выбранному факультету.' }],
    })));
    const { user } = mountAuth('/register');
    await fillRegistration(user);
    await user.click(screen.getByRole('button', { name: /зарегистрироваться/i }));
    expect(await screen.findByText('Группа не принадлежит выбранному факультету.')).toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: 'Группа' })).toHaveAttribute('aria-invalid', 'true');
    await choose(user, 'Группа', 'ИВТ-22');
    expect(screen.queryByText('Группа не принадлежит выбранному факультету.')).not.toBeInTheDocument();
  });

  it('показывает обе честные пометки demo/OFF', async () => {
    createAuthScenario();
    mountAuth('/register');
    expect(await screen.findByText(/демо-стенд: данные могут быть сброшены/i)).toBeInTheDocument();
    expect(screen.getByText(/подтверждение email на этом стенде выключено/i)).toBeInTheDocument();
  });

  it('для prod/REQUIRED не показывает пометки demo/OFF и следует CONFIRM_EMAIL без login', async () => {
    const state = createAuthScenario();
    state.config = { demo: false, emailVerification: 'REQUIRED' };
    state.nextStep = 'CONFIRM_EMAIL';
    const { user } = mountAuth('/register');
    await fillRegistration(user);
    expect(screen.queryByText(/демо-стенд: данные могут быть сброшены/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/подтверждение email на этом стенде выключено/i)).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /зарегистрироваться/i }));
    expect(await screen.findByText(/письм/i)).toBeInTheDocument();
    expect(state.events.some((event) => event.path.endsWith('/login'))).toBe(false);
  });

  it('ошибка config не превращается в OFF: регистрация не отправляется, есть повтор', async () => {
    const state = createAuthScenario();
    server.use(http.get('*/api/v1/auth/config', () => problem('INTERNAL_ERROR', 500)));
    mountAuth('/register');
    expect(await screen.findByRole('button', { name: /повторить/i })).toBeInTheDocument();
    expect(screen.queryByText(/подтверждение email на этом стенде выключено/i)).not.toBeInTheDocument();
    expect(state.events.filter((event) => event.path.includes('/register/'))).toHaveLength(0);
    const submit = screen.queryByRole('button', { name: /зарегистрироваться/i });
    if (submit) expect(submit).toBeDisabled();
  });

  it('после ошибки справочника даёт повторить, не отправляя регистрацию с пустой группой', async () => {
    const state = createAuthScenario();
    let failed = true;
    server.use(http.get('*/api/v1/directory/faculties/1/groups', () => failed
      ? problem('INTERNAL_ERROR', 500)
      : HttpResponse.json({ items: [authUsers.student.group] })));
    const { user } = mountAuth('/register');
    await choose(user, 'Факультет', 'Факультет информационных технологий');
    const retry = await screen.findByRole('button', { name: /повторить/i });
    expect(state.events.filter((event) => event.path.includes('/register/'))).toHaveLength(0);
    failed = false;
    await user.click(retry);
    await choose(user, 'Группа', 'ИВТ-21');
    const group = screen.getByRole('combobox', { name: 'Группа' });
    expect(group).toHaveValue(group instanceof HTMLSelectElement ? '11' : 'ИВТ-21');
  });
});

describe('U5 frontend: временный пароль, выход и допуск — R2, R49, R50', () => {
  it.each(['/tasks', '/login'])('временный пароль на %s открывает только смену и выход', async (path) => {
    createAuthScenario(authUsers.admin);
    const { router } = mountAuth(path);
    expect(await screen.findByLabelText('Текущий пароль')).toBeInTheDocument();
    expect(screen.getByLabelText('Новый пароль')).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/password/change');
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Выйти' })).toBeInTheDocument();
  });

  it('после смены получает новый CSRF и открывает разделы только после серверного me=false', async () => {
    const state = createAuthScenario(authUsers.admin);
    const { user, router } = mountAuth('/password/change');
    await user.type(await screen.findByLabelText('Текущий пароль'), 'old');
    await user.type(screen.getByLabelText('Новый пароль'), 'new-password-1');
    await user.click(screen.getByRole('button', { name: 'Сменить пароль' }));
    expect(await screen.findByRole('navigation', { name: 'Боковое меню' })).toBeInTheDocument();
    expect(router.state.location.pathname).not.toBe('/password/change');
    expect(state.events.find((event) => event.path.endsWith('/password'))).toEqual(expect.objectContaining({
      csrf: 'acceptance-csrf-1', body: { currentPassword: 'old', newPassword: 'new-password-1' },
    }));
    expect(state.csrfCount).toBeGreaterThanOrEqual(2);
    expect(screen.queryByDisplayValue('new-password-1')).not.toBeInTheDocument();
  });

  it.each([
    ['PASSWORD_REUSED', 'Новый пароль', { detail: 'Новый пароль должен отличаться от текущего.' }],
    ['VALIDATION_FAILED', 'Текущий пароль', { fieldErrors: [{ field: 'currentPassword', code: 'MISMATCH', message: 'Текущий пароль указан неверно.' }] }],
  ])('ошибка %s относится к полю %s', async (code, label, extra) => {
    createAuthScenario(authUsers.admin);
    server.use(http.post('*/api/v1/auth/password', () => problem(code, 400, extra)));
    const { user } = mountAuth('/password/change');
    await user.type(await screen.findByLabelText('Текущий пароль'), 'old');
    await user.type(screen.getByLabelText('Новый пароль'), 'new-password-1');
    await user.click(screen.getByRole('button', { name: 'Сменить пароль' }));
    await waitFor(() => expect(screen.getByLabelText(label)).toHaveAttribute('aria-invalid', 'true'));
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument();
  });

  it('logout очищает персональный cache, ведёт на login и обновляет CSRF', async () => {
    const state = createAuthScenario(authUsers.student);
    const { user, queryClient, router } = mountAuth('/tasks');
    await screen.findByRole('navigation', { name: 'Боковое меню' });
    queryClient.setQueryData(['acceptance-private-profile'], { secret: 'synthetic-personal-data' });
    await openUserMenu(user, authUsers.student.fullName);
    await user.click(logoutAction());
    expect(await screen.findByRole('button', { name: 'Войти' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/login');
    expect(queryClient.getQueryData(['acceptance-private-profile'])).toBeUndefined();
    await waitFor(() => expect(state.csrfCount).toBeGreaterThanOrEqual(2));
  });

  it('не изображает успешный выход при ошибке logout', async () => {
    createAuthScenario(authUsers.student);
    server.use(http.post('*/api/v1/auth/logout', () => problem('INTERNAL_ERROR', 500)));
    const { user, queryClient, router } = mountAuth('/tasks');
    await screen.findByRole('navigation', { name: 'Боковое меню' });
    queryClient.setQueryData(['acceptance-private-profile'], { id: 1001 });
    await openUserMenu(user, authUsers.student.fullName);
    await user.click(logoutAction());
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/tasks');
    expect(queryClient.getQueryData(['acceptance-private-profile'])).toEqual({ id: 1001 });
  });

  it('преподаватель без допуска видит объяснение ожидания на главной', async () => {
    createAuthScenario(authUsers.unapprovedTeacher);
    mountAuth('/');
    expect(await screen.findByText('Заявка ждёт подтверждения администратора')).toBeInTheDocument();
  });

  it('PENDING-членство объясняется пользователю, а не выглядит пустым списком', async () => {
    createAuthScenario(authUsers.pendingStudent);
    mountAuth('/');
    expect(await screen.findByText(/членство.*ждёт подтверждения/i)).toBeInTheDocument();
  });

  it('истёкшая сессия при защищённой мутации очищает персональный cache и ведёт на login', async () => {
    createAuthScenario(authUsers.admin);
    server.use(http.post('*/api/v1/auth/password', () => problem('UNAUTHENTICATED', 401)));
    const { user, queryClient, router } = mountAuth('/password/change');
    await user.type(await screen.findByLabelText('Текущий пароль'), 'old');
    await user.type(screen.getByLabelText('Новый пароль'), 'new-password-1');
    queryClient.setQueryData(['acceptance-private-profile'], { id: 3001 });
    await user.click(screen.getByRole('button', { name: 'Сменить пароль' }));
    expect(await screen.findByRole('button', { name: 'Войти' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/login');
    expect(queryClient.getQueryData(['acceptance-private-profile'])).toBeUndefined();
  });
});
