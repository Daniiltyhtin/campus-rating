import { http, HttpResponse } from 'msw';
import { parseLedgerPage, parseProfile } from '../features/profile/contracts';
import type { UserProfile } from '../features/profile/contracts';
import { examples } from './examples.generated';
import { readUser } from './auth';

function example(operationId: string, index = 0): unknown {
  const response = examples.find((item) => item.operationId === operationId)?.responses.filter((item) => item.status >= 200 && item.status < 300)[index];
  if (!response) throw new Error(`Missing profile mock example: ${operationId}/${index}`);
  return structuredClone(response.body);
}
function problem(code: string, status: number) {
  return HttpResponse.json({ type: 'about:blank', title: 'Ошибка', code, status }, { status, headers: { 'Content-Type': 'application/problem+json' } });
}
export function createProfileMockHandlers() {
  const profiles = [0, 1, 2, 3, 4].map((index) => parseProfile(example('getUserProfile', index)));
  const ledger = parseLedgerPage(example('listLedgerEntries'));
  const roster = examples.find((item) => item.operationId === 'listActivityParticipants')?.responses.find((item) => item.status === 200)?.body;
  function profileFor(userId: number): UserProfile | null {
    const viewer = readUser();
    if (!viewer) return null;
    if (userId === 1001 || userId === 1002) {
      const source = profiles[viewer.role === 'ADMIN' ? 2 : viewer.id === userId ? 1 : 0];
      if (source?.role !== 'STUDENT') throw new Error('Missing student profile');
      if (userId === 1001) return source;
      if (!roster || typeof roster !== 'object' || !('items' in roster) || !Array.isArray(roster.items)) throw new Error('Missing roster');
      const row: unknown = roster.items.find((item: unknown) => item !== null && typeof item === 'object' && 'student' in item && item.student !== null && typeof item.student === 'object' && 'id' in item.student && item.student.id === userId);
      if (!row || typeof row !== 'object' || !('student' in row) || !row.student || typeof row.student !== 'object' || !('fullName' in row.student) || typeof row.student.fullName !== 'string') throw new Error('Missing mock student');
      return { ...source, id: userId, fullName: row.student.fullName };
    }
    return profiles.find((profile) => profile.id === userId && profile.role !== 'STUDENT') ?? null;
  }
  return [
    http.get('*/api/v1/users/:userId/profile', ({ params }) => {
      const viewer = readUser(); if (!viewer) return problem('UNAUTHENTICATED', 401);
      const profile = profileFor(Number(params.userId));
      return profile ? HttpResponse.json(profile) : problem('NOT_FOUND', 404);
    }),
    http.get('*/api/v1/ledger/:userId/entries', ({ request, params }) => {
      if (!readUser()) return problem('UNAUTHENTICATED', 401);
      const userId = Number(params.userId);
      if (userId !== 1001 && userId !== 1002) return HttpResponse.json({ items: [], nextCursor: null });
      const query = new URL(request.url).searchParams, currency = query.get('currency');
      if (currency && !['AR', 'BUA', 'BOA'].includes(currency)) return problem('VALIDATION_FAILED', 400);
      const rows = ledger.items.filter((entry) => !currency || entry.currency === currency);
      const cursor = query.get('cursor');
      if (cursor && cursor !== 'profile-page-2') return problem('VALIDATION_FAILED', 400);
      const offset = cursor ? 2 : 0;
      return HttpResponse.json({ items: rows.slice(offset, offset + 2), nextCursor: offset + 2 < rows.length ? 'profile-page-2' : null });
    }),
  ];
}
