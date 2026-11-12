import { ActionIcon, Menu } from '@mantine/core';
import { useEffect, useId, useRef, useState } from 'react';
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

const options: readonly (readonly [CampusAppearance, string, ReactNode])[] = [
  ['light', 'Светлая', sun], ['dark', 'Тёмная', moon], ['system', 'Системная', monitor],
];

export function ThemeOptions() {
  const { appearance, setAppearance } = useCampusAppearance();
  return <>{options.map(([value, label]) => <Menu.RadioItem key={value} value={value}
    checked={appearance === value} onChange={() => setAppearance(value)} className="cr-menu-item"
    checkIcon={<span aria-hidden="true">✓</span>}>{label}</Menu.RadioItem>)}</>;
}

export function ThemeMenu() {
  const { appearance, setAppearance } = useCampusAppearance();
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  const toggle = useRef<HTMLButtonElement>(null);
  const id = useId();
  useEffect(() => {
    if (!open) return;
    const outside = (event: PointerEvent) => { if (!root.current?.contains(event.target as Node)) setOpen(false); };
    const escape = (event: KeyboardEvent) => { if (event.key === 'Escape') { setOpen(false); toggle.current?.focus(); } };
    document.addEventListener('pointerdown', outside);
    document.addEventListener('keydown', escape);
    return () => { document.removeEventListener('pointerdown', outside); document.removeEventListener('keydown', escape); };
  }, [open]);
  return <div ref={root} className="cr-theme-switch" data-open={open || undefined}>
    <div id={id} className="cr-theme-switch-options" role="group" aria-label="Выбор темы" inert={!open} aria-hidden={!open}>
      {options.map(([value, label, icon]) => <ActionIcon key={value} variant="subtle" color="var(--cr-primary)" aria-label={label}
        aria-pressed={appearance === value} className="cr-theme-switch-option"
        onClick={() => { setAppearance(value); setOpen(false); toggle.current?.focus(); }}>{icon}</ActionIcon>)}
    </div>
    <ActionIcon ref={toggle} variant="subtle" aria-label="Тема" color="var(--cr-primary)" aria-expanded={open} aria-controls={id}
      onClick={() => setOpen((value) => !value)}>{icons[appearance]}</ActionIcon>
  </div>;
}
