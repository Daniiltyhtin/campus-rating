import { useEffect } from 'react';
import { act, cleanup, renderHook } from '@testing-library/react';
import { LOADER_DELAY_MS, LOADER_MIN_VISIBLE_MS, useLoaderTiming } from './loaderTiming';

beforeEach(() => vi.useFakeTimers());
afterEach(() => { cleanup(); vi.useRealTimers(); });

const advance = async (ms: number) => { await act(async () => { await vi.advanceTimersByTimeAsync(ms); }); };

it('Covers AE31, AE33: timing constants are 300 and 400 ms', () => {
  expect(LOADER_DELAY_MS).toBe(300);
  expect(LOADER_MIN_VISIBLE_MS).toBe(400);
});

it('Covers AE31: idle permits results without an indicator', () => {
  const { result } = renderHook(() => useLoaderTiming(false));
  expect(result.current).toEqual({ indicatorVisible: false, resultReady: true });
});

it.each([100, 299, 300])('Covers AE31: completion at %i ms never displays an indicator', async (duration) => {
  const visibleStates: boolean[] = [];
  const { result, rerender } = renderHook(({ active }) => {
    const timing = useLoaderTiming(active);
    useEffect(() => { visibleStates.push(timing.indicatorVisible); }, [timing.indicatorVisible]);
    return timing;
  }, { initialProps: { active: true } });
  expect(result.current).toEqual({ indicatorVisible: false, resultReady: false });
  await advance(duration - 1);
  expect(result.current.indicatorVisible).toBe(false);
  act(() => { vi.advanceTimersByTime(1); rerender({ active: false }); });
  expect(result.current).toEqual({ indicatorVisible: false, resultReady: true });
  await advance(1000);
  expect(result.current).toEqual({ indicatorVisible: false, resultReady: true });
  expect(visibleStates).not.toContain(true);
});

it('Covers AE32: activity lasting 301 ms displays the indicator at 300 ms', async () => {
  const { result, rerender } = renderHook(({ active }) => useLoaderTiming(active), { initialProps: { active: true } });
  await advance(299);
  expect(result.current.indicatorVisible).toBe(false);
  await advance(1);
  expect(result.current).toEqual({ indicatorVisible: true, resultReady: false });
  await advance(1);
  rerender({ active: false });
  expect(result.current).toEqual({ indicatorVisible: true, resultReady: false });
  await advance(399);
  expect(result.current).toEqual({ indicatorVisible: false, resultReady: true });
});

it.each([320, 500])('Covers AE33, AE34: completion at %i ms holds the result until 700 ms', async (duration) => {
  const { result, rerender } = renderHook(({ active }) => useLoaderTiming(active), { initialProps: { active: true } });
  await advance(300);
  expect(result.current.indicatorVisible).toBe(true);
  await advance(duration - 300);
  rerender({ active: false });
  await advance(699 - duration);
  expect(result.current).toEqual({ indicatorVisible: true, resultReady: false });
  await advance(1);
  expect(result.current).toEqual({ indicatorVisible: false, resultReady: true });
});

it('Covers AE32: completion at 2 seconds publishes immediately', async () => {
  const { result, rerender } = renderHook(({ active }) => useLoaderTiming(active), { initialProps: { active: true } });
  await advance(2000);
  expect(result.current).toEqual({ indicatorVisible: true, resultReady: false });
  rerender({ active: false });
  expect(result.current).toEqual({ indicatorVisible: false, resultReady: true });
});

it('Covers AE33: activity restarted during Holding begins a new Waiting after the held result', async () => {
  const { result, rerender } = renderHook(({ active }) => useLoaderTiming(active), { initialProps: { active: true } });
  await advance(320);
  rerender({ active: false });
  await advance(180);
  rerender({ active: true });
  await advance(199);
  expect(result.current).toEqual({ indicatorVisible: true, resultReady: false });
  await advance(1);
  expect(result.current.indicatorVisible).toBe(false);
  await advance(299);
  expect(result.current).toEqual({ indicatorVisible: false, resultReady: false });
  await advance(1);
  expect(result.current).toEqual({ indicatorVisible: true, resultReady: false });
  rerender({ active: false });
  await advance(400);
  expect(result.current).toEqual({ indicatorVisible: false, resultReady: true });
});
