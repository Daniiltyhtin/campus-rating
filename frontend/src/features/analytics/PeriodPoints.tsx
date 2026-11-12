import { SegmentedControl, Stack } from '@mantine/core';
import { useState } from 'react';
import { TeacherTableLayout } from '../../app/layouts/PageLayouts';
import { FeedLink } from '../feed/FeedShared';
import type { Analytics } from './contracts';

export function PeriodPoints({ items }: { items: Analytics['items'] }) {
  const [sort, setSort] = useState<'bua' | 'boa' | 'ar'>('bua');
  const rows = [...items].sort((a, b) => b[sort] - a[sort] || a.student.fullName.localeCompare(b.student.fullName, 'ru') || a.student.id - b.student.id);
  return <Stack><SegmentedControl aria-label="Сортировка баллов за период" value={sort}
    onChange={(value) => { if (value === 'bua' || value === 'boa' || value === 'ar') setSort(value); }}
    data={[{ value: 'bua', label: 'БУА' }, { value: 'boa', label: 'БОА' }, { value: 'ar', label: 'АР' }]} />
    <TeacherTableLayout caption="Баллы за период" rows={rows} keyForRow={(row) => row.student.id} state={{ status: 'ready' }}
      columns={[{ key: 'student', title: 'ФИО', render: (row) => <FeedLink to={`/users/${row.student.id}`}>{row.student.fullName}</FeedLink> },
        { key: 'bua', title: 'БУА за период', render: (row) => row.bua }, { key: 'boa', title: 'БОА за период', render: (row) => row.boa },
        { key: 'ar', title: 'АР на конец периода', render: (row) => row.ar }]} />
  </Stack>;
}
