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
      { path: '*', element: <SectionPlaceholder /> },
    ],
  },
];
