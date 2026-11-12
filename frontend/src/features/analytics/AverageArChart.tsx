import { LineChart } from '@mantine/charts';
import { Stack, Title } from '@mantine/core';
import { TeacherTableLayout } from '../../app/layouts/PageLayouts';
import type { Analytics } from './contracts';

const average = (value: number | null) => value === null ? '—' : value.toLocaleString('ru-RU', { minimumFractionDigits: 1, maximumFractionDigits: 1, useGrouping: false });
const day = (value: string) => value.split('-').reverse().join('.');
export function AverageArChart({ days }: { days: Analytics['days'] }) {
  return <Stack><Title order={2}>Средний АР группы</Title>
    <LineChart role="application" aria-label="Средний АР группы" lineChartProps={{ role: 'img' }} h={280} data={days.map((row) => ({ ...row, date: day(row.date) }))} dataKey="date"
      series={[{ name: 'averageAr', label: 'Средний АР', color: 'var(--cr-ar)' }]} connectNulls={false}
      valueFormatter={average} withLegend={false} />
    <TeacherTableLayout caption="Средний АР группы" rows={days} keyForRow={(row) => row.date} state={{ status: 'ready' }}
      columns={[{ key: 'date', title: 'Дата', render: (row) => day(row.date) },
        { key: 'averageAr', title: 'Средний АР', render: (row) => average(row.averageAr) },
        { key: 'students', title: 'Студентов', render: (row) => row.students }]} />
  </Stack>;
}
