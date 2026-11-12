import { Anchor, TextInput } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router';
import { api } from '../../../api/client';
import { PageLayout, TeacherTableLayout } from '../../../app/layouts/PageLayouts';
import { dataOf, loadState } from '../../tasks/helpers';
import { AdminOnly } from '../shared';

function Budgets() {
  const [month, setMonth] = useState(() => new Intl.DateTimeFormat('sv-SE', { timeZone: 'Europe/Moscow', year: 'numeric', month: '2-digit' }).format(new Date()));
  const query = useQuery({ queryKey: ['admin-budgets', month], queryFn: () => dataOf(api.GET('/api/v1/admin/budgets', { params: { query: { month } } })) });
  const caption = new Intl.DateTimeFormat('ru-RU', { month: 'long', year: 'numeric', timeZone: 'Europe/Moscow' }).format(new Date(`${month}-01T00:00:00+03:00`)).replace(' г.', '');
  return <PageLayout title="Бюджеты"><TextInput type="month" label="Месяц" value={month} onChange={(e) => { if (/^\d{4}-(0[1-9]|1[0-2])$/.test(e.currentTarget.value)) setMonth(e.currentTarget.value); }} />
    <TeacherTableLayout caption="Бюджеты преподавателей" rows={query.data?.items ?? []} keyForRow={(r) => `${r.teacher.id}-${r.group.id}`} state={loadState(query, query.data?.items.length === 0, `За ${caption} начислений не было`)}
      columns={[{ key: 'teacher', title: 'Преподаватель', render: (r) => <Anchor component={Link} to={`/users/${r.teacher.id}`}>{r.teacher.fullName}</Anchor> },
        { key: 'group', title: 'Группа', render: (r) => r.group.name }, { key: 'limit', title: 'Лимит', render: (r) => r.limit },
        { key: 'spent', title: 'Потрачено', render: (r) => r.spent }, { key: 'remaining', title: 'Остаток', render: (r) => r.remaining }]} />
  </PageLayout>;
}
export function AdminBudgetsPage() { return <AdminOnly><Budgets /></AdminOnly>; }
