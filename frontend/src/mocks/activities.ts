import { http, HttpResponse } from 'msw';
import type { components } from '../api/schema';
import { activityClock } from '../features/activities/time';
import type { Clock } from '../features/activities/time';
import { parseActivity, parseActivityList, parseAwardPage, parseBudgets, parseGroups, parseParticipants, parseParticipation, parseSummary } from '../features/activities/contracts';
import type { Activity, Participant, ParticipationRequest, Preview, TeacherAward } from '../features/activities/contracts';
import { readUser, validCsrf } from './auth';
import { examples } from './examples.generated';

type Schemas = components['schemas'];
function example(id: string): unknown {
  const value = examples.find((item) => item.operationId === id)?.responses.find((item) => item.status >= 200 && item.status < 300);
  if (!value) throw new Error(`Missing mock example: ${id}`);
  return structuredClone(value.body);
}
function problem(code: string, status = 403, details = {}) {
  return HttpResponse.json({ type: 'about:blank', title: 'Ошибка', code, status, ...details }, { status, headers: { 'Content-Type': 'application/problem+json' } });
}
function monthAt(date: Date) {
  const parts = new Intl.DateTimeFormat('en-GB', { timeZone: 'Europe/Moscow', year: 'numeric', month: '2-digit' }).formatToParts(date);
  return `${parts.find((part) => part.type === 'year')?.value}-${parts.find((part) => part.type === 'month')?.value}`;
}
function creation(value: unknown): value is Schemas['ActivityCreate'] {
  if (!value || typeof value !== 'object') return false;
  return 'title' in value && typeof value.title === 'string' && value.title.trim().length > 0 && [...value.title].length <= 200
    && 'description' in value && typeof value.description === 'string' && [...value.description].length <= 10_000
    && 'type' in value && (value.type === 'ACADEMIC' || value.type === 'SOCIAL')
    && 'startsAt' in value && typeof value.startsAt === 'string' && Number.isFinite(Date.parse(value.startsAt))
    && 'maxPoints' in value && typeof value.maxPoints === 'number' && Number.isInteger(value.maxPoints) && value.maxPoints >= 2 && value.maxPoints <= 10_000
    && 'groupIds' in value && Array.isArray(value.groupIds) && value.groupIds.length > 0
    && value.groupIds.every((id: unknown) => typeof id === 'number' && Number.isSafeInteger(id) && id > 0) && new Set(value.groupIds).size === value.groupIds.length;
}

