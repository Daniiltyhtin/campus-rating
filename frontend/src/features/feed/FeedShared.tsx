import { Anchor } from '@mantine/core';
import type { ReactNode } from 'react';
import { Link } from 'react-router';
import { PageLayout } from '../../app/layouts/PageLayouts';
import './feed.css';

export function FeedLink({ to, children }: { to: string; children: ReactNode }) {
  return <Anchor component={Link} to={to} c="var(--cr-link)" className="feed-link">{children}</Anchor>;
}
export function FeedPage({ title, description, children }: { title: string; description?: string; children: ReactNode }) {
  return <div className="feed-page"><PageLayout title={title} description={description}>{children}</PageLayout></div>;
}
const iconPaths = {
  ASSIGNMENT_CREATED: 'M6 3h9l3 3v15H6z M9 12h6 M9 16h6',
  DEADLINE_REMINDER: 'M12 21a8 8 0 1 0 0-16 8 8 0 0 0 0 16z M12 9v4l3 2',
  WORK_ACCEPTED: 'M5 12l4 4 10-10',
  WORK_RETURNED: 'M9 7L4 12l5 5 M4 12h11a5 5 0 0 1 0 10h-2',
  POINTS_AWARDED: 'M12 3l2.7 5.6 6.1.9-4.4 4.3 1 6.1L12 17l-5.4 2.9 1-6.1-4.4-4.3 6.1-.9z',
  ACTIVITY_CREATED: 'M4 6h16v14H4z M4 10h16 M8 3v5 M16 3v5',
  WORK_SUBMITTED: 'M12 16V4 M7 9l5-5 5 5 M5 20h14',
  POLL_CREATED: 'M5 20V10 M12 20V4 M19 20v-7',
  POLL_CLOSED: 'M5 20V10 M12 20V4 M19 20v-7 M3 20h18',
} as const;
export function FeedIcon({ kind }: { kind: keyof typeof iconPaths }) {
  return <svg className="feed-icon" viewBox="0 0 24 24" width="24" height="24" aria-hidden="true" fill="none" stroke="currentColor"
    strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d={iconPaths[kind]} /></svg>;
}
