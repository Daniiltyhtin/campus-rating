import type { components } from '../../api/schema';

export interface Clock { now(): Date }
export const activityClock: Clock = { now: () => new Date() };
const moscowParts = new Intl.DateTimeFormat('en-GB', { timeZone: 'Europe/Moscow', year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', hourCycle: 'h23' });
const displayTime = new Intl.DateTimeFormat('ru-RU', { timeZone: 'Europe/Moscow', year: 'numeric', month: 'long', day: 'numeric', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' });

export function formatMoscowTime(instant: string) { return `${displayTime.format(new Date(instant))} МСК`; }
function calendarAt(instant: number) {
  const parts = Object.fromEntries(moscowParts.formatToParts(new Date(instant)).map((part) => [part.type, part.value]));
  return `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}`;
}
export function moscowInputToUtc(input: string): string | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/.exec(input);
  if (!match) return null;
  const year = Number(match[1]), month = Number(match[2]), day = Number(match[3]), hour = Number(match[4]), minute = Number(match[5]);
  const naive = Date.UTC(year, month - 1, day, hour, minute);
  if (!Number.isFinite(naive)) return null;
  const at = calendarAt(naive);
  const offset = Date.parse(`${at}:00Z`) - naive;
  const instant = naive - offset;
  return calendarAt(instant) === input ? new Date(instant).toISOString() : null;
}
export type CreationFields = { title: string; description: string; type: string; groupIds: string[]; startsAt: string; maxPoints: string };
export function validateCreation(fields: CreationFields, groups: readonly { id: number }[], now: Date):
  { ok: true; body: components['schemas']['ActivityCreate'] } | { ok: false; errors: Record<string, string> } {
  const errors: Record<string, string> = {};
  const title = fields.title.trim();
  if (!title || [...title].length > 200) errors.title = 'Укажите название длиной от 1 до 200 символов';
  if ([...fields.description].length > 10_000) errors.description = 'Описание — не больше 10000 символов';
  const maxPoints = Number(fields.maxPoints);
  if (!fields.maxPoints.trim() || !Number.isInteger(maxPoints) || maxPoints < 2 || maxPoints > 10_000) errors.maxPoints = 'Максимум — целое число от 2 до 10000 баллов';
  const groupIds = fields.groupIds.map(Number);
  if (groupIds.length === 0 || new Set(groupIds).size !== groupIds.length || groupIds.some((id) => !groups.some((group) => group.id === id))) errors.groupIds = 'Выберите хотя бы одну доступную группу без повторов';
  const startsAt = moscowInputToUtc(fields.startsAt);
  if (!startsAt || Date.parse(startsAt) <= now.getTime()) errors.startsAt = 'Укажите корректные дату и время начала в будущем, по Москве';
  const type = fields.type;
  if (type !== 'ACADEMIC' && type !== 'SOCIAL') errors.type = 'Выберите тип активности';
  if (Object.keys(errors).length || !startsAt || (type !== 'ACADEMIC' && type !== 'SOCIAL')) return { ok: false, errors };
  return { ok: true, body: { title, description: fields.description, type, groupIds, startsAt, maxPoints } };
}
