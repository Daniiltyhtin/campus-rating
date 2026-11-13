import { cleanup, configure, getConfig, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import type { components } from '../../api/schema';
import { server } from '../../mocks/server';
import { authUsers, choose, createAuthScenario, mountAuth, problem } from '../../test/authAcceptance';
import { createDirectoryScenario } from '../../test/directoryAcceptance';

type Schemas = components['schemas'];
type Pending = Schemas['PendingMembership'];
type Call = { method: string; path: string; body: unknown };

const originalTimeout = getConfig().asyncUtilTimeout;
const mounted: ReturnType<typeof mountAuth>[] = [];
beforeAll(() => { configure({ asyncUtilTimeout: 3_000 }); server.listen(); });
afterEach(() => {
  cleanup();
  for (const view of mounted.splice(0)) { view.router.dispose(); view.queryClient.clear(); }
  server.resetHandlers(); localStorage.clear();
  document.cookie = 'XSRF-TOKEN=; Max-Age=0; Path=/';
  document.cookie = 'campus-mock-user=; Max-Age=0; Path=/';
});
afterAll(() => { server.close(); configure({ asyncUtilTimeout: originalTimeout }); });

const faculty = { id: 1, code: 'ФИТ', name: 'Факультет информационных технологий' };
const ivt21 = { id: 11, name: 'ИВТ-21', faculty };
const ivt22 = { id: 12, name: 'ИВТ-22', faculty };
const head: Schemas['CurrentStudent'] = { ...authUsers.student, groupHead: true };
const rejected: Schemas['CurrentStudent'] = { ...authUsers.pendingStudent, membershipState: 'REJECTED', groupHead: false };

function request(id: number, fullName: string, email: string, group = ivt21, requestedAt = '2026-11-12T09:00:00Z'): Pending {
  return { studentId: id, fullName, email, group, requestedAt };
}

function mount(path: string) {
  const view = mountAuth(path);
  mounted.push(view);
  return view;
}

function memberships(initial: Pending[]) {
  const state = { items: [...initial], calls: [] as Call[], reads: 0, fail: false };
  server.use(
    http.get('*/api/v1/memberships/pending', () => {
      state.reads++;
      if (state.fail) return problem('INTERNAL', 500);
      return HttpResponse.json({ items: state.items });
    }),
    http.post('*/api/v1/memberships/:id/:decision', ({ params, request: call }) => {
      state.calls.push({ method: call.method, path: new URL(call.url).pathname, body: null });
      state.items = state.items.filter((item) => item.studentId !== Number(params.id));
      return new HttpResponse(null, { status: 204 });
    }),
  );
  return state;
}

describe('U15 заявки в группу — преподаватель', { timeout: 15_000 }, () => {
  it('видит «Заявки: N» в «Группах», подтверждает строку и отклоняет через диалог', async () => {
    createAuthScenario(authUsers.teacher);
    document.cookie = `campus-mock-user=${encodeURIComponent(JSON.stringify(authUsers.teacher))}; Path=/`;
    const state = memberships([
      request(1004, 'Орлов Никита Сергеевич', 'orlov@student.campus.test'),
      request(1005, 'Кузнецова Мария Ивановна', 'kuznetsova@student.campus.test', ivt22, '2026-11-12T10:30:00Z'),
    ]);
    const view = mount('/groups');
    await view.user.click(await screen.findByRole('link', { name: /Заявки: 2/ }));
    await waitFor(() => expect(view.router.state.location.pathname).toBe('/memberships'));
    expect(await screen.findByText('Орлов Никита Сергеевич')).toBeInTheDocument();
    expect(screen.getByText('orlov@student.campus.test')).toBeInTheDocument();
    expect(screen.getByText('kuznetsova@student.campus.test')).toBeInTheDocument();
    expect(screen.getAllByText(/ИВТ-21/).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/ИВТ-22/).length).toBeGreaterThan(0);
    expect(screen.getByText(/12:00/)).toBeInTheDocument();
    expect(screen.getByText(/13:30/)).toBeInTheDocument();

    await view.user.click(screen.getAllByRole('button', { name: /^Подтвердить/ })[0]!);
    await waitFor(() => expect(screen.queryByText('Орлов Никита Сергеевич')).not.toBeInTheDocument());
    expect(state.calls).toEqual([{ method: 'POST', path: '/api/v1/memberships/1004/approval', body: null }]);

    await view.user.click(screen.getByRole('button', { name: /^Отклонить/ }));
    const dialog = within(await screen.findByRole('dialog'));
    expect(state.calls).toHaveLength(1);
    expect(dialog.getByText(/Кузнецова Мария Ивановна/)).toBeInTheDocument();
    await view.user.click(dialog.getByRole('button', { name: /^Отклонить/ }));
    await waitFor(() => expect(screen.queryByText('Кузнецова Мария Ивановна')).not.toBeInTheDocument());
    expect(state.calls.at(-1)).toEqual({ method: 'POST', path: '/api/v1/memberships/1005/rejection', body: null });
    expect(await screen.findByText('Новых заявок нет')).toBeInTheDocument();
  });

  it('отмена диалога отклонения ничего не отправляет', async () => {
    createAuthScenario(authUsers.teacher);
    const state = memberships([request(1004, 'Орлов Никита Сергеевич', 'orlov@student.campus.test')]);
    const view = mount('/memberships');
    await view.user.click(await screen.findByRole('button', { name: /^Отклонить/ }));
    const dialog = within(await screen.findByRole('dialog'));
    await view.user.click(dialog.getByRole('button', { name: /Отмена/ }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(state.calls).toHaveLength(0);
    expect(screen.getByText('Орлов Никита Сергеевич')).toBeInTheDocument();
  });

  it('пустой список — «Новых заявок нет»', async () => {
    createAuthScenario(authUsers.teacher);
    memberships([]);
    mount('/memberships');
    expect(await screen.findByText('Новых заявок нет')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Подтвердить/ })).not.toBeInTheDocument();
  });

  it('ошибка загрузки — сообщение и «Повторить»', async () => {
    createAuthScenario(authUsers.teacher);
    const state = memberships([request(1004, 'Орлов Никита Сергеевич', 'orlov@student.campus.test')]);
    state.fail = true;
    const view = mount('/memberships');
    const retry = await screen.findByRole('button', { name: /Повторить/ });
    expect(screen.queryByText('Новых заявок нет')).not.toBeInTheDocument();
    state.fail = false;
    await view.user.click(retry);
    expect(await screen.findByText('Орлов Никита Сергеевич')).toBeInTheDocument();
  });
});

