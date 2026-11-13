import { Button, Modal, Stack, Text, TextInput } from '@mantine/core';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api } from '../../../api/client';
import type { components } from '../../../api/schema';
import { AsyncContent, PageLayout } from '../../../app/layouts/PageLayouts';
import { dataOf, loadState, fieldError } from '../../tasks/helpers';
import { AdminError, AdminOnly } from '../shared';

type Settings = components['schemas']['AdminSettings'];
const fields = [{ key: 'penaltyStep', label: 'Шаг штрафа, АР', min: 1, max: 1000 },
  { key: 'penaltyIntervalSeconds', label: 'Интервал штрафа, с', min: 2, max: 86400 },
  { key: 'semesterAccrual', label: 'Начисление семестра, АР', min: 0, max: 1000 },
  { key: 'monthlyBudgetLimit', label: 'Лимит бюджета в месяц', min: 0, max: 100000 }] as const;
type Key = typeof fields[number]['key'];
function SettingsForm({ settings }: { settings: Settings }) {
  const client = useQueryClient();
  const [values, setValues] = useState(() => Object.fromEntries(fields.map((f) => [f.key, String(settings[f.key])])) as Record<Key, string>);
  const [errors, setErrors] = useState<Partial<Record<Key, string>>>({}), [impact, setImpact] = useState<number>();
  const [saved, setSaved] = useState(false);
  const body = () => Object.fromEntries(fields.map((f) => [f.key, Number(values[f.key])])) as components['schemas']['AdminSettingsRequest'];
  const save = useMutation({ mutationFn: () => dataOf(api.PUT('/api/v1/admin/settings', { body: body() })), onSuccess: async () => {
    setImpact(undefined); setSaved(true); await client.invalidateQueries({ queryKey: ['admin-settings'] }); await client.invalidateQueries({ queryKey: ['admin-budgets'] });
  } });
  const preview = useMutation({ mutationFn: () => dataOf(api.GET('/api/v1/admin/budgets/impact', { params: { query: { limit: Number(values.monthlyBudgetLimit) } } })),
    onSuccess: (result) => { if (result.count) setImpact(result.count); else save.mutate(); } });
  function submit() {
    setSaved(false); const next: Partial<Record<Key, string>> = {};
    for (const f of fields) { const n = Number(values[f.key]), min = f.key === 'penaltyIntervalSeconds' ? settings.minPenaltyIntervalSeconds : f.min;
      if (!values[f.key].trim() || !Number.isInteger(n) || n < min || n > f.max) next[f.key] = `Допустимо от ${min} до ${f.max}`; }
    setErrors(next); if (Object.keys(next).length) return;
    if (Number(values.monthlyBudgetLimit) < settings.monthlyBudgetLimit) preview.mutate(); else save.mutate();
  }
  return <Stack><form noValidate onSubmit={(e) => { e.preventDefault(); submit(); }}><Stack>{fields.map((f) => <TextInput key={f.key} type="number" step="1" label={f.label} value={values[f.key]} disabled={save.isPending || preview.isPending || impact !== undefined}
    onChange={(e) => { setValues({ ...values, [f.key]: e.currentTarget.value }); setSaved(false); }} error={errors[f.key] ?? fieldError(save.error, f.key)} />)}
    <Button type="submit" loading={save.isPending || preview.isPending}>Сохранить</Button></Stack></form>
    {saved && <Text role="status">Настройки сохранены</Text>}<AdminError error={save.error ?? preview.error} />
    <Modal opened={impact !== undefined} onClose={() => { if (!save.isPending) setImpact(undefined); }} title="Снизить лимит бюджета?">
      <Stack><Text>Пар «преподаватель + группа», у которых расход в этом месяце уже больше нового лимита: {impact}. Их остаток станет 0, новые начисления в эти группы до конца месяца будут отклоняться.</Text>
        <Button loading={save.isPending} onClick={() => save.mutate()}>Сохранить</Button><Button variant="default" disabled={save.isPending} onClick={() => setImpact(undefined)}>Отмена</Button><AdminError error={save.error} /></Stack>
    </Modal></Stack>;
}
function Settings() {
  const query = useQuery({ queryKey: ['admin-settings'], queryFn: () => dataOf(api.GET('/api/v1/admin/settings')) });
  return <PageLayout title="Настройки"><AsyncContent state={loadState(query)}>{query.data && <SettingsForm settings={query.data} />}</AsyncContent></PageLayout>;
}
export function AdminSettingsPage() { return <AdminOnly><Settings /></AdminOnly>; }
