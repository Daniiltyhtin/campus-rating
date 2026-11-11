import { Button } from '@mantine/core';
import { cleanup, render } from '@testing-library/react';
import { vi } from 'vitest';
import { CampusThemeProvider } from './CampusThemeProvider';

afterEach(() => { cleanup(); localStorage.clear(); vi.restoreAllMocks(); });
it('changes a default button from disabled to enabled without conflicting background styles', () => {
  const error = vi.spyOn(console, 'error').mockImplementation(() => {});
  const view = render(<CampusThemeProvider><Button variant="default" disabled>Показать ещё</Button></CampusThemeProvider>);
  view.rerender(<CampusThemeProvider><Button variant="default">Показать ещё</Button></CampusThemeProvider>);
  expect(error.mock.calls.filter((args) => args.some((arg) => String(arg).includes('style property')))).toEqual([]);
});
