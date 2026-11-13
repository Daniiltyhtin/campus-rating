import { randomUUID } from 'node:crypto';
import { test as base, expect, request, type APIRequestContext, type Page, type Locator } from '@playwright/test';
import type { components } from '../../../frontend/src/api/schema.js';
import { mailToken } from '../../fixtures/greenmail.js';

export type Schemas = components['schemas'];
export type User = { id: number; email: string; password: string; fullName: string; lastName: string; firstName: string; middleName: string; api: Api };
export class Api {
  constructor(readonly context: APIRequestContext) {}
  async get<T>(path: string): Promise<T> {
    const response = await this.context.get(path);
    expect(response.status(), `${path}: ${await response.text()}`).toBe(200);
    return await response.json() as T;
  }
  async csrf() {
    expect((await this.context.get('/api/v1/auth/csrf')).status()).toBe(204);
    const cookie = (await this.context.storageState()).cookies.find((item) => item.name === 'XSRF-TOKEN');
    expect(cookie).toBeDefined();
    return { 'X-XSRF-TOKEN': decodeURIComponent(cookie!.value) };
  }
  async send(path: string, data?: unknown, method = 'POST', status = 200) {
    const response = await this.context.fetch(path, { method, data, headers: await this.csrf() });
    expect(response.status(), `${method} ${path}: ${await response.text()}`).toBe(status);
    return response;
  }
  async post<T>(path: string, data: unknown, status = 200): Promise<T> {
    return await (await this.send(path, data, 'POST', status)).json() as T;
  }
  async signIn(email: string, password: string) {
    await this.send('/api/v1/auth/login', { email, password });
    await this.csrf();
  }
  async now() {
    const response = await this.context.get('/api/v1/auth/config');
    expect(response.status()).toBe(200);
    const time = Date.parse(response.headers().date);
    expect(Number.isFinite(time)).toBe(true);
    return time;
  }
  async future(seconds: number) { return new Date(await this.now() + seconds * 1000).toISOString(); }
  async until(instant: string) {
    await expect.poll(() => this.now(), { timeout: 25_000, intervals: [200, 500] }).toBeGreaterThanOrEqual(Date.parse(instant));
  }
  async submit(assignmentId: number, link = 'https://example.org/report') {
    const response = await this.context.post(`/api/v1/assignments/${assignmentId}/submission`, {
      headers: await this.csrf(), multipart: { link },
    });
    expect(response.status(), await response.text()).toBe(200);
    return await response.json() as Schemas['Assignment'];
  }
  async withdraw(id: number) { await this.send(`/api/v1/assignments/${id}/submission/withdrawal`); }
  async returnWork(id: number, comment: string) { await this.send(`/api/v1/assignments/${id}/return`, { comment }); }
  async confirm(id: number, items: Schemas['ParticipationItem'][]) {
    return this.post<Schemas['ParticipationResult']>(`/api/v1/activities/${id}/participation`, { items });
  }
}

