import { Anchor, Stack, Text } from '@mantine/core';
import { CurrencyIcon } from '../../app/CurrencyMark';
import { Link } from 'react-router';
import type { ReactNode } from 'react';
import { ApiError } from '../../api/client';
import { PageLayout } from '../../app/layouts/PageLayouts';
import { useAuthSession } from '../auth/authQueries';
import type { CurrentUser } from '../auth/contracts';
import { activityError } from './errors';
import type { BudgetCheck, Participant } from './contracts';
import './activities.css';

export function ActivityPage({ title, description, actions, children }: { title: string; description?: string; actions?: ReactNode; children: ReactNode }) {
  return <div className="activities-page"><PageLayout title={title} description={description} actions={actions}>{children}</PageLayout></div>;
}
export function ActivityAccess({ teacherOnly = false, children }: { teacherOnly?: boolean; children: (user: Exclude<CurrentUser, { role: 'ADMIN' }>) => ReactNode }) {
  const { session } = useAuthSession();
  const user = session.data;
  if (!user || user.role === 'ADMIN' || (teacherOnly && user.role !== 'TEACHER')) return <ActivityPage title="Нет доступа"><Text>Этот раздел недоступен для вашей роли.</Text></ActivityPage>;
  return children(user);
}
export function ActivityLink({ to, children }: { to: string; children: ReactNode }) {
  return <Anchor component={Link} to={to} c="var(--cr-link)" className="activity-link">{children}</Anchor>;
}
export function BudgetChecks({ budgets }: { budgets: readonly BudgetCheck[] }) {
  return <Stack gap="sm">{budgets.map((budget) => <div key={budget.groupId} className="cr-article">
    <Text fw={700}>{budget.groupName}</Text><Text>Нужно {budget.needed} · Остаток {budget.remaining}</Text>
    <Text className={budget.shortfall > 0 ? 'activity-danger' : 'cr-caption'}>{budget.shortfall > 0 ? `Не хватает ${budget.shortfall}` : 'Бюджета хватает'}</Text>
  </div>)}</Stack>;
}
export function ActivityFailure({ error, participants = [] }: { error: unknown; participants?: readonly Participant[] }) {
  let names = '';
  if (error instanceof ApiError && 'studentIds' in error.problem) names = error.problem.studentIds
    .map((id) => participants.find((item) => item.student.id === id)?.student.fullName ?? `Студент №${id}`).join(', ');
  return <div role="alert"><Text>{activityError(error)}{names ? `: ${names}` : ''}</Text>
    {error instanceof ApiError && error.problem.code === 'BUDGET_EXCEEDED' && <BudgetChecks budgets={error.problem.budgets} />}</div>;
}
export function Points({ amount, currency }: { amount: number; currency: string }) {
  return <span className={`cr-number cr-amount ${currency === 'BUA' ? 'cr-bua' : 'cr-boa'}`}><CurrencyIcon currency={currency === 'BUA' ? 'BUA' : 'BOA'} />{amount} {currency === 'BUA' ? 'БУА' : 'БОА'}</span>;
}
