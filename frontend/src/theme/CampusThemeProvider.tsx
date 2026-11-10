import { MantineProvider, createTheme } from '@mantine/core';
import { useLocalStorage } from '@mantine/hooks';
import type { ReactNode } from 'react';
import { contrastVariablesResolver, cssVariablesResolver, theme } from './theme';
import { AppearanceContext } from './useCampusAppearance';
import type { CampusAppearance } from './useCampusAppearance';

const contrastTheme = createTheme({ ...theme, fontSizes: { xs: '0.8125rem', sm: '0.9375rem', md: '1.0625rem', lg: '1.1875rem', xl: '1.375rem' } });

export function CampusThemeProvider({ children }: { children: ReactNode }) {
  const [stored, setStored] = useLocalStorage<CampusAppearance>({ key: 'campus-appearance', defaultValue: 'light', getInitialValueInEffect: false });
  const appearance = stored === 'dark' || stored === 'contrast' ? stored : 'light';
  return <AppearanceContext.Provider value={{ appearance, setAppearance: setStored }}>
    <MantineProvider theme={appearance === 'contrast' ? contrastTheme : theme}
      forceColorScheme={appearance === 'dark' ? 'dark' : 'light'}
      cssVariablesResolver={appearance === 'contrast' ? contrastVariablesResolver : cssVariablesResolver}>
      {children}
    </MantineProvider>
  </AppearanceContext.Provider>;
}
