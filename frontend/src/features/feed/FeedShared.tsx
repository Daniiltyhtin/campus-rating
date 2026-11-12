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
