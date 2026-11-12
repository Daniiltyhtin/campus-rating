import { Alert, Button, Container, Group, Stack, Text, Title } from '@mantine/core';
import { BrandLoader } from '../../theme/Brand';
import type { FormEventHandler, ReactNode } from 'react';

export type LoadState = { status: 'ready' } | { status: 'loading' } | { status: 'empty'; message: string }
  | { status: 'error'; message: string; retry: () => void };

export function AsyncContent({ state, children }: { state: LoadState; children: ReactNode }) {
  switch (state.status) {
    case 'loading': return <BrandLoader />;
    case 'empty': return <Text className="cr-caption">{state.message}</Text>;
    case 'error': return <Alert role="alert" title="Не удалось загрузить данные"><Stack><Text>{state.message}</Text><Button variant="default" onClick={state.retry}>Повторить</Button></Stack></Alert>;
    case 'ready': return children;
  }
}

export function PageLayout({ title, description, actions, children }: {
  title: string; description?: string; actions?: ReactNode; children: ReactNode;
}) {
  return <Container size="lg" className="cr-page"><Stack gap="xl">
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

export function TeacherTableLayout<T>({ caption, rows, columns, keyForRow, state, isSelected }: {
  caption: string; rows: readonly T[]; columns: { key: string; title: string; render: (row: T) => ReactNode }[];
  keyForRow: (row: T) => number | string; state: LoadState;
  isSelected?: (row: T) => boolean;
}) {
  return <AsyncContent state={state}><table className="cr-table" role="table"><caption>{caption}</caption>
    <thead role="rowgroup"><tr role="row">{columns.map((column) => <th scope="col" role="columnheader" key={column.key}>{column.title}</th>)}</tr></thead>
    <tbody role="rowgroup">{rows.map((row) => <tr role="row" key={keyForRow(row)} data-selected={isSelected?.(row) || undefined}>{columns.map((column) =>
      <td role="cell" key={column.key} data-label={column.title}>{column.render(row)}</td>)}</tr>)}</tbody>
  </table></AsyncContent>;
}
