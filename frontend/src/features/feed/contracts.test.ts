import { describe, expect, it } from 'vitest';
import { examples } from '../../mocks/examples.generated';
import { parseAwardsPage, parseGroupRating, parsePersonalPage } from './contracts';

function example(operationId: string): unknown {
  return structuredClone(examples.find((item) => item.operationId === operationId)!.responses.find((item) => item.status === 200)!.body);
}
describe('feed input boundary', () => {
  it('accepts dereferenced contract examples without changing order or balances', () => {
    expect(parseAwardsPage(example('listAwardsFeed'))).toEqual(example('listAwardsFeed'));
    expect(parseGroupRating(example('getGroupRating'))).toEqual(example('getGroupRating'));
    expect(parsePersonalPage(example('listPersonalFeed'))).toEqual(example('listPersonalFeed'));
  });
  it.each(['assignmentId', 'checkpointTitle', 'deadline', 'kind'])('personal event requires %s', (field) => {
    const value = parsePersonalPage(example('listPersonalFeed'));
    const created = value.items.find((item) => item.kind === 'ASSIGNMENT_CREATED') as Record<string, unknown>;
    delete created[field];
    expect(() => parsePersonalPage(value)).toThrow();
  });
  it.each([0, -1, 1.5, Number.MAX_SAFE_INTEGER + 1, 2_147_483_648])('rejects invalid award amount %s', (amount) => {
    const value = parseAwardsPage(example('listAwardsFeed'));
    value.items[0]!.amount = amount;
    expect(() => parseAwardsPage(value)).toThrow();
  });
  it.each(['items', 'nextCursor'])('requires %s', (field) => {
    const value = example('listAwardsFeed') as Record<string, unknown>;
    delete value[field];
    expect(() => parseAwardsPage(value)).toThrow();
  });
  it.each(['not-a-date', '2026-11-03', '2026-11-03T14:05:00', '2026-02-30T14:05:00Z'])('rejects invalid UTC instant %s', (createdAt) => {
    const value = parseAwardsPage(example('listAwardsFeed'));
    value.items[0]!.createdAt = createdAt;
    expect(() => parseAwardsPage(value)).toThrow();
  });
  it.each([null, {}, { items: [], nextCursor: 2 }, { items: [null], nextCursor: null }])('rejects malformed page %j', (value) => {
    expect(() => parseAwardsPage(value)).toThrow();
  });
  it.each([['AR', -1], ['AR', 1001], ['BUA', -1], ['BOA', 0.5], ['BUA', Number.MAX_SAFE_INTEGER + 1]] as const)('rejects invalid balance %s=%s', (currency, amount) => {
    const value = parseGroupRating(example('getGroupRating'));
    value.items[0]!.balances[currency] = amount;
    expect(() => parseGroupRating(value)).toThrow();
  });
  it('accepts an empty rating', () => {
    const value = parseGroupRating(example('getGroupRating'));
    value.items = [];
    expect(parseGroupRating(value).items).toEqual([]);
  });
  it('rejects unknown currency and invalid nested references', () => {
    const value = example('listAwardsFeed') as { items: Record<string, unknown>[] };
    value.items[0]!.currency = 'POINTS';
    expect(() => parseAwardsPage(value)).toThrow();
    value.items[0]!.currency = 'BOA';
    value.items[0]!.teacher = { id: 0, fullName: 'Имя' };
    expect(() => parseAwardsPage(value)).toThrow();
  });
});

it('36-5 accepts zero balances in a nonempty rating', () => {
  const value = structuredClone(examples.find((item) => item.operationId === 'getGroupRating')!.responses.find((item) => item.status === 200)!.body) as ReturnType<typeof parseGroupRating>;
  value.items = [{ ...value.items[0]!, balances: { AR: 0, BUA: 0, BOA: 0 } }];
  expect(parseGroupRating(value).items).toEqual(value.items);
});


it('skips future event kinds while preserving known events and the cursor', () => {
  const known = { id: 7, createdAt: '2026-11-03T14:05:00Z', kind: 'WORK_ACCEPTED', assignmentId: 2, checkpointTitle: 'Работа' };
  const page = { items: [{ id: 8, createdAt: '2026-11-03T14:05:00Z', kind: 'FUTURE_EVENT', payload: 'new format' }, known], nextCursor: 'next' };
  expect(parsePersonalPage(page)).toEqual({ items: [known], nextCursor: 'next' });
  expect(parsePersonalPage({ ...page, items: [{ kind: 'FUTURE_EVENT' }, known] }).items).toEqual([known]);
  expect(() => parsePersonalPage({ ...page, items: [page.items[0], { ...known, assignmentId: 0 }] })).toThrow();
});
