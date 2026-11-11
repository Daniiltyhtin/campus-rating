import type { RouteObject } from 'react-router';
import { LoginPlaceholder } from '../features/auth/LoginPlaceholder';
import { RouteLoading, SectionPlaceholder, SessionGate } from './routeElements';

export const routes: RouteObject[] = [
  {
    hydrateFallbackElement: <RouteLoading />,
    children: [{ path: 'login', element: <LoginPlaceholder /> }, {
      path: 'styleguide', lazy: async () => ({ Component: (await import('../features/styleguide/StyleguidePage')).StyleguidePage }),
    }],
  },
  {
    element: <SessionGate />,
    children: [
      { index: true, element: <SectionPlaceholder /> },
      { path: 'tasks', lazy: async () => ({ Component: (await import('../features/tasks/TaskList')).TaskList }) },
      { path: 'tasks/:id', lazy: async () => ({ Component: (await import('../features/tasks/TaskPage')).TaskPage }) },
      { path: 'checkpoints', lazy: async () => ({ Component: (await import('../features/tasks/CheckpointList')).CheckpointList }) },
      { path: 'checkpoints/new', lazy: async () => ({ Component: (await import('../features/tasks/CheckpointForms')).CheckpointNew }) },
      { path: 'checkpoints/:id', lazy: async () => ({ Component: (await import('../features/tasks/CheckpointPage')).CheckpointPage }) },
      { path: '*', element: <SectionPlaceholder /> },
    ],
  },
];
