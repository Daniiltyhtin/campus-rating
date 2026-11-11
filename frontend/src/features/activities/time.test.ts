import { formatMoscowTime, moscowInputToUtc, validateCreation } from './time';

it('converts the Moscow wall-clock input to UTC independently of browser time zone', () => {
  expect(moscowInputToUtc('2026-11-21T14:00')).toBe('2026-11-21T11:00:00.000Z');
  expect(moscowInputToUtc('2026-01-01T01:30')).toBe('2025-12-31T22:30:00.000Z');
  expect(formatMoscowTime('2026-11-21T11:00:00Z')).toContain('14:00');
  expect(formatMoscowTime('2026-11-21T11:00:00Z')).toContain('21 ноября');
});
it.each(['', '2026-02-30T12:00', '2026-11-21T25:00', 'not-a-date'])('rejects invalid Moscow calendar input %s', (input) => {
  expect(moscowInputToUtc(input)).toBeNull();
});

const values = { title: ' Хакатон ', description: 'Описание', type: 'ACADEMIC', groupIds: ['11'], startsAt: '2026-11-21T14:00', maxPoints: '75' };
const groups = [{ id: 11 }, { id: 12 }];
const now = new Date('2026-11-11T10:00:00Z');
it('validates and normalizes a creation request once at the form boundary', () => {
  expect(validateCreation(values, groups, now)).toEqual({ ok: true, body: { title: 'Хакатон', description: 'Описание', type: 'ACADEMIC',
    groupIds: [11], startsAt: '2026-11-21T11:00:00.000Z', maxPoints: 75 } });
});
it.each(['1', '10001', '2.5', '', 'not-a-number'])('rejects maxPoints %s', (maxPoints) => {
  const result = validateCreation({ ...values, maxPoints }, groups, now);
  expect(result).toMatchObject({ ok: false, errors: { maxPoints: expect.any(String) } });
});
it.each(['2', '10000'])('accepts the maxPoints boundary %s', (maxPoints) => {
  expect(validateCreation({ ...values, maxPoints }, groups, now)).toMatchObject({ ok: true, body: { maxPoints: Number(maxPoints) } });
});
it.each([[], ['11', '11'], ['13']].map((groupIds) => ({ groupIds })))('rejects missing, duplicate or inaccessible groups %#', ({ groupIds }) => {
  expect(validateCreation({ ...values, groupIds }, groups, now)).toMatchObject({ ok: false, errors: { groupIds: expect.any(String) } });
});
it('rejects an event at now, a past event and an empty title', () => {
  expect(validateCreation({ ...values, startsAt: '2026-11-11T13:00' }, groups, now)).toMatchObject({ ok: false, errors: { startsAt: expect.any(String) } });
  expect(validateCreation({ ...values, startsAt: '2026-11-10T13:00' }, groups, now)).toMatchObject({ ok: false });
  expect(validateCreation({ ...values, title: '  ' }, groups, now)).toMatchObject({ ok: false, errors: { title: expect.any(String) } });
});
