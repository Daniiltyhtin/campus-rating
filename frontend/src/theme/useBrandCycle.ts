import { useMediaQuery } from '@mantine/hooks';
import { useCallback, useEffect, useRef, useState } from 'react';

export const BRAND_CYCLE_MS = 1600;

export function useBrandCycle(): { playing: number | null; play: () => void } {
  const reduced = useMediaQuery('(prefers-reduced-motion: reduce)', false, { getInitialValueInEffect: false });
  const [playing, setPlaying] = useState<number | null>(null);
  const counter = useRef(0);
  useEffect(() => {
    if (playing === null) return;
    const timer = setTimeout(() => setPlaying(null), BRAND_CYCLE_MS);
    return () => clearTimeout(timer);
  }, [playing]);
  const play = useCallback(() => {
    if (reduced) return;
    setPlaying((current) => current ?? ++counter.current);
  }, [reduced]);
  return { playing, play };
}
