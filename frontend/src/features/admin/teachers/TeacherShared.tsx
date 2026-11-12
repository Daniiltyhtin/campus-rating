import { Anchor } from '@mantine/core';
import type { ReactNode } from 'react';
import { Link } from 'react-router';
import { PageLayout } from '../../../app/layouts/PageLayouts';
import './teachers.css';

export function TeacherLayout({ title, children }: { title: string; children: ReactNode }) { return <div className="teachers-page"><PageLayout title={title}>{children}</PageLayout></div>; }
export function TeacherLink({ to, children }: { to: string; children: ReactNode }) { return <Anchor component={Link} to={to} c="var(--cr-link)" className="teacher-link">{children}</Anchor>; }
export function TeacherStatus({ approved }: { approved: boolean }) { return <span className={`cr-status cr-status-${approved ? 'accepted' : 'in-progress'}`}>{approved ? 'Допущен' : 'Ждёт допуска'}</span>; }
