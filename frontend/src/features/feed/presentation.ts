import type { Award, PersonalEvent } from './contracts';

export function uniqueAwards(items: readonly Award[]) {
  const seen = new Set<number>();
  return items.filter((item) => {
    if (seen.has(item.entryId)) return false;
    seen.add(item.entryId); return true;
  });
}
function instantKey(value: string) {
  const utc = value.replace(/\+00:00$/, 'Z');
  const [seconds, fraction = ''] = utc.slice(0, -1).split('.');
  return `${seconds}.${fraction.replace(/0+$/, '').padEnd(9, '0')}`;
}
export function newerAwards(head: readonly Award[], visible: readonly Award[]) {
  const first = visible[0];
  if (!first) return uniqueAwards(head);
  const known = new Set(visible.map((item) => item.entryId));
  return uniqueAwards(head).filter((item) => !known.has(item.entryId)
    && (instantKey(item.createdAt) > instantKey(first.createdAt) || (instantKey(item.createdAt) === instantKey(first.createdAt) && item.entryId > first.entryId)));
}

const feedTime = new Intl.DateTimeFormat('ru-RU', { timeZone: 'Europe/Moscow', year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', hourCycle: 'h23' });
export function formatFeedTime(instant: string) {
  const parts = Object.fromEntries(feedTime.formatToParts(new Date(instant)).map((part) => [part.type, part.value]));
  return `${parts.day}.${parts.month}.${parts.year} ${parts.hour}:${parts.minute}`;
}
const currencyName = { AR: 'АР', BUA: 'БУА', BOA: 'БОА' } as const;
export type PersonalEventView = { text: string; to: string };
export function personalEventView(event: PersonalEvent, viewerId: number): PersonalEventView {
  switch (event.kind) {
    case 'ASSIGNMENT_CREATED': return { text: `Новая задача «${event.checkpointTitle}» · до ${formatFeedTime(event.deadline)}`, to: `/tasks/${event.assignmentId}` };
    case 'DEADLINE_REMINDER': return { text: `До дедлайна «${event.checkpointTitle}» меньше суток`, to: `/tasks/${event.assignmentId}` };
    case 'WORK_ACCEPTED': return { text: `Работа «${event.checkpointTitle}» принята`, to: `/tasks/${event.assignmentId}` };
    case 'WORK_RETURNED': return { text: `Работу «${event.checkpointTitle}» вернули на доработку`, to: `/tasks/${event.assignmentId}` };
    case 'POINTS_AWARDED': return { text: `Начислено ${event.amount} ${currencyName[event.currency]} за «${event.activityTitle}»`, to: `/users/${viewerId}` };
    case 'ACTIVITY_CREATED': return { text: `Новая активность «${event.activityTitle}» для вашей группы`, to: '/activities' };
    case 'WORK_SUBMITTED': return { text: `Сдана работа «${event.checkpointTitle}» · ${event.studentFullName}`, to: `/checkpoints/${event.checkpointId}` };
  }
}
export function uniqueEvents(items: readonly PersonalEvent[]) {
  const seen = new Set<number>();
  return items.filter((item) => !seen.has(item.id) && !!seen.add(item.id));
}
