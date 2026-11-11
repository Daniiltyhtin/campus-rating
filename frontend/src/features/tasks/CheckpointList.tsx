import { Anchor, Button, Stack, Text } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router';
import { api } from '../../api/client';
import { ListLayout, PageLayout } from '../../app/layouts/PageLayouts';
import { dataOf, dateTime, loadState } from './helpers';

export function CheckpointList() {
  const query = useQuery({ queryKey: ['checkpoints'], queryFn: () => dataOf(api.GET('/api/v1/checkpoints')) });
  const items = query.data?.items ?? [];
  return (
    <PageLayout
      title="Мои точки"
      actions={
        <Button component={Link} to="/checkpoints/new">
          Новая точка
        </Button>
      }
    >
      <ListLayout
        title="Контрольные точки"
        items={items}
        keyForItem={(item) => item.id}
        state={loadState(query, !items.length, 'Точек пока нет. Создайте точку для доступных групп.')}
        renderItem={(item) => (
          <Stack gap="xs">
            <Anchor component={Link} to={`/checkpoints/${item.id}`}>
              {item.title}
            </Anchor>
            <Text>Дедлайн: {dateTime(item.deadline)} (МСК)</Text>
            <Text>Ждёт проверки: {item.submittedCount}</Text>
          </Stack>
        )}
      />
    </PageLayout>
  );
}
