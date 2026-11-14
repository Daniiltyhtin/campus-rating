import { useEffect, useState } from 'react';

export const LOADER_DELAY_MS = 300;
export const LOADER_MIN_VISIBLE_MS = 400;

type TimingState = { phase: 'idle' | 'waiting' | 'visible' | 'holding'; active: boolean; startedAt: number };

export function loaderResultDeadline(startedAt: number, finishedAt: number): number {
  return finishedAt <= startedAt + LOADER_DELAY_MS ? finishedAt
    : Math.max(finishedAt, startedAt + LOADER_DELAY_MS + LOADER_MIN_VISIBLE_MS);
}

function transition(state: TimingState, active: boolean, now: number): TimingState {
  const deadline = state.startedAt + LOADER_DELAY_MS + LOADER_MIN_VISIBLE_MS;
  if (state.phase === 'holding') {
    if (now < deadline) return active === state.active ? state : { ...state, active };
    return { phase: active ? 'waiting' : 'idle', active, startedAt: now };
  }
  if (state.phase === 'idle') return active ? { phase: 'waiting', active, startedAt: now } : state;
  if (!active) return { ...state, active, phase: loaderResultDeadline(state.startedAt, now) > now ? 'holding' : 'idle' };
  if (state.phase === 'waiting' && now >= state.startedAt + LOADER_DELAY_MS) return { ...state, phase: 'visible' };
  return state;
}

export function useLoaderTiming(active: boolean): { indicatorVisible: boolean; resultReady: boolean } {
  const [state, setState] = useState<TimingState>(() => ({ phase: active ? 'waiting' : 'idle', active, startedAt: Date.now() }));
  if (state.active !== active) {
    setState((previous) => transition(previous, active, Date.now()));
  }
  useEffect(() => {
    const delay = state.phase === 'waiting' ? LOADER_DELAY_MS
      : state.phase === 'holding' ? LOADER_DELAY_MS + LOADER_MIN_VISIBLE_MS : null;
    if (delay === null) return;
    const deadline = state.startedAt + delay;
    const tick = () => {
      const remaining = deadline - Date.now();
      if (remaining > 0) timer = setTimeout(tick, remaining);
      else setState((previous) => transition(previous, previous.active, Date.now()));
    };
    let timer = setTimeout(tick, Math.max(0, deadline - Date.now()));
    return () => clearTimeout(timer);
  }, [state]);
  return { indicatorVisible: state.phase === 'visible' || state.phase === 'holding', resultReady: state.phase === 'idle' };
}

export async function waitForLoaderResult(startedAt: number): Promise<void> {
  const now = Date.now();
  const remaining = loaderResultDeadline(startedAt, now) - now;
  if (remaining > 0) await new Promise<void>((resolve) => setTimeout(resolve, remaining));
}
