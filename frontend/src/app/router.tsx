import { redirect, type RouteObject } from 'react-router';
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
      { path: 'password/set/:token', lazy: async () => ({ Component: (await import('../features/auth/SetPasswordPage')).SetPasswordPage }) },
      { path: 'email/confirm/:token', lazy: async () => ({ Component: (await import('../features/auth/EmailVerification')).ConfirmEmailPage }) },
      { element: <GuestGate />, children: [{ path: 'login', element: <LoginPage /> }, { path: 'register', element: <RegisterPage /> }] },
      { element: <PasswordGate />, children: [{ path: 'password/change', element: <ChangePasswordPage /> }] },
      { element: <SessionGate />, children: [
        { path: 'admin/directory/teachers', loader: () => redirect('/admin/users?role=TEACHER&status=PENDING') },
        { path: 'admin/directory/teachers/:id', loader: ({ params }) => redirect(`/admin/users/teachers/${params.id}`) },
        { path: 'admin/users/teachers/:id', lazy: async () => ({ Component: (await import('../features/admin/teachers/TeacherPage')).TeacherPage }) },
        { path: 'admin/import', lazy: async () => ({ Component: (await import('../features/admin/import/AdminImportPage')).AdminImportPage }) },
        { path: 'admin/submissions/:assignmentId', lazy: async () => ({ Component: (await import('../features/admin/submissions/AdminSubmissionPage')).AdminSubmissionPage }) },
        { path: 'admin/users', lazy: async () => ({ Component: (await import('../features/admin/users/AdminUsersPage')).AdminUsersPage }) },
        { path: 'memberships', lazy: async () => ({ Component: (await import('../features/groups/PendingMembers')).PendingMembersPage }) },
        { path: 'groups/:id/students', lazy: async () => ({ Component: (await import('../features/groups/GroupStudentsPage')).GroupStudentsPage }) },
        { path: 'admin/semesters', lazy: async () => ({ Component: (await import('../features/admin/semesters/AdminSemestersPage')).AdminSemestersPage }) },
        { path: 'admin/settings', lazy: async () => ({ Component: (await import('../features/admin/settings/AdminSettingsPage')).AdminSettingsPage }) },
        { path: 'admin/budgets', lazy: async () => ({ Component: (await import('../features/admin/budgets/AdminBudgetsPage')).AdminBudgetsPage }) },
        { path: 'admin/directory', lazy: async () => ({ Component: (await import('../features/admin/directory/DirectoryPage')).DirectoryPage }) },
        { path: 'tasks', lazy: async () => ({ Component: (await import('../features/tasks/TaskList')).TaskList }) },
        { path: 'tasks/:id', lazy: async () => ({ Component: (await import('../features/tasks/TaskPage')).TaskPage }) },
        { path: 'checkpoints', lazy: async () => ({ Component: (await import('../features/tasks/CheckpointList')).CheckpointList }) },
        { path: 'checkpoints/new', lazy: async () => ({ Component: (await import('../features/tasks/CheckpointForms')).CheckpointNew }) },
        { path: 'checkpoints/:id', lazy: async () => ({ Component: (await import('../features/tasks/CheckpointPage')).CheckpointPage }) },
        { path: 'awards', lazy: async () => ({ Component: (await import('../features/feed/AwardsFeed')).AwardsFeed }) },
        { path: 'groups/:id/ratings', loader: ({ params }) => redirect(`/groups/${params.id}`) },
        { path: 'groups/:id', lazy: async () => ({ Component: (await import('../features/feed/GroupRatings')).GroupRatings }) },
        { path: 'users/:id', lazy: async () => ({ Component: (await import('../features/profile/ProfilePage')).ProfilePage }) },
        { path: 'activities', lazy: async () => ({ Component: (await import('../features/activities/ActivityListPage')).ActivityListPage }) },
        { path: 'activities/new', lazy: async () => ({ Component: (await import('../features/activities/CreateActivityPage')).CreateActivityPage }) },
        { path: 'activities/:id/confirm', lazy: async () => ({ Component: (await import('../features/activities/ConfirmParticipationPage')).ConfirmParticipationPage }) },
        { path: 'polls', lazy: async () => ({ Component: (await import('../features/polls/PollsPage')).PollsPage }) },
        { path: 'polls/new', lazy: async () => ({ Component: (await import('../features/polls/NewPollPage')).NewPollPage }) },
        { path: 'polls/:id', lazy: async () => ({ Component: (await import('../features/polls/PollPage')).PollPage }) },
        { path: 'groups', lazy: async () => ({ Component: (await import('../features/activities/BudgetPage')).BudgetPage }) },
        { index: true, lazy: async () => ({ Component: (await import('../features/feed/PersonalFeed')).HomePage }) },
        { path: '*', element: <SectionPlaceholder /> },
      ] },
    ],
  },
];
