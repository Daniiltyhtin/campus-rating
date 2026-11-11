import type { components } from '../../api/schema';

type Schemas = components['schemas'];
export type Activity = Schemas['Activity'];
export type Participant = Schemas['Participant'];
export type Level = Schemas['ParticipationLevel'];
export type Preview = Schemas['ParticipationPreview'];
export type ParticipationResult = Schemas['ParticipationResult'];
export type ParticipationRequest = Schemas['ParticipationRequest'];
export type Group = Schemas['StudyGroupRef'];
export type TeacherAward = Schemas['TeacherAward'];
export type BudgetCheck = Schemas['BudgetCheck'];
export const levels: readonly Level[] = [25, 50, 75, 100];
export const participationLimit = 500;
function object(value: unknown): value is Record<string, unknown> { return !!value && typeof value === 'object' && !Array.isArray(value); }
function integer(value: unknown): value is number { return typeof value === 'number' && Number.isSafeInteger(value); }
function id(value: unknown): value is number { return integer(value) && value > 0; }
function text(value: unknown): value is string { return typeof value === 'string'; }
function instant(value: unknown): value is string { return text(value) && /^\d{4}-\d{2}-\d{2}T.*(Z|[+-]\d{2}:\d{2})$/.test(value) && Number.isFinite(Date.parse(value)); }
function month(value: unknown): value is string { return text(value) && /^\d{4}-(0[1-9]|1[0-2])$/.test(value); }
function level(value: unknown): value is Level { return value === 25 || value === 50 || value === 75 || value === 100; }
function currency(value: unknown): value is 'BUA' | 'BOA' { return value === 'BUA' || value === 'BOA'; }
function activityType(value: unknown): value is Schemas['ActivityType'] { return value === 'ACADEMIC' || value === 'SOCIAL'; }
function matchingCurrency(type: unknown, unit: unknown) { return type === 'ACADEMIC' ? unit === 'BUA' : type === 'SOCIAL' && unit === 'BOA'; }
function faculty(value: unknown): boolean { return object(value) && id(value.id) && text(value.name) && text(value.code); }
function group(value: unknown): value is Group { return object(value) && id(value.id) && text(value.name) && faculty(value.faculty); }
function user(value: unknown): value is Schemas['UserRef'] { return object(value) && id(value.id) && text(value.fullName); }
function list<T>(value: unknown, isItem: (item: unknown) => item is T): value is T[] { return Array.isArray(value) && value.every(isItem); }
function array(value: unknown): value is unknown[] { return Array.isArray(value); }
function unique(values: readonly number[]) { return new Set(values).size === values.length; }
function award(value: unknown): value is Schemas['AwardLine'] { return object(value) && id(value.studentId) && id(value.groupId) && level(value.level) && integer(value.amount) && value.amount >= 0; }
function myAward(value: unknown): boolean { return object(value) && level(value.level) && integer(value.amount) && value.amount >= 0; }
export function isBudgetCheck(value: unknown): value is BudgetCheck {
  return object(value) && id(value.groupId) && text(value.groupName) && integer(value.needed) && integer(value.remaining) && integer(value.shortfall) && value.shortfall >= 0;
}
function isActivity(value: unknown): value is Activity {
  return object(value) && id(value.id) && text(value.title) && text(value.description) && activityType(value.type) && matchingCurrency(value.type, value.currency)
    && instant(value.startsAt) && integer(value.maxPoints) && value.maxPoints >= 2 && list(value.groups, group) && user(value.author)
    && typeof value.enrollmentOpen === 'boolean' && typeof value.confirmationOpen === 'boolean' && integer(value.enrolledCount) && integer(value.confirmedCount)
    && (value.enrolled === null || typeof value.enrolled === 'boolean') && (value.myAward === null || myAward(value.myAward));
}
function participant(value: unknown): value is Participant {
  return object(value) && user(value.student) && group(value.group) && (value.enrolled === null || typeof value.enrolled === 'boolean')
    && (value.confirmed === null || (object(value.confirmed) && myAward(value.confirmed) && instant(value.confirmed.confirmedAt)));
}
function result(value: unknown): value is ParticipationResult {
  return object(value) && currency(value.currency) && list(value.awards, award) && list(value.alreadyConfirmed, award) && integer(value.total) && value.total >= 0
    && unique([...value.awards, ...value.alreadyConfirmed].map((item) => item.studentId));
}
function budget(value: unknown): value is Schemas['GroupBudget'] { return object(value) && group(value.group) && integer(value.limit) && integer(value.spent) && integer(value.remaining); }
function teacherAward(value: unknown): value is TeacherAward {
  return object(value) && id(value.id) && object(value.activity) && id(value.activity.id) && text(value.activity.title) && activityType(value.activity.type)
    && user(value.student) && group(value.group) && level(value.level) && integer(value.amount) && currency(value.currency) && matchingCurrency(value.activity.type, value.currency) && instant(value.confirmedAt);
}
function summaryLine(value: unknown): value is Schemas['AwardSummaryLine'] { return object(value) && group(value.group) && currency(value.currency) && integer(value.amount) && integer(value.count); }
function invalid(): never { throw new Error('Некорректный ответ API активностей'); }
export function parseActivity(value: unknown): Activity { return isActivity(value) ? value : invalid(); }
export function parseActivityList(value: unknown): Schemas['ActivityList'] {
  if (!object(value) || !list(value.items, isActivity) || !unique(value.items.map((item) => item.id))) return invalid();
  return { items: value.items };
}
export function parseGroups(value: unknown): Schemas['StudyGroupList'] {
  if (!object(value) || !list(value.items, group) || !unique(value.items.map((item) => item.id))) return invalid();
  return { items: value.items };
}
export function parseParticipants(value: unknown): Schemas['ParticipantList'] {
  if (!object(value) || !id(value.activityId) || !activityType(value.type) || !integer(value.maxPoints) || !list(value.items, participant) || !unique(value.items.map((item) => item.student.id))) return invalid();
  return { activityId: value.activityId, type: value.type, maxPoints: value.maxPoints, items: value.items };
}
export function parseResult(value: unknown): ParticipationResult { return result(value) ? value : invalid(); }
export function parsePreview(value: unknown): Preview {
  if (!object(value)) return invalid();
  const { budgets, withinBudget } = value;
  if (!result(value) || !list(budgets, isBudgetCheck) || typeof withinBudget !== 'boolean') return invalid();
  return { ...value, budgets, withinBudget };
}
export function parseBudgets(value: unknown): Schemas['BudgetList'] {
  if (!object(value) || !month(value.month) || !list(value.items, budget)) return invalid();
  return { month: value.month, items: value.items };
}
export function parseAwardPage(value: unknown): Schemas['TeacherAwardPage'] {
  if (!object(value) || !list(value.items, teacherAward) || !(value.nextCursor === null || text(value.nextCursor))) return invalid();
  return { items: value.items, nextCursor: value.nextCursor };
}
export function parseSummary(value: unknown): Schemas['TeacherAwardSummary'] {
  if (!object(value) || !month(value.month) || !integer(value.total) || !integer(value.count) || !list(value.items, summaryLine)) return invalid();
  return { month: value.month, total: value.total, count: value.count, items: value.items };
}
export function parseParticipation(value: unknown): ParticipationRequest {
  if (!object(value) || !array(value.items) || value.items.length < 1 || value.items.length > participationLimit) return invalid();
  const items = value.items.map((item) => {
    if (!object(item) || !id(item.studentId) || !level(item.level)) return invalid();
    return { studentId: item.studentId, level: item.level };
  });
  if (!unique(items.map((item) => item.studentId))) return invalid();
  return { items };
}
