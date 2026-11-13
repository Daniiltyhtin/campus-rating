import { Text } from '@mantine/core';
import { ActivityLink } from '../activities/ActivityShared';
import { formatMoscowTime } from '../activities/time';
import type { PollSummary } from './queries';

function pollStatus(poll: PollSummary) {
  const when = `${poll.open ? 'до' : 'завершён'} ${formatMoscowTime(poll.endsAt)}`;
  return poll.voted ? `${when} · вы проголосовали` : when;
}

export function PollLink({ poll }: { poll: PollSummary }) {
  return <div className="cr-article"><ActivityLink to={`/polls/${poll.id}`}>{poll.title}</ActivityLink>
    <Text className="cr-caption">{pollStatus(poll)}{poll.anonymous ? ' · анонимный' : ''}</Text></div>;
}
