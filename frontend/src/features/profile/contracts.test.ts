import { examples } from '../../mocks/examples.generated';
import { parseLedgerPage, parseProfile } from './contracts';

const profiles = examples.find((item) => item.operationId === 'getUserProfile')!.responses.filter((item) => item.status === 200).map((item) => item.body);
const page = examples.find((item) => item.operationId === 'listLedgerEntries')!.responses.find((item) => item.status === 200)!.body;
it.each(profiles.map((profile, index) => ({ profile, index })))('accepts the generated profile variant $index', ({ profile }) => {
  expect(parseProfile(profile)).toEqual(profile);
});
it('accepts the ledger contract and keeps negative amounts and system authors', () => {
  expect(parseLedgerPage(page)).toEqual(page);
});
it.each([{ id: '1001' }, { balances: { AR: -1, BUA: 0, BOA: 0 } }, { balances: { AR: 1001, BUA: 0, BOA: 0 } }, { balances: { AR: 940, BUA: -1, BOA: 0 } }, { role: 'OTHER' }, { activeTasks: [{ view: 'OWN', title: 'Task' }] }])('rejects malformed profile %#', (changes) => {
  expect(() => parseProfile({ ...Object(profiles[1]), ...changes })).toThrow();
});
it('drops unexpected submission fields and an assignmentId from a PUBLIC task', () => {
  const profile = parseProfile(profiles[0]);
  if (profile.role !== 'STUDENT') throw new Error('Expected student');
  const value = parseProfile({ ...profile, activeTasks: [{ ...profile.activeTasks[0], assignmentId: 7001, link: 'private', file: 'secret', returnComment: 'private' }] });
  if (value.role !== 'STUDENT') throw new Error('Expected student');
  expect(value.activeTasks[0]).not.toHaveProperty('assignmentId');
  expect(value.activeTasks[0]).not.toHaveProperty('link');
  expect(value.activeTasks[0]).not.toHaveProperty('file');
  expect(value.activeTasks[0]).not.toHaveProperty('returnComment');
});
it.each([0, 1.5, Number.MAX_SAFE_INTEGER + 1, '50'])('rejects invalid ledger amount %s', (amount) => {
  const parsed = parseLedgerPage(page);
  expect(() => parseLedgerPage({ ...parsed, items: [{ ...parsed.items[0], amount }] })).toThrow();
});
it('rejects malformed identity, source, author, currency and timestamps', () => {
  const parsed = parseLedgerPage(page);
  for (const changes of [{ id: 0 }, { currency: 'MONEY' }, { source: null }, { author: { id: '2001', fullName: 'Teacher' } }, { createdAt: '2026-11-11' }]) {
    expect(() => parseLedgerPage({ items: [{ ...parsed.items[0], ...changes }], nextCursor: null })).toThrow();
  }
  expect(() => parseLedgerPage({ items: [], nextCursor: 1 })).toThrow();
});
