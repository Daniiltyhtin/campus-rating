import { ActionIcon, Tooltip } from '@mantine/core';
import type { ReactNode } from 'react';
import type { CampusAppearance } from './useCampusAppearance';
import { useCampusAppearance } from './useCampusAppearance';

function Icon({ children }: { children: ReactNode }) {
  return <svg viewBox="0 0 24 24" width="1.25rem" height="1.25rem" fill="none" stroke="currentColor" strokeWidth="1.5"
    strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{children}</svg>;
}

const sun = <Icon><circle cx="12" cy="12" r="4" /><path d="M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1.5 1.5m11 11L19 19M5 19l1.5-1.5m11-11L19 5" /></Icon>;
const moon = <Icon><path d="M20 14.5A8 8 0 0 1 9.5 4 8 8 0 1 0 20 14.5Z" /></Icon>;
const monitor = <Icon><rect x="3" y="4" width="18" height="12" rx="1.5" /><path d="M8 20h8M12 16v4" /></Icon>;

const icons: Record<CampusAppearance, ReactNode> = { light: sun, dark: moon, system: monitor };

const labels: Record<CampusAppearance, string> = { light: 'Светлая тема', dark: 'Тёмная тема', system: 'Системная тема' };
const next: Record<CampusAppearance, CampusAppearance> = { light: 'dark', dark: 'system', system: 'light' };

export function ThemeMenu() {
  const { appearance, setAppearance } = useCampusAppearance();
  return <Tooltip label={labels[appearance]} withArrow events={{ hover: true, focus: true, touch: false }}>
    <ActionIcon variant="subtle" color="var(--cr-primary)" className="cr-theme-toggle" aria-label={`Тема: ${labels[appearance]}`}
      onClick={() => setAppearance(next[appearance])}>{icons[appearance]}</ActionIcon>
  </Tooltip>;
}
