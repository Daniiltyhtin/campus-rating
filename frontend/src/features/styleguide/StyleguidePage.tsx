import { Button, Checkbox, Group, SegmentedControl, Select, Stack, Text, TextInput, Textarea, Title } from '@mantine/core';
import { useState } from 'react';
import { CardLayout, FormLayout, ListLayout, PageLayout, TeacherTableLayout } from '../../app/layouts/PageLayouts';
import type { LoadState } from '../../app/layouts/PageLayouts';
import { useCampusAppearance } from '../../theme/useCampusAppearance';

const works = [
  { id: 1, fullName: 'Смирнова Анна Олеговна', group: 'ИВТ-21', status: 'Просрочено', className: 'cr-status-overdue', charged: 40 },
  { id: 2, fullName: 'Кузнецов Дмитрий Андреевич', group: 'ИВТ-21', status: 'Сдано на проверку', className: 'cr-status-submitted', charged: 60 },
];
const tasks = [{ id: 1, title: 'Лабораторная 3. Деревья поиска', teacher: 'Петров Павел Петрович', deadline: '2 ноября, 12:00' }];

export function StyleguidePage() {
  const { appearance, setAppearance } = useCampusAppearance();
  const [status, setStatus] = useState<LoadState['status']>('ready');
  const [saved, setSaved] = useState(false);
  const [selected, setSelected] = useState<number[]>([1, 2]);
  const [accepted, setAccepted] = useState<number | null>(null);
  const state: LoadState = status === 'error' ? { status, message: 'Проверьте соединение и попробуйте снова.', retry: () => setStatus('ready') }
    : status === 'empty' ? { status, message: 'Здесь пока нет данных' } : { status };
  return <PageLayout title="Редакция" description="Холодная бумага, синий акцент. Literata и PT Sans, ясная типографика и линейки."
    actions={<Select label="Тема интерфейса" allowDeselect={false} value={appearance} onChange={(value) => {
      if (value === 'light' || value === 'dark' || value === 'contrast') setAppearance(value);
    }} data={[{ value: 'light', label: 'Светлая' }, { value: 'dark', label: 'Тёмная' }, { value: 'contrast', label: 'Высокий контраст' }]} />}>
    <Stack gap="md"><Text className="cr-kicker">Компоненты и состояния</Text>
      <SegmentedControl aria-label="Состояние примеров" value={status} onChange={(value) => setStatus(value as LoadState['status'])}
        data={[{ value: 'ready', label: 'Данные' }, { value: 'loading', label: 'Загрузка' }, { value: 'empty', label: 'Пусто' }, { value: 'error', label: 'Ошибка' }]} />
      <Group gap="sm"><Button>Основное действие</Button><Button variant="default">Второе действие</Button>
        <Button disabled>Недоступно</Button><Button loading>Сохранение</Button></Group>
      <Group gap="sm"><span className="cr-status cr-status-in-progress">В процессе</span><span className="cr-status cr-status-submitted">Сдано на проверку</span>
        <span className="cr-status cr-status-overdue">Просрочено</span><span className="cr-status cr-status-accepted">Принято</span></Group>
    </Stack>
    <div className="cr-template-grid">
      <ListLayout title="Список задач" state={state} items={tasks} keyForItem={(item) => item.id} renderItem={(item) => <Stack gap="xs">
        <Title order={3}>{item.title}</Title><Text className="cr-caption">{item.teacher} · {item.deadline}</Text>
        <Group><span className="cr-status cr-status-overdue">Просрочено</span><Text className="cr-ar">Списано 40 из 60 АР</Text></Group>
        <Button variant="outline">Открыть задачу</Button></Stack>} />
      <CardLayout title="Карточка студента"><Stack><Title order={3}>Смирнова Анна Олеговна</Title><Text className="cr-caption">ФИТ · ИВТ-21</Text>
        <Group gap="xl"><Stack gap="xs"><Text className="cr-kicker">АР</Text><Title order={2} className="cr-number cr-ar">940</Title></Stack>
          <Stack gap="xs"><Text className="cr-kicker">БУА</Text><Title order={2} className="cr-number cr-bua">120</Title></Stack>
          <Stack gap="xs"><Text className="cr-kicker">БОА</Text><Title order={2} className="cr-number cr-boa">50</Title></Stack></Group>
        <Text>Баллы и статусы всегда подписаны — смысл не передаётся одним цветом.</Text></Stack></CardLayout>
      <FormLayout title="Форма" onSubmit={(event) => { event.preventDefault(); setSaved(true); }}>
        <TextInput label="Название" placeholder="Лабораторная работа" required />
        <Textarea label="Комментарий" placeholder="Что нужно дополнить" rows={3} />
        <Checkbox label="Я проверил данные" /><TextInput label="Ошибка поля" defaultValue="" error="Заполните обязательное поле" />
        <Button type="submit">Сохранить пример</Button>{saved && <Text role="status" className="cr-success"><span aria-hidden="true" className="cr-success-icon">✓</span><strong>Пример сохранён.</strong></Text>}
      </FormLayout>
      <section><Stack gap="sm">
        {accepted !== null && <Text role="status" className="cr-success"><span aria-hidden="true" className="cr-success-icon">✓</span>
          <span><strong>Принято {accepted} из {selected.length}.</strong> Просроченные работы пропущены. Это пример состояния.</span></Text>}
        <div className="cr-selection"><strong>Выбрано {selected.length}</strong>
          <Button variant="subtle" onClick={() => { setSelected([]); setAccepted(null); }}>Снять выбор</Button>
          <Button disabled={selected.length === 0} onClick={() => setAccepted(works.filter((row) => selected.includes(row.id) && row.status === 'Сдано на проверку').length)}>Принять</Button></div>
        <TeacherTableLayout caption="Работы студентов" rows={works} keyForRow={(row) => row.id} state={state} isSelected={(row) => selected.includes(row.id)}
        columns={[{ key: 'student', title: 'Студент', render: (row) => <Checkbox label={row.fullName} checked={selected.includes(row.id)} onChange={(event) => {
          setSelected(event.currentTarget.checked ? [...selected, row.id] : selected.filter((id) => id !== row.id)); setAccepted(null);
        }} /> },
          { key: 'group', title: 'Группа', render: (row) => row.group },
          { key: 'status', title: 'Статус', render: (row) => <span className={`cr-status ${row.className}`}>{row.status}</span> },
          { key: 'charged', title: 'Списано', render: (row) => `${row.charged} АР` }]} /></Stack></section>
    </div>
  </PageLayout>;
}
