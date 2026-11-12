import { Text } from '@mantine/core';
import type { ReactNode } from 'react';
import { ApiError } from '../../api/client';
import { useAuthSession } from '../auth/authQueries';
import { TaskFailure } from '../tasks/shared';
import './admin.css';

export function AdminOnly({ children }: { children: ReactNode }) {
  const user = useAuthSession().session.data;
  return user?.role === 'ADMIN' ? <div className="admin-page">{children}</div> : <TaskFailure forbidden />;
}
function adminError(error: unknown) {
  return error instanceof ApiError ? error.problem.detail ?? 'Не удалось выполнить действие' : 'Не удалось выполнить действие. Проверьте соединение.';
}
export function AdminError({ error }: { error: unknown }) {
  return error ? <Text role="alert">{adminError(error)}</Text> : null;
}
