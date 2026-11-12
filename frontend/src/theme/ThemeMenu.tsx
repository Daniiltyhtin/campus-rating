import { ActionIcon, Menu } from '@mantine/core';
import { useCampusAppearance } from './useCampusAppearance';

export function ThemeOptions() {
  const { appearance, setAppearance } = useCampusAppearance();
  return <>{([
    ['light', 'Светлая'], ['dark', 'Тёмная'], ['contrast', 'Высокий контраст'],
  ] as const).map(([value, label]) => <Menu.RadioItem key={value} value={value}
    checked={appearance === value} onChange={() => setAppearance(value)} className="cr-menu-item"
    checkIcon={<span aria-hidden="true">✓</span>}>{label}</Menu.RadioItem>)}</>;
}

export function ThemeMenu() {
  return <Menu position="bottom-end" withinPortal transitionProps={{ duration: 0 }}>
    <Menu.Target><ActionIcon variant="subtle" aria-label="Тема" color="var(--cr-primary)">
      <svg viewBox="0 0 24 24" width="1.25rem" height="1.25rem" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true">
        <circle cx="12" cy="12" r="4" /><path d="M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1.5 1.5m11 11L19 19M5 19l1.5-1.5m11-11L19 5" />
      </svg>
    </ActionIcon></Menu.Target>
    <Menu.Dropdown><ThemeOptions /></Menu.Dropdown>
  </Menu>;
}
