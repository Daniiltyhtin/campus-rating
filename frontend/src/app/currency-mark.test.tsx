import { readFileSync } from 'node:fs';
import { MantineProvider } from '@mantine/core';
import { render, screen } from '@testing-library/react';
import { CurrencyMark } from './CurrencyMark';

const styles = readFileSync('src/theme/theme.css', 'utf8');

it('currency mark is focusable, names the full currency and has a touch-size hit area', () => {
  render(<MantineProvider env="test"><CurrencyMark currency="BUA" /></MantineProvider>);
  const mark = screen.getByLabelText(/^БУА — Баллы учебной активности/);
  expect(mark).toHaveAttribute('tabindex', '0');
  expect(mark).toHaveClass('cr-currency-mark');
  expect(styles).toMatch(/\.cr-currency-mark\s*\{[^}]*position:\s*relative/);
  expect(styles).toMatch(/\.cr-currency-mark::before\s*\{[^}]*width:\s*max\(100%,\s*var\(--cr-touch-target\)\)[^}]*height:\s*max\(100%,\s*var\(--cr-touch-target\)\)/);
});
