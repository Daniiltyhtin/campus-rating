import { describe, expect, it } from 'vitest';
import { examples } from '../../mocks/examples.generated';
import { parseAwardsPage, parseGroupRating } from './contracts';

function example(operationId: string): unknown {
  return structuredClone(examples.find((item) => item.operationId === operationId)!.responses.find((item) => item.status === 200)!.body);
}
describe('feed input boundary', () => {
  it('accepts dereferenced contract examples without changing order or balances', () => {
    expect(parseAwardsPage(example('listAwardsFeed'))).toEqual(example('listAwardsFeed'));
    expect(parseGroupRating(example('getGroupRating'))).toEqual(example('getGroupRating'));
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
  it('accepts zero balances and an empty rating', () => {
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
