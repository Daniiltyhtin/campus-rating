import { Alert, Button, Container, Group, Stack, Text, Title } from '@mantine/core';
import { BrandLoader } from '../../theme/Brand';
import { useState, type FormEventHandler, type ReactNode } from 'react';
import { useLoaderTiming } from '../loaderTiming';

export type LoadState = { status: 'ready' } | { status: 'loading' } | { status: 'empty'; message: string }
  | { status: 'error'; message: string; retry: () => void };

export function AsyncContent({ state, children }: { state: LoadState; children: ReactNode }) {
  const timing = useLoaderTiming(state.status === 'loading');
  const content = state.status === 'ready' ? children
    : state.status === 'empty' ? <Text className="cr-caption">{state.message}</Text>
      : state.status === 'error' ? <Alert role="alert" title="Не удалось загрузить данные"><Stack><Text>{state.message}</Text><Button variant="default" onClick={state.retry}>Повторить</Button></Stack></Alert> : null;
  const [lastReady, setLastReady] = useState(() => ({ state, children, content: timing.resultReady ? content : null }));
  if (state.status !== 'loading' && (lastReady.state !== state || lastReady.children !== children)) setLastReady({ state, children, content });
  return timing.indicatorVisible ? <BrandLoader /> : timing.resultReady ? content : lastReady.content;
}

export function PageLayout({ title, description, actions, children, wide = false }: {
  title: string; description?: string; actions?: ReactNode; children: ReactNode; wide?: boolean;
}) {
  return <Container size={wide ? '100rem' : 'lg'} className="cr-page"><Stack gap="xl">
    <header className="cr-masthead"><Group justify="space-between" align="start"><Stack gap="xs">
      <Title order={1}>{title}</Title>
      {description && <Text className="cr-caption">{description}</Text>}
    </Stack>{actions}</Group></header>{children}
  </Stack></Container>;
}

export function ListLayout<T>({ title, items, keyForItem, renderItem, state }: {
  title: string; items: readonly T[]; keyForItem: (item: T) => string | number;
  renderItem: (item: T) => ReactNode; state: LoadState;
}) {
  return <section><Title order={2} mb="md">{title}</Title><AsyncContent state={state}>
    <Stack gap={0}>{items.map((item) => <article className="cr-article" key={keyForItem(item)}>{renderItem(item)}</article>)}</Stack>
  </AsyncContent></section>;
}

export function CardLayout({ title, children }: { title: string; children: ReactNode }) {
  return <section className="cr-card"><Title order={2} mb="md">{title}</Title>{children}</section>;
}

export function FormLayout({ title, children, onSubmit, noValidate = false, maxWidth }: { title: string; children: ReactNode; onSubmit: FormEventHandler<HTMLFormElement>; noValidate?: boolean; maxWidth?: string }) {
  return <section className="cr-card" style={{ maxWidth }}><Title order={2} mb="md">{title}</Title><form noValidate={noValidate} onSubmit={onSubmit}><Stack>{children}</Stack></form></section>;
}

export type TableSort = { direction: 'asc' | 'desc' | null; onSort: () => void };
const sortArrows = { asc: 'M12 19V5M6 11l6-6 6 6', desc: 'M12 5v14M6 13l6 6 6-6', none: 'M8 9l4-4 4 4M8 15l4 4 4-4' };
function SortHeader({ title, sort }: { title: string; sort: TableSort }) {
  return <button type="button" className="cr-sort-button" onClick={sort.onSort}>{title}
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"
      aria-hidden="true" data-direction={sort.direction ?? 'none'}><path d={sortArrows[sort.direction ?? 'none']} /></svg></button>;
}
export function TeacherTableLayout<T>({ caption, rows, columns, keyForRow, state, isSelected }: {
  caption: string; rows: readonly T[]; columns: { key: string; title: string; render: (row: T) => ReactNode; sort?: TableSort; className?: string }[];
  keyForRow: (row: T) => number | string; state: LoadState;
  isSelected?: (row: T) => boolean;
}) {
  return <AsyncContent state={state}><table className="cr-table" role="table"><caption>{caption}</caption>
    <thead role="rowgroup"><tr role="row">{columns.map((column) => <th scope="col" role="columnheader" key={column.key} className={column.className}
      aria-sort={column.sort ? column.sort.direction === 'asc' ? 'ascending' : column.sort.direction === 'desc' ? 'descending' : 'none' : undefined}>
      {column.sort ? <SortHeader title={column.title} sort={column.sort} /> : column.title}</th>)}</tr></thead>
    <tbody role="rowgroup">{rows.map((row) => <tr role="row" key={keyForRow(row)} data-selected={isSelected?.(row) || undefined}>{columns.map((column) =>
      <td role="cell" key={column.key} data-label={column.title} className={column.className}>{column.render(row)}</td>)}</tr>)}</tbody>
  </table></AsyncContent>;
}
