import type { components } from '../../api/schema';
import { parseGroupRating } from '../feed/contracts';

type Schemas = components['schemas'];
export type Analytics = Schemas['GroupAnalytics'];
export type Budget = Schemas['GroupAnalyticsBudget'];
function invalid(): never { throw new Error('Некорректный ответ аналитики'); }
function object(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : invalid();
}
function integer(value: unknown): number { return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : invalid(); }
function text(value: unknown): string { return typeof value === 'string' ? value : invalid(); }
function date(value: unknown): string {
  const result = text(value);
  return /^\d{4}-\d{2}-\d{2}$/.test(result) && Number.isFinite(Date.parse(result)) && new Date(result).toISOString().slice(0, 10) === result ? result : invalid();
}
function list<T>(value: unknown, parse: (item: unknown) => T): T[] { return Array.isArray(value) ? value.map(parse) : invalid(); }
function user(value: unknown): Schemas['UserRef'] {
  const row = object(value), id = integer(row.id);
  return id > 0 ? { id, fullName: text(row.fullName) } : invalid();
}
export function parseAnalytics(value: unknown): Analytics {
  const row = object(value);
  const group = parseGroupRating({ group: row.group, sort: 'AR', items: [] }).group;
  return { group, semesterStart: row.semesterStart === null ? null : date(row.semesterStart),
    days: list(row.days, (value) => {
      const day = object(value), average = day.averageAr;
      if (average !== null && (typeof average !== 'number' || !Number.isFinite(average))) return invalid();
      return { date: date(day.date), averageAr: average, students: integer(day.students) };
    }),
    items: list(row.items, (value) => { const item = object(value); return { student: user(item.student), bua: integer(item.bua), boa: integer(item.boa), ar: integer(item.ar) }; }) };
}
export function parseBudget(value: unknown): Budget {
  return { items: list(object(value).items, (value) => {
    const row = object(value), month = text(row.month);
    if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(month)) return invalid();
    return { month, teacher: row.teacher === null ? null : user(row.teacher), limit: integer(row.limit), spent: integer(row.spent),
      remaining: row.remaining === null ? null : integer(row.remaining), awards: integer(row.awards), bua: integer(row.bua), boa: integer(row.boa) };
  }) };
}
export function todayMoscow() { return new Intl.DateTimeFormat('sv-SE', { timeZone: 'Europe/Moscow', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date()); }
export function minusDays(date: string, days: number) { const result = new Date(`${date}T12:00:00Z`); result.setUTCDate(result.getUTCDate() - days); return result.toISOString().slice(0, 10); }
export function validPeriod(from: string, to: string, today: string) {
  return /^\d{4}-\d{2}-\d{2}$/.test(from) && /^\d{4}-\d{2}-\d{2}$/.test(to)
    && Number.isFinite(Date.parse(from)) && Number.isFinite(Date.parse(to)) && from <= to && to <= today
    && (Date.parse(to) - Date.parse(from)) / 86400000 < 366;
}
export const periodError = 'Допустимо не больше 366 дней, «С» не позже «По» и «По» не позже сегодня';
