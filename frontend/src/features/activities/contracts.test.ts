import { examples } from '../../mocks/examples.generated';
import { parseActivity, parseActivityList, parseAwardPage, parseBudgets, parseGroups, parseParticipants, parsePreview, parseResult, parseSummary } from './contracts';

function example(operationId: string): unknown {
  const entry = examples.find((item) => item.operationId === operationId)?.responses.find((item) => item.status >= 200 && item.status < 300);
  if (!entry) throw new Error(`Missing contract example: ${operationId}`);
  return structuredClone(entry.body);
}
const parsers: [string, (value: unknown) => unknown][] = [
  ['getActivity', parseActivity], ['listActivities', parseActivityList], ['listActivityParticipants', parseParticipants],
  ['previewParticipation', parsePreview], ['confirmParticipation', parseResult], ['listBudgets', parseBudgets],
  ['listMyAwards', parseAwardPage], ['getMyAwardsSummary', parseSummary], ['listAccessibleGroups', parseGroups],
];
it.each(parsers)('accepts the dereferenced contract for %s', (operationId, parse) => {
  expect(parse(example(operationId))).toBeDefined();
});
it('rejects invalid identity, flags, time and currency in an activity', () => {
  const value = example('getActivity');
  if (!value || typeof value !== 'object') throw new Error('Expected an activity example');
  for (const changes of [{ id: '603' }, { enrollmentOpen: 'yes' }, { startsAt: 'not-a-date' }, { currency: 'AR' }, { currency: 'BUA' }]) {
    expect(() => parseActivity({ ...value, ...changes })).toThrow();
  }
});
it('rejects malformed participants and incomplete preview instead of inventing a successful total', () => {
  expect(() => parseParticipants({ activityId: 603, type: 'SOCIAL', maxPoints: 100, items: [{ student: { id: 1001 } }] })).toThrow();
  expect(() => parsePreview({ currency: 'BUA', awards: [], alreadyConfirmed: [], total: 0, budgets: [] })).toThrow();
  expect(() => parseResult({ currency: 'BOA', awards: [], alreadyConfirmed: [], total: '0' })).toThrow();
});
it('keeps a zero-new-award result distinct from a repeated award', () => {
  const result = parseResult({ currency: 'BUA', awards: [], alreadyConfirmed: [{ studentId: 1001, groupId: 11, level: 50, amount: 38 }], total: 0 });
  expect(result.total).toBe(0);
  expect(result.awards).toEqual([]);
  expect(result.alreadyConfirmed[0]?.amount).toBe(38);
});
it('rejects an invalid month and a malformed cursor page', () => {
  expect(() => parseBudgets({ month: '2026-13', items: [] })).toThrow();
  expect(() => parseAwardPage({ items: [], nextCursor: 1 })).toThrow();
  expect(parseAwardPage({ items: [], nextCursor: null })).toEqual({ items: [], nextCursor: null });
});
