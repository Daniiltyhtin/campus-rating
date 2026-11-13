import type { components } from '../../api/schema';

type Schemas = components['schemas'];
export type Award = Schemas['AwardFeedItem'];
export type AwardsPage = Schemas['AwardFeedPage'];
export type GroupRating = Schemas['GroupRating'];
export type Currency = Schemas['Currency'];
export type PersonalEvent = Schemas['PersonalFeedItem'];
export type PersonalPage = Schemas['PersonalFeedPage'];
function invalid(): never { throw new Error('Некорректный ответ API ленты или рейтинга'); }
function object(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : invalid();
}
function text(value: unknown) { return typeof value === 'string' ? value : invalid(); }
function integer(value: unknown, min = 0, max = Number.MAX_SAFE_INTEGER): number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= min && value <= max ? value : invalid();
}
function id(value: unknown) { return integer(value, 1); }
function list<T>(value: unknown, parse: (value: unknown) => T): T[] { return Array.isArray(value) ? value.map(parse) : invalid(); }
export function parseCurrency(value: unknown): Currency { return value === 'AR' || value === 'BUA' || value === 'BOA' ? value : invalid(); }
function user(value: unknown): Schemas['UserRef'] {
  const row = object(value); return { id: id(row.id), fullName: text(row.fullName) };
}
function group(value: unknown): Schemas['StudyGroupRef'] {
  const row = object(value), faculty = object(row.faculty);
  return { id: id(row.id), name: text(row.name), faculty: { id: id(faculty.id), code: text(faculty.code), name: text(faculty.name) } };
}
function instant(value: unknown) {
  const result = text(value);
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$/.test(result) || !Number.isFinite(Date.parse(result))) return invalid();
  return new Date(result).toISOString().slice(0, 19) === result.slice(0, 19) ? result : invalid();
}
function award(value: unknown): Award {
  const row = object(value), activity = object(row.activity);
  return { entryId: id(row.entryId), createdAt: instant(row.createdAt), student: user(row.student), teacher: user(row.teacher),
    activity: { id: id(activity.id), title: text(activity.title) }, amount: integer(row.amount, 1, 2_147_483_647), currency: parseCurrency(row.currency) };
}
export function parseAwardsPage(value: unknown): AwardsPage {
  const row = object(value);
  return { items: list(row.items, award), nextCursor: row.nextCursor === null ? null : text(row.nextCursor) };
}
function ratingRow(value: unknown): Schemas['RatingRow'] {
  const row = object(value), balances = object(row.balances);
  return { student: user(row.student), balances: { AR: integer(balances.AR, 0, 1000), BUA: integer(balances.BUA), BOA: integer(balances.BOA) } };
}
export function parseGroupRating(value: unknown): GroupRating {
  const row = object(value);
  return { group: group(row.group), sort: parseCurrency(row.sort), items: list(row.items, ratingRow) };
}
function personalEvent(value: unknown): PersonalEvent | null {
  const row = object(value), kind = text(row.kind);
  const base = () => ({ id: id(row.id), createdAt: instant(row.createdAt) });
  switch (kind) {
    case 'ASSIGNMENT_CREATED': case 'DEADLINE_REMINDER':
      return { ...base(), kind, assignmentId: id(row.assignmentId), checkpointTitle: text(row.checkpointTitle), deadline: instant(row.deadline) };
    case 'WORK_ACCEPTED': case 'WORK_RETURNED':
      return { ...base(), kind, assignmentId: id(row.assignmentId), checkpointTitle: text(row.checkpointTitle) };
    case 'POINTS_AWARDED':
      return { ...base(), kind, activityId: id(row.activityId), activityTitle: text(row.activityTitle),
        amount: integer(row.amount, 1, 2_147_483_647), currency: parseCurrency(row.currency) };
    case 'ACTIVITY_CREATED':
      return { ...base(), kind, activityId: id(row.activityId), activityTitle: text(row.activityTitle) };
    case 'WORK_SUBMITTED':
      return { ...base(), kind, checkpointId: id(row.checkpointId), checkpointTitle: text(row.checkpointTitle), studentFullName: text(row.studentFullName) };
    case 'POLL_CREATED':
      return { ...base(), kind, pollId: id(row.pollId), pollTitle: text(row.pollTitle), endsAt: instant(row.endsAt) };
    case 'POLL_CLOSED':
      return { ...base(), kind, pollId: id(row.pollId), pollTitle: text(row.pollTitle), voted: integer(row.voted), eligible: integer(row.eligible) };
    default: return null;
  }
}
export function parsePersonalPage(value: unknown): PersonalPage {
  const row = object(value);
  return { items: list(row.items, personalEvent).filter((item): item is PersonalEvent => item !== null), nextCursor: row.nextCursor === null ? null : text(row.nextCursor) };
}
