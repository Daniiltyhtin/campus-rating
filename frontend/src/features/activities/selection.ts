import type { Level } from './contracts';
import { participationLimit } from './contracts';

export function updateSelection(current: Record<number, Level>, studentId: number, level: Level | undefined): Record<number, Level> {
  if (level !== undefined && current[studentId] === undefined && Object.keys(current).length >= participationLimit) return current;
  const next = { ...current };
  if (level === undefined) delete next[studentId];
  else next[studentId] = level;
  return next;
}
