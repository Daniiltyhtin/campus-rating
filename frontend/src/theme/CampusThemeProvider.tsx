import { MantineProvider } from '@mantine/core';
import { useColorScheme, useLocalStorage } from '@mantine/hooks';
import type { ReactNode } from 'react';
import { useEffect } from 'react';
import { cssVariablesResolver, theme } from './theme';
import { AppearanceContext } from './useCampusAppearance';
import type { CampusAppearance } from './useCampusAppearance';

export function CampusThemeProvider({ children }: { children: ReactNode }) {
  const [stored, setStored] = useLocalStorage<CampusAppearance>({ key: 'campus-appearance', defaultValue: 'light', getInitialValueInEffect: false });
  const system = useColorScheme('light', { getInitialValueInEffect: false });
  const appearance = stored === 'dark' || stored === 'system' ? stored : 'light';
  const scheme = appearance === 'system' ? system : appearance;
  useEffect(() => { document.documentElement.dataset.theme = scheme; }, [scheme]);
  return <AppearanceContext.Provider value={{ appearance, setAppearance: setStored }}>
    <MantineProvider theme={theme} forceColorScheme={scheme} cssVariablesResolver={cssVariablesResolver}>
      {children}
    </MantineProvider>
  </AppearanceContext.Provider>;
}
