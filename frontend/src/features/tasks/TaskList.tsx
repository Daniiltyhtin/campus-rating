import { useTimedInfiniteQuery } from '../../app/infiniteTiming';
import { Anchor, Stack, Tabs, Text } from '@mantine/core';
import { useState } from 'react';
import { Link } from 'react-router';
import { api } from '../../api/client';
import { ListLayout, PageLayout } from '../../app/layouts/PageLayouts';
import { More, Status } from './shared';
import { dataOf, dateTime, loadState } from './helpers';

function emptyMessage(pending: boolean, view: 'active' | 'completed') {
  if (pending) return 'Членство ждёт подтверждения';
  if (view === 'active')
    return 'Новых задач нет. Когда преподаватель создаст точку для вашей группы, она появится здесь';
  return 'Завершённых задач пока нет. Здесь появятся принятые работы.';
}

export function TaskList() {
  const [view, setView] = useState<'active' | 'completed'>('active');
  const query = useTimedInfiniteQuery({
    queryKey: ['tasks', view],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) =>
      dataOf(api.GET('/api/v1/me/assignments', { params: { query: { view, cursor: pageParam } } })),
    getNextPageParam: (page) => (view === 'completed' ? (page.nextCursor ?? undefined) : undefined),
  });
  const items = query.data?.pages.flatMap((page) => page.items) ?? [];
  const pending = query.data?.pages[0]?.membershipState === 'PENDING';
  return (
    <PageLayout title="Мои задачи">
      <Tabs value={view} onChange={(value) => setView(value === 'completed' ? 'completed' : 'active')}>
        <Tabs.List>
          <Tabs.Tab value="active">Активные</Tabs.Tab>
          <Tabs.Tab value="completed">Завершённые</Tabs.Tab>
        </Tabs.List>
      </Tabs>
      <ListLayout
        title={view === 'active' ? 'Активные задачи' : 'Завершённые задачи'}
        items={items}
        keyForItem={(item) => item.assignmentId}
        state={loadState(query, pending || !items.length, emptyMessage(pending, view))}
        renderItem={(item) => (
          <Stack gap="xs">
            <Anchor component={Link} to={`/tasks/${item.assignmentId}`}>
              {item.checkpoint.title}
            </Anchor>
            <Text>{item.teacher.fullName}</Text>
            <Text>Дедлайн: {dateTime(item.deadline)} (МСК)</Text>
            <Status status={item.status} />
            <Text className="cr-ar">
              Списано {item.penalty.charged} из {item.cost} АР
            </Text>
          </Stack>
        )}
      />
      <More {...query} />
      <>
        {query.isFetchNextPageError && (
          <Text role="alert">Не удалось загрузить следующую страницу. Попробуйте ещё раз.</Text>
        )}
      </>
    </PageLayout>
  );
}
