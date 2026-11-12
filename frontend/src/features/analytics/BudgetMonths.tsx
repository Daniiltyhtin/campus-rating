import { TeacherTableLayout } from '../../app/layouts/PageLayouts';
import { feedState } from '../feed/queries';
import { useAnalyticsBudget } from './queries';

function month(value: string) { return new Intl.DateTimeFormat('ru-RU', { month: 'long', year: 'numeric', timeZone: 'Europe/Moscow' }).format(new Date(`${value}-01T12:00:00Z`)).replace(/ г\.$/, ''); }
export function BudgetMonths({ viewerId, groupId, admin }: { viewerId: number; groupId: number; admin: boolean }) {
  const query = useAnalyticsBudget(viewerId, groupId);
  return <TeacherTableLayout caption="Расход бюджета по месяцам" rows={query.data?.items ?? []}
    keyForRow={(row) => `${row.month}-${row.teacher?.id ?? 'empty'}`} state={feedState(query, !query.data?.items.length, 'Начислений пока нет')}
    columns={[{ key: 'month', title: 'Месяц', render: (row) => month(row.month) },
      ...(admin ? [{ key: 'teacher', title: 'Преподаватель', render: (row: NonNullable<typeof query.data>['items'][number]) => row.teacher?.fullName ?? '—' }] : []),
      { key: 'limit', title: 'Лимит', render: (row) => row.limit }, { key: 'spent', title: 'Потрачено', render: (row) => row.spent },
      { key: 'remaining', title: 'Остаток', render: (row) => row.remaining ?? '—' }, { key: 'awards', title: 'Начислений', render: (row) => row.awards },
      { key: 'bua', title: 'БУА', render: (row) => row.bua }, { key: 'boa', title: 'БОА', render: (row) => row.boa }]} />;
}
