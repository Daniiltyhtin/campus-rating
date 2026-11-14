import { Button, type ButtonProps } from '@mantine/core';
import type { ComponentPropsWithoutRef } from 'react';
import { useLoaderTiming } from './loaderTiming';

export type ActionButtonProps = ButtonProps & ComponentPropsWithoutRef<'button'> & { pending?: boolean };

export function ActionButton({ pending = false, onClick, ...props }: ActionButtonProps) {
  const timing = useLoaderTiming(pending);
  return <Button {...props} loading={timing.indicatorVisible} onClick={(event) => {
    if (pending || !timing.resultReady) { event.preventDefault(); return; }
    onClick?.(event);
  }} />;
}
