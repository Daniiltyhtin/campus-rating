import type { Award } from './contracts';

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
