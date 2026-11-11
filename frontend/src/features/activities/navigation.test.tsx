import { act, cleanup, screen, waitFor } from '@testing-library/react';
import { server } from '../../mocks/server';
import { authUsers, createActivitiesScenario, mountActivities, selectParticipant } from '../../test/activitiesAcceptance';

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }));
afterEach(() => { cleanup(); server.resetHandlers(); localStorage.clear(); });
afterAll(() => server.close());

it('requires a new selection after navigating to another activity with the same participants', async () => {
  const state = createActivitiesScenario(authUsers.teacher);
  const { router, user, queryClient } = mountActivities('/activities/603/confirm');
  await selectParticipant(user);
  await waitFor(() => expect(screen.getByRole('button', { name: 'Начислить 75 БУА' })).toBeEnabled());
  state.activity = { ...state.activity, id: 604, title: 'Другая активность' };
  state.participants = { ...state.participants, activityId: 604 };
  queryClient.setQueryData(['activities', authUsers.teacher.id, 'detail', 604], state.activity);
  queryClient.setQueryData(['activities', authUsers.teacher.id, 'participants', 604], state.participants);
  await act(async () => { await router.navigate('/activities/604/confirm'); });
  await screen.findByRole('heading', { name: 'Другая активность' });
  expect(await screen.findByRole('checkbox', { name: /Выбрать Кузнецов/ })).not.toBeChecked();
  expect(screen.getByRole('button', { name: 'Начислить' })).toBeDisabled();
});
