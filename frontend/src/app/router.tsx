import type { RouteObject } from 'react-router';
import { LoginPlaceholder } from '../features/auth/LoginPlaceholder';
import { SectionPlaceholder, SessionGate } from './routeElements';

export const routes: RouteObject[] = [
  {
    children: [{ path: 'login', element: <LoginPlaceholder /> }],
  },
  {
    element: <SessionGate />,
    children: [
      { index: true, element: <SectionPlaceholder /> },
      { path: '*', element: <SectionPlaceholder /> },
    ],
  },
];