export function createActivityMockHandlers(clock: Clock = activityClock) {
  const groups = parseGroups(example('listAccessibleGroups')).items;
  const seed = parseActivityList(example('listActivities')).items;
  const academic = parseActivity(example('createActivity'));
  const activities: Activity[] = [parseActivity(example('getActivity')), { ...academic, id: 602, startsAt: seed[1]!.startsAt, confirmationOpen: true }, seed[1]!];
  const roster = parseParticipants(example('listActivityParticipants')).items;
  const budgets = parseBudgets(example('listBudgets')).items;
  const baseline = parseSummary(example('getMyAwardsSummary'));
  const history = parseAwardPage(example('listMyAwards')).items;
  const initialMonth = monthAt(clock.now());
  const extraAwards: TeacherAward[] = [];
  const confirmed = new Map<string, NonNullable<Participant['confirmed']>>();
  const enrolled = new Map<string, boolean>();
  const key = (activityId: number, studentId: number) => `${activityId}:${studentId}`;
  let nextId = 605;
  const isEnrolled = (activity: Activity, studentId: number) => enrolled.get(key(activity.id, studentId)) ?? (activity.id === 601);
  const visible = (activity: Activity) => {
    const user = readUser();
    const past = Date.parse(activity.startsAt) <= clock.now().getTime();
    const award = user && confirmed.get(key(activity.id, user.id));
    return { ...activity, enrollmentOpen: activity.type === 'SOCIAL' && !past, confirmationOpen: past,
      enrolled: user?.role === 'STUDENT' && activity.type === 'SOCIAL' ? isEnrolled(activity, user.id) : null,
      myAward: user?.role === 'STUDENT' ? award ?? (activity.id === 601 ? activity.myAward : null) : null };
  };
  const participants = (activity: Activity): Participant[] => roster.filter((row) => activity.groups.some((group) => group.id === row.group.id)).map((row) => ({
    ...row, enrolled: activity.type === 'SOCIAL' ? isEnrolled(activity, row.student.id) : null,
    confirmed: confirmed.get(key(activity.id, row.student.id)) ?? (activity.id === 601 ? row.confirmed : null),
  }));
  const access = (request: Request, teacherOnly = false) => {
    const user = readUser();
    if (!user) return problem('UNAUTHENTICATED', 401);
    if (user.role === 'ADMIN' || (teacherOnly && user.role !== 'TEACHER')) return problem('FORBIDDEN');
    if (request.method !== 'GET' && !validCsrf(request)) return problem('CSRF_INVALID');
    return null;
  };
  const own = (request: Request, activity: Activity | undefined) => access(request, true)
    ?? (!activity ? problem('NOT_FOUND', 404) : activity.author.id !== readUser()?.id ? problem('FORBIDDEN') : null);
  const spentExtra = (groupId: number, month: string) => extraAwards.filter((row) => row.group.id === groupId && monthAt(new Date(row.confirmedAt)) === month).reduce((sum, row) => sum + row.amount, 0);
  const budgetItems = (month: string) => budgets.map((row) => {
    const spent = (month === initialMonth ? row.spent : 0) + spentExtra(row.group.id, month);
    return { ...row, spent, remaining: row.limit - spent };
  });
  function preview(activity: Activity, body: ParticipationRequest): Preview {
    const rows = participants(activity);
    const awards: Schemas['AwardLine'][] = [], alreadyConfirmed: Schemas['AwardLine'][] = [];
    for (const item of body.items) {
      const row = rows.find((row) => row.student.id === item.studentId)!;
      const known = row.confirmed;
      const line = { studentId: item.studentId, groupId: row.group.id, level: known?.level ?? item.level,
        amount: known?.amount ?? Math.round(activity.maxPoints * item.level / 100) };
      (known ? alreadyConfirmed : awards).push(line);
    }
    const checks = budgetItems(monthAt(clock.now())).filter((row) => awards.some((line) => line.groupId === row.group.id)).map((row) => {
      const needed = awards.filter((line) => line.groupId === row.group.id).reduce((sum, line) => sum + line.amount, 0);
      return { groupId: row.group.id, groupName: row.group.name, needed, remaining: row.remaining, shortfall: Math.max(0, needed - row.remaining) };
    });
    return { awards, alreadyConfirmed, currency: activity.currency, total: awards.reduce((sum, row) => sum + row.amount, 0), budgets: checks, withinBudget: checks.every((row) => row.shortfall === 0) };
  }
  async function participation(request: Request, id: number, commit: boolean) {
    const activity = activities.find((row) => row.id === id);
    const error = own(request, activity);
    if (error || !activity) return error!;
    if (!visible(activity).confirmationOpen) return problem('INVALID_STATE', 409);
    let body: ParticipationRequest;
    try { body = parseParticipation(await request.json()); } catch { return problem('VALIDATION_FAILED', 400, { fieldErrors: [{ field: 'items', code: 'INVALID', message: 'Выберите от 1 до 500 участников без повторов.' }] }); }
    const rows = participants(activity);
    const invalid = body.items.filter((item) => !rows.some((row) => row.student.id === item.studentId)).map((row) => row.studentId);
    if (invalid.length) return problem('STUDENT_NOT_ELIGIBLE', 422, { studentIds: invalid });
    const absent = body.items.filter((item) => activity.type === 'SOCIAL' && !rows.find((row) => row.student.id === item.studentId)?.enrolled).map((row) => row.studentId);
    if (absent.length) return problem('NOT_ENROLLED', 422, { studentIds: absent });
    const result = preview(activity, body);
    if (!commit) return HttpResponse.json(result);
    if (!result.withinBudget) return problem('BUDGET_EXCEEDED', 422, { budgets: result.budgets });
    const confirmedAt = clock.now().toISOString();
    for (const line of result.awards) {
      const row = rows.find((row) => row.student.id === line.studentId)!;
      confirmed.set(key(id, line.studentId), { level: line.level, amount: line.amount, confirmedAt });
      extraAwards.unshift({ id: 10_000 + extraAwards.length, activity: { id, title: activity.title, type: activity.type }, student: row.student, group: row.group, level: line.level, amount: line.amount, currency: activity.currency, confirmedAt });
    }
    activity.confirmedCount += result.awards.length;
    return HttpResponse.json({ awards: result.awards, alreadyConfirmed: result.alreadyConfirmed, currency: result.currency, total: result.total });
  }
  return [
    http.get('*/api/v1/me/accessible-groups', ({ request }) => {
      const user = readUser();
      return access(request, true) ?? HttpResponse.json({ items: user?.role === 'TEACHER' && user.teacherApproved ? groups : [] });
    }),
    http.get('*/api/v1/activities', ({ request }) => {
      const error = access(request); if (error) return error;
      const user = readUser();
      const items = activities.filter((row) => user?.role === 'TEACHER' ? row.author.id === user.id : user?.role === 'STUDENT' && row.groups.some((group) => group.id === user.group.id)).map(visible);
      items.sort((a, b) => a.confirmationOpen !== b.confirmationOpen ? Number(a.confirmationOpen) - Number(b.confirmationOpen) : a.confirmationOpen ? Date.parse(b.startsAt) - Date.parse(a.startsAt) : Date.parse(a.startsAt) - Date.parse(b.startsAt));
      return HttpResponse.json({ items });
    }),
    http.post('*/api/v1/activities', async ({ request }) => {
      const error = access(request, true); if (error) return error;
      const user = readUser();
      const body: unknown = await request.json();
      if (!creation(body) || Date.parse(body.startsAt) <= clock.now().getTime()) return problem('VALIDATION_FAILED', 400, { fieldErrors: [{ field: 'startsAt', code: 'INVALID', message: 'Проверьте поля и дату в будущем.' }] });
      if (user?.role !== 'TEACHER' || !user.teacherApproved || body.groupIds.some((id) => !groups.some((row) => row.id === id))) return problem('FORBIDDEN');
      const activity: Activity = { ...academic, ...body, id: nextId++, currency: body.type === 'ACADEMIC' ? 'BUA' : 'BOA', groups: groups.filter((row) => body.groupIds.includes(row.id)), author: { id: user.id, fullName: user.fullName }, enrolled: body.type === 'ACADEMIC' ? null : false, enrollmentOpen: body.type === 'SOCIAL', confirmationOpen: false };
      activities.unshift(activity);
      return HttpResponse.json(visible(activity), { status: 201 });
    }),
    http.get('*/api/v1/activities/:activityId', ({ request, params }) => {
      const error = access(request); if (error) return error;
      const activity = activities.find((row) => row.id === Number(params.activityId));
      if (!activity) return problem('NOT_FOUND', 404);
      const user = readUser();
      if (user?.role === 'TEACHER' ? activity.author.id !== user.id : user?.role !== 'STUDENT' || !activity.groups.some((group) => group.id === user.group.id)) return problem('FORBIDDEN');
      return HttpResponse.json(visible(activity));
    }),
    ...(['put', 'delete'] as const).map((method) => http[method]('*/api/v1/activities/:activityId/enrollment', ({ request, params }) => {
      const error = access(request); if (error) return error;
      const user = readUser(), activity = activities.find((row) => row.id === Number(params.activityId));
      if (!activity) return problem('NOT_FOUND', 404);
      if (user?.role !== 'STUDENT' || !activity.groups.some((group) => group.id === user.group.id)) return problem('FORBIDDEN');
      if (activity.type !== 'SOCIAL') return problem('INVALID_STATE', 409);
      if (!visible(activity).enrollmentOpen) return problem('ENROLLMENT_CLOSED', 409);
      if (method === 'put' && user.membershipState !== 'ACTIVE') return problem('MEMBERSHIP_PENDING', 403);
      const next = method === 'put';
      if (isEnrolled(activity, user.id) !== next) activity.enrolledCount += next ? 1 : -1;
      enrolled.set(key(activity.id, user.id), next);
      return HttpResponse.json(visible(activity));
    })),
    http.get('*/api/v1/activities/:activityId/participants', ({ request, params }) => {
      const activity = activities.find((row) => row.id === Number(params.activityId));
      const error = own(request, activity); if (error || !activity) return error!;
      return HttpResponse.json({ activityId: activity.id, type: activity.type, maxPoints: activity.maxPoints, items: participants(activity) });
    }),
    http.post('*/api/v1/activities/:activityId/participation/preview', ({ request, params }) => participation(request, Number(params.activityId), false)),
    http.post('*/api/v1/activities/:activityId/participation', ({ request, params }) => participation(request, Number(params.activityId), true)),
    http.get('*/api/v1/budgets', ({ request }) => {
      const error = access(request, true); if (error) return error;
      const month = new URL(request.url).searchParams.get('month') ?? monthAt(clock.now());
      return HttpResponse.json({ month, items: budgetItems(month) });
    }),
    http.get('*/api/v1/me/awards', ({ request }) => {
      const error = access(request, true); if (error) return error;
      const query = new URL(request.url).searchParams;
      const month = query.get('month') ?? monthAt(clock.now()), group = query.get('groupId');
      const items = [...extraAwards, ...history].filter((row) => monthAt(new Date(row.confirmedAt)) === month && (!group || String(row.group.id) === group));
      const offset = Number(query.get('cursor') ?? 0);
      return HttpResponse.json({ items: items.slice(offset, offset + 1), nextCursor: offset + 1 < items.length ? String(offset + 1) : null });
    }),
    http.get('*/api/v1/me/awards/summary', ({ request }) => {
      const error = access(request, true); if (error) return error;
      const month = new URL(request.url).searchParams.get('month') ?? monthAt(clock.now());
      const items = month === initialMonth ? structuredClone(baseline.items) : [];
      for (const award of extraAwards.filter((row) => monthAt(new Date(row.confirmedAt)) === month)) {
        const row = items.find((row) => row.group.id === award.group.id && row.currency === award.currency);
        if (row) { row.amount += award.amount; row.count++; }
        else items.push({ group: award.group, currency: award.currency, amount: award.amount, count: 1 });
      }
      return HttpResponse.json({ month, items, total: items.reduce((sum, row) => sum + row.amount, 0), count: items.reduce((sum, row) => sum + row.count, 0) });
    }),
  ];
}
