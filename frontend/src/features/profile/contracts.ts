import type { components } from '../../api/schema';

type Schemas = components['schemas'];
export type UserProfile = Schemas['UserProfile'];
export type ProfileTask = Schemas['ProfileTask'];
export type LedgerEntry = Schemas['LedgerEntryView'];
export type Currency = Schemas['Currency'];
function invalid(): never { throw new Error('Некорректный ответ API профиля'); }
function isObject(value: unknown): value is Record<string, unknown> { return value !== null && typeof value === 'object' && !Array.isArray(value); }
function object(value: unknown): Record<string, unknown> { return isObject(value) ? value : invalid(); }
function text(value: unknown): string { return typeof value === 'string' ? value : invalid(); }
function integer(value: unknown, minimum = Number.MIN_SAFE_INTEGER, maximum = Number.MAX_SAFE_INTEGER): number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum && value <= maximum ? value : invalid();
}
function id(value: unknown) { return integer(value, 1); }
function list<T>(value: unknown, parse: (value: unknown) => T): T[] { return Array.isArray(value) ? value.map(parse) : invalid(); }
function choice<T extends string>(value: unknown, options: readonly T[]): T { return options.find((item) => item === value) ?? invalid(); }
function instant(value: unknown) {
  const result = text(value);
  return /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})$/.test(result) && Number.isFinite(Date.parse(result)) ? result : invalid();
}
function faculty(value: unknown): Schemas['StudyGroupRef']['faculty'] {
  const item = object(value); return { id: id(item.id), code: text(item.code), name: text(item.name) };
}
function group(value: unknown): Schemas['StudyGroupRef'] {
  const item = object(value); return { id: id(item.id), name: text(item.name), faculty: faculty(item.faculty) };
}
function user(value: unknown): Schemas['UserRef'] {
  const item = object(value); return { id: id(item.id), fullName: text(item.fullName) };
}
function task(value: unknown): ProfileTask {
  const item = object(value), view = choice(item.view, ['PUBLIC', 'OWN', 'ADMIN'] as const);
  const base = { title: text(item.title), deadline: instant(item.deadline), status: choice(item.status, ['IN_PROGRESS', 'OVERDUE', 'SUBMITTED', 'ACCEPTED'] as const), charged: integer(item.charged, 0) };
  if (view === 'PUBLIC') return { ...base, view };
  if (view === 'OWN') return { ...base, view, assignmentId: id(item.assignmentId) };
  return { ...base, view, assignmentId: id(item.assignmentId) };
}
export function parseProfile(value: unknown): UserProfile {
  const item = object(value), role = choice(item.role, ['STUDENT', 'TEACHER', 'ADMIN'] as const), identity = user(item);
  if (role === 'ADMIN') return { ...identity, role };
  if (role === 'TEACHER') {
    const department = object(item.department);
    return { ...identity, role, department: { id: id(department.id), code: text(department.code), name: text(department.name), faculty: faculty(department.faculty) } };
  }
  const balances = object(item.balances);
  return { ...identity, role, group: group(item.group), balances: { AR: integer(balances.AR, 0, 1000), BUA: integer(balances.BUA, 0), BOA: integer(balances.BOA, 0) },
    activeTasks: list(item.activeTasks, task), completedTasks: list(item.completedTasks, task) };
}
function entry(value: unknown): LedgerEntry {
  const item = object(value), source = object(item.source), amount = integer(item.amount);
  if (amount === 0) return invalid();
  return { id: id(item.id), createdAt: instant(item.createdAt), currency: choice(item.currency, ['AR', 'BUA', 'BOA'] as const), amount,
    kind: choice(item.kind, ['INITIAL', 'SEMESTER_ACCRUAL', 'PENALTY', 'ACTIVITY_AWARD'] as const),
    source: { type: choice(source.type, ['REGISTRATION', 'SEMESTER', 'CHECKPOINT', 'ACTIVITY'] as const), id: source.id === null ? null : id(source.id), title: text(source.title) },
    author: item.author === null ? null : user(item.author) };
}
export function parseLedgerPage(value: unknown): Schemas['LedgerEntryPage'] {
  const item = object(value);
  return { items: list(item.items, entry), nextCursor: item.nextCursor === null ? null : text(item.nextCursor) };
}
