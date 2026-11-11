import type { Award } from './contracts';

export function uniqueAwards(items: readonly Award[]) {
  const seen = new Set<number>();
  return items.filter((item) => {
    if (seen.has(item.entryId)) return false;
    seen.add(item.entryId); return true;
  });
}
export function newerAwards(head: readonly Award[], visible: readonly Award[]) {
  const first = visible[0];
  if (!first) return uniqueAwards(head);
  const known = new Set(visible.map((item) => item.entryId));
  return uniqueAwards(head).filter((item) => !known.has(item.entryId)
    && (Date.parse(item.createdAt) > Date.parse(first.createdAt) || (Date.parse(item.createdAt) === Date.parse(first.createdAt) && item.entryId > first.entryId)));
}
