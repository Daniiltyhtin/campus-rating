import type { RouteObject } from 'react-router';
import { LoginPage } from '../features/auth/LoginPage';
import { RegisterPage } from '../features/auth/RegisterPage';
import { ChangePasswordPage } from '../features/auth/ChangePasswordPage';
import { AuthBoundary, GuestGate, PasswordGate, RouteLoading, SectionPlaceholder, SessionGate } from './routeElements';

export const routes: RouteObject[] = [
  {
    hydrateFallbackElement: <RouteLoading />,
    children: [{
      path: 'styleguide', lazy: async () => ({ Component: (await import('../features/styleguide/StyleguidePage')).StyleguidePage }),
    }],
  },
  {
    element: <AuthBoundary />,
    children: [
      { element: <GuestGate />, children: [{ path: 'login', element: <LoginPage /> }, { path: 'register', element: <RegisterPage /> }] },
      { element: <PasswordGate />, children: [{ path: 'password/change', element: <ChangePasswordPage /> }] },
      { element: <SessionGate />, children: [
        { path: 'activities', lazy: async () => ({ Component: (await import('../features/activities/ActivityListPage')).ActivityListPage }) },
        { path: 'activities/new', lazy: async () => ({ Component: (await import('../features/activities/CreateActivityPage')).CreateActivityPage }) },
        { path: 'activities/:id/confirm', lazy: async () => ({ Component: (await import('../features/activities/ConfirmParticipationPage')).ConfirmParticipationPage }) },
        { path: 'groups', lazy: async () => ({ Component: (await import('../features/activities/BudgetPage')).BudgetPage }) },
        { index: true, element: <SectionPlaceholder /> }, { path: '*', element: <SectionPlaceholder /> },
      ] },
    ],
  },
];