describe('U15 заявки в группу — староста и студент', { timeout: 15_000 }, () => {
  it('староста видит карточку «Заявки в группу» с числом и пояснением на «Главной» и по ней — заявки своей группы', async () => {
    createAuthScenario(head);
    memberships([request(1004, 'Орлов Никита Сергеевич', 'orlov@student.campus.test')]);
    const view = mount('/');
    const notice = await screen.findByRole('region', { name: /^Заявки в группу/ });
    expect(notice).toHaveTextContent('1 студент ждёт подтверждения членства');
    await view.user.click(within(notice).getByRole('link', { name: 'Проверить заявки' }));
    await waitFor(() => expect(view.router.state.location.pathname).toBe('/memberships'));
    expect(await screen.findByText('Орлов Никита Сергеевич')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^Подтвердить/ })).toBeInTheDocument();
  });

  it('при N = 0 плашки у старосты нет', async () => {
    createAuthScenario(head);
    const state = memberships([]);
    mount('/');
    expect(await screen.findByText('Событий пока нет')).toBeInTheDocument();
    await waitFor(() => expect(state.reads).toBeGreaterThan(0));
    expect(screen.queryByText(/Заявки в группу/)).not.toBeInTheDocument();
  });

  it('обычный студент плашки не видит, а прямой переход на /memberships — «Нет доступа»', async () => {
    createAuthScenario({ ...authUsers.student, groupHead: false });
    memberships([request(1004, 'Орлов Никита Сергеевич', 'orlov@student.campus.test')]);
    mount('/');
    expect(await screen.findByText('Событий пока нет')).toBeInTheDocument();
    expect(screen.queryByText(/Заявки в группу/)).not.toBeInTheDocument();
    cleanup();
    mount('/memberships');
    expect(await screen.findByRole('heading', { name: 'Нет доступа' })).toBeInTheDocument();
    expect(screen.queryByText('Орлов Никита Сергеевич')).not.toBeInTheDocument();
  });

  it('студент с REJECTED видит плашку отклонения, выбирает группу и после отправки — ожидание PENDING', async () => {
    const auth = createAuthScenario(rejected);
    const calls: Call[] = [];
    server.use(http.put('*/api/v1/me/membership', async ({ request: call }) => {
      const body = await call.json();
      calls.push({ method: call.method, path: new URL(call.url).pathname, body });
      const next: Schemas['CurrentStudent'] = { ...rejected, group: ivt22, membershipState: 'PENDING' };
      auth.currentUser = next;
      return HttpResponse.json(next);
    }));
    const view = mount('/');
    expect(await screen.findByText(/Заявка в ИВТ-21 отклонена/)).toBeInTheDocument();
    await choose(view.user, 'Факультет', 'Факультет информационных технологий');
    await choose(view.user, 'Группа', 'ИВТ-22');
    await view.user.click(screen.getByRole('button', { name: /заявк|Отправить/i }));
    await waitFor(() => expect(calls).toEqual([{ method: 'PUT', path: '/api/v1/me/membership', body: { facultyId: 1, groupId: 12 } }]));
    expect(await screen.findByText('Членство в ИВТ-22 ждёт подтверждения старосты или преподавателя')).toBeInTheDocument();
    expect(screen.queryByText(/отклонена/)).not.toBeInTheDocument();
  });

  it('ошибка повторной заявки по полю groupId показывается у поля, плашка остаётся', async () => {
    createAuthScenario(rejected);
    server.use(http.put('*/api/v1/me/membership', () => problem('VALIDATION_FAILED', 400, {
      fieldErrors: [{ field: 'groupId', code: 'MISMATCH', message: 'Группа не принадлежит факультету' }],
    })));
    const view = mount('/');
    await screen.findByText(/Заявка в ИВТ-21 отклонена/);
    await choose(view.user, 'Факультет', 'Факультет информационных технологий');
    await choose(view.user, 'Группа', 'ИВТ-22');
    await view.user.click(screen.getByRole('button', { name: /заявк|Отправить/i }));
    expect(await screen.findByText('Группа не принадлежит факультету')).toBeInTheDocument();
    expect(screen.getByText(/Заявка в ИВТ-21 отклонена/)).toBeInTheDocument();
  });
});