class Scenario {
  readonly prefix = `e2e-${Date.now().toString(36)}-${randomUUID().slice(0, 8)}`;
  private contexts: APIRequestContext[] = [];
  faculty!: Schemas['AdminFaculty'];
  group!: Schemas['AdminStudyGroup'];
  department!: Schemas['AdminDepartment'];
  admin!: Api;
  private approver?: User;
  constructor(private baseURL: string) {}
  async api() {
    const context = await request.newContext({ baseURL: this.baseURL });
    this.contexts.push(context);
    return new Api(context);
  }
  async init() {
    this.admin = await this.api();
    await this.admin.signIn('admin@campus.test', 'demo-admin-1');
    this.faculty = await this.admin.post('/api/v1/admin/directory/faculties', {
      code: this.prefix, name: `Факультет ${this.prefix}`,
    }, 201);
    this.group = await this.admin.post('/api/v1/admin/directory/groups', {
      code: this.prefix, name: `Группа ${this.prefix}`, facultyId: this.faculty.id, admissionYear: 2026,
    }, 201);
    this.department = await this.admin.post('/api/v1/admin/directory/departments', {
      code: this.prefix, name: `Кафедра ${this.prefix}`, facultyId: this.faculty.id,
    }, 201);
  }
  identity(label = 'Студент') {
    const suffix = randomUUID().slice(0, 8);
    return { email: `${this.prefix}-${suffix}@campus.test`, password: 'e2e-password-1', lastName: label, firstName: suffix, middleName: '', fullName: `${label} ${suffix}` };
  }
  async registerStudent(group = this.group, active = true) {
    const student = await this.register('student', { facultyId: group.faculty.id, groupId: group.id });
    if (active) await this.approveMembership(student.id);
    return student;
  }
  async approveMembership(studentId: number) {
    this.approver ??= await this.registerTeacher();
    await this.approver.api.send(`/api/v1/memberships/${studentId}/approval`, undefined, 'POST', 204);
  }
  async confirmEmail(email: string) {
    const guest = await this.api();
    if ((await guest.get<Schemas['AuthConfig']>('/api/v1/auth/config')).emailVerification !== 'REQUIRED') return;
    await guest.send('/api/v1/auth/email/confirmation', { token: await mailToken(email, 'email/confirm') }, 'POST', 204);
  }
  async registerTeacher(approved = true) {
    const user = await this.register('teacher', { departmentId: this.department.id });
    if (approved) await this.approveTeacher(user.id);
    return user;
  }
  private async register(role: 'student' | 'teacher', target: object): Promise<User> {
    const api = await this.api();
    const identity = this.identity(role === 'student' ? 'Студент' : 'Преподаватель');
    const { fullName, ...registration } = identity;
    const result = await api.post<Schemas['RegistrationResult']>(`/api/v1/auth/register/${role}`, { ...registration, ...target }, 201);
    await this.confirmEmail(identity.email);
    await api.signIn(identity.email, identity.password);
    return { ...registration, fullName, id: result.userId, api };
  }
  async approveTeacher(id: number) {
    await this.admin.send(`/api/v1/admin/teachers/${id}/approval`, { departmentId: this.department.id });
  }
  async checkpoint(teacher: User, seconds = 120, cost = 12) {
    return teacher.api.post<Schemas['Checkpoint']>('/api/v1/checkpoints', {
      title: `Точка ${randomUUID().slice(0, 8)}`, description: 'Работа для проверки', groupIds: [this.group.id],
      deadline: await teacher.api.future(seconds), cost,
    }, 201);
  }
  async assignment(student: User, checkpoint: Schemas['Checkpoint']) {
    const list = await student.api.get<Schemas['StudentTaskPage']>('/api/v1/me/assignments?view=active');
    const item = list.items.find((task) => task.checkpoint.id === checkpoint.id);
    expect(item, checkpoint.title).toBeDefined();
    return student.api.get<Schemas['Assignment']>(`/api/v1/assignments/${item!.assignmentId}`);
  }
  async activity(teacher: User, type: 'ACADEMIC' | 'SOCIAL' = 'ACADEMIC', seconds = 5, maxPoints = 75) {
    return teacher.api.post<Schemas['Activity']>('/api/v1/activities', {
      title: `Активность ${randomUUID().slice(0, 8)}`, description: 'Событие для проверки', type,
      groupIds: [this.group.id], startsAt: await teacher.api.future(seconds), maxPoints,
    }, 201);
  }
  async dispose() { await Promise.all(this.contexts.map((context) => context.dispose())); }
}

export const test = base.extend<{ scenario: Scenario }>({
  scenario: async ({ baseURL }, use) => {
    const scenario = new Scenario(baseURL!);
    try { await scenario.init(); await use(scenario); } finally { await scenario.dispose(); }
  },
});
export { expect };
export async function login(page: Page, user: Pick<User, 'email' | 'password'>) {
  await page.goto('/login');
  await page.getByLabel('Email', { exact: true }).fill(user.email);
  await page.getByLabel('Пароль', { exact: true }).fill(user.password);
  await page.getByRole('button', { name: 'Войти', exact: true }).click();
  await expect(page).not.toHaveURL(/\/login$/);
}
export async function logout(page: Page) {
  await page.getByRole('button', { name: /Меню пользователя:/ }).click();
  await page.getByRole('menuitem', { name: 'Выйти', exact: true }).click();
  await expect(page).toHaveURL(/\/login$/);
}
export function moscowInput(instant: string) {
  return new Date(Date.parse(instant) + 3 * 3600_000).toISOString().slice(0, 16);
}
export async function evidence(page: Page, name: string) {
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
  await page.screenshot({ path: test.info().outputPath(`${name}.png`), fullPage: true });
}

export async function choose(label: Locator) {
  await label.evaluate((element) => element.scrollIntoView({ block: 'center' }));
  await label.click();
}
