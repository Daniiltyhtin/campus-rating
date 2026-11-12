import { createContext, useContext } from 'react';

export type CampusAppearance = 'light' | 'dark' | 'system';
export const AppearanceContext = createContext<{ appearance: CampusAppearance; setAppearance: (value: CampusAppearance) => void } | null>(null);

export function useCampusAppearance() {
  const value = useContext(AppearanceContext);
  if (!value) throw new Error('CampusThemeProvider is required');
  return value;
}