describe('U15 старосты в справочнике — администратор', { timeout: 15_000 }, () => {
  function heads(initial: Schemas['GroupHead'][]) {
    const state = { items: [...initial], calls: [] as Call[] };
    server.use(
      http.get('*/api/v1/admin/group-heads', () => HttpResponse.json({ items: state.items })),
      http.put('*/api/v1/admin/group-heads/:groupId', async ({ params, request: call }) => {
        const body = await call.json() as { studentId: number };
        state.calls.push({ method: 'PUT', path: new URL(call.url).pathname, body });
        const fullName = body.studentId === 1002 ? 'Иванов Иван Иванович' : 'Смирнова Анна Олеговна';
        const value = { groupId: Number(params.groupId), student: { id: body.studentId, fullName } };
        state.items = [...state.items.filter((item) => item.groupId !== value.groupId), value];
        return HttpResponse.json(value);
      }),
      http.delete('*/api/v1/admin/group-heads/:groupId', ({ params, request: call }) => {
        state.calls.push({ method: 'DELETE', path: new URL(call.url).pathname, body: null });
        state.items = state.items.filter((item) => item.groupId !== Number(params.groupId));
        return new HttpResponse(null, { status: 204 });
      }),
      http.get('*/api/v1/groups/:groupId/students', () => HttpResponse.json({ items: [
        { id: 1002, fullName: 'Иванов Иван Иванович', email: 'ivanov@student.campus.test', membershipState: 'ACTIVE', passwordSetAt: null },
        { id: 1004, fullName: 'Орлов Никита Сергеевич', email: 'orlov@student.campus.test', membershipState: 'PENDING', passwordSetAt: null },
      ] })),
    );
    return state;
  }

  it('назначает старосту из ACTIVE-студентов группы, видит его в колонке и снимает', async () => {
    createDirectoryScenario();
    const state = heads([]);
    const view = mount('/admin/directory');
    await view.user.click(await screen.findByRole('tab', { name: 'Учебные группы' }));
    const table = await screen.findByRole('table', { name: 'Учебные группы' });
    expect(within(table).getByRole('columnheader', { name: 'Староста' })).toBeInTheDocument();
    await view.user.click(within(table).getByRole('button', { name: /Назначить старосту/ }));
    const dialog = within(await screen.findByRole('dialog'));
    const select = await dialog.findByRole('combobox', { name: /Студент|Староста/ });
    await view.user.click(select);
    expect(screen.queryByRole('option', { name: /Орлов/ })).not.toBeInTheDocument();
    if (select instanceof HTMLSelectElement) {
      expect(Array.from(select.options).some((option) => option.textContent?.includes('Орлов'))).toBe(false);
      await view.user.selectOptions(select, '1002');
    } else {
      await view.user.click(await screen.findByRole('option', { name: /Иванов Иван Иванович/ }));
    }
    await view.user.click(dialog.getByRole('button', { name: /^Назначить/ }));
    await waitFor(() => expect(state.calls).toEqual([{ method: 'PUT', path: '/api/v1/admin/group-heads/11', body: { studentId: 1002 } }]));
    expect(await within(table).findByText('Иванов Иван Иванович')).toBeInTheDocument();

    await view.user.click(within(table).getByRole('button', { name: /Снять старосту/ }));
    const confirm = screen.queryByRole('dialog');
    if (confirm) await view.user.click(within(confirm).getByRole('button', { name: /Снять/ }));
    await waitFor(() => expect(state.calls.at(-1)).toEqual({ method: 'DELETE', path: '/api/v1/admin/group-heads/11', body: null }));
    await waitFor(() => expect(within(table).queryByText('Иванов Иван Иванович')).not.toBeInTheDocument());
  });

  it('показывает назначенного старосту в колонке «Староста»', async () => {
    createDirectoryScenario();
    heads([{ groupId: 11, student: { id: 1001, fullName: 'Смирнова Анна Олеговна' } }]);
    const view = mount('/admin/directory');
    await view.user.click(await screen.findByRole('tab', { name: 'Учебные группы' }));
    const table = await screen.findByRole('table', { name: 'Учебные группы' });
    expect(await within(table).findByText('Смирнова Анна Олеговна')).toBeInTheDocument();
    expect(within(table).getByRole('button', { name: /Снять старосту/ })).toBeInTheDocument();
  });
});
