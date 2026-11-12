import { Text } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { api } from '../../api/client';
import { dateTime } from '../tasks/helpers';

export function PasswordAudit() {
  const query = useQuery({ queryKey: ['password-audit'], queryFn: async () => {
    const result = await api.GET('/api/v1/me/password-audit');
    if (!result.response.ok) throw new Error('Не удалось загрузить сведения о пароле');
    return result.response.status === 204 ? null : result.data;
  } });
  return query.data ? <Text>Пароль выставлен: {query.data.actor.fullName}, {dateTime(query.data.createdAt)}</Text> : null;
}
