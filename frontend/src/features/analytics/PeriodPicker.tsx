import { Group, Select, Stack, Text, TextInput } from '@mantine/core';
import { useState } from 'react';
import { periodError, validPeriod } from './contracts';

export function PeriodPicker({ period, from, to, today, semesterStart, change, dates }: {
  period: string; from: string; to: string; today: string; semesterStart: string | null;
  change: (value: string) => void; dates: (from: string, to: string) => void;
}) {
  const [first, setFirst] = useState(from), [last, setLast] = useState(to), [touched, setTouched] = useState(false);
  const valid = validPeriod(first, last, today);
  const commit = () => { setTouched(true); if (valid) dates(first, last); };
  return <Stack>
    <Select label="Период" value={period} allowDeselect={false} onChange={(value) => { if (value) change(value); }}
      data={[{ value: '30', label: '30 дней' }, { value: '90', label: '90 дней' },
        { value: 'semester', label: 'С начала семестра', disabled: !semesterStart }, { value: 'custom', label: 'Свои даты' }]}
      renderOption={({ option }) => <Stack gap="xs"><span>{option.label}</span>{option.value === 'semester' && !semesterStart && <Text size="sm" aria-hidden="true">Семестр не начат</Text>}</Stack>} />
    {period === 'custom' && <><Group grow align="start">
      <TextInput type="date" label="С" value={first} onChange={(event) => setFirst(event.currentTarget.value)} onBlur={commit} aria-invalid={touched && !valid} />
      <TextInput type="date" label="По" value={last} onChange={(event) => setLast(event.currentTarget.value)} onBlur={commit} aria-invalid={touched && !valid} />
    </Group>{touched && !valid && <Text role="alert">{periodError}</Text>}</>}
  </Stack>;
}
