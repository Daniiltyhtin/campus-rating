import { notifyManager } from '@tanstack/react-query';
import { act, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http } from 'msw';
import { server } from '../mocks/server';

export function setupTiming() { notifyManager.setScheduler(queueMicrotask); }

const realTimeout = globalThis.setTimeout;
const releases = new Set<() => void>();
const disposals = new Set<() => void>();

export function fakeLoaderTime() {
  vi.useFakeTimers({ toFake: ['Date', 'performance', 'setTimeout', 'clearTimeout'] });
  const fakeTimeout = globalThis.setTimeout;
  vi.spyOn(globalThis, 'setTimeout').mockImplementation(((callback: TimerHandler, ms?: number, ...args: unknown[]) => {
    if (!ms) return realTimeout(callback, ms, ...args);
    return fakeTimeout(callback, ms, ...args);
  }) as typeof setTimeout);
}

export function timingUser() {
  const user = userEvent.setup({ delay: null });
  const interact = async (action: () => Promise<void>) => {
    const pending = action();
    await flushNetwork();
    await pending;
    await flushNetwork();
  };
  return {
    click: (...args: Parameters<typeof user.click>) => interact(() => user.click(...args)),
    keyboard: (...args: Parameters<typeof user.keyboard>) => interact(() => user.keyboard(...args)),
    selectOptions: (...args: Parameters<typeof user.selectOptions>) => interact(() => user.selectOptions(...args)),
  };
}

export function disposeTimingView<T extends { router: { dispose: () => void }; queryClient: { clear: () => void } }>(view: T) {
  disposals.add(() => { view.router.dispose(); view.queryClient.clear(); });
  return view;
}

export async function flushNetwork() {
  for (let turn = 0; turn < 12; turn++) {
    await act(async () => { await new Promise<void>((resolve) => realTimeout(resolve, 0)); });
    if (vi.isFakeTimers()) await act(async () => { await vi.advanceTimersByTimeAsync(0); });
  }
}

export async function untilNetwork(predicate: () => boolean) {
  for (let turn = 0; turn < 100; turn++) {
    if (predicate()) return;
    await flushNetwork();
  }
  expect(predicate(), `MSW request or initial UI did not settle: ${document.querySelector('h1')?.textContent}`).toBe(true);
}

export async function advance(ms: number) {
  await act(async () => { await vi.advanceTimersByTimeAsync(ms); });
  await flushNetwork();
}

export function controlledRequest(method: 'get' | 'post' | 'put', path: string, response: () => Response) {
  let release!: () => void;
  const pending = new Promise<void>((resolve) => { release = resolve; });
  releases.add(release);
  const state = { calls: 0, returned: false, release: async () => {
    release();
    await flushNetwork();
    expect(state.returned).toBe(true);
  } };
  server.use(http[method](path, async () => {
    state.calls++;
    await pending;
    state.returned = true;
    return response();
  }));
  return state;
}

export async function cleanupTiming() {
  cleanup();
  for (const dispose of disposals) dispose();
  disposals.clear();
  for (const release of releases) release();
  releases.clear();
  vi.restoreAllMocks();
  vi.useRealTimers();
  await flushNetwork();
  vi.unstubAllGlobals();
  notifyManager.setScheduler((callback) => { setTimeout(callback, 0); });
  server.resetHandlers();
  localStorage.clear();
  document.cookie = 'XSRF-TOKEN=; Max-Age=0; Path=/';
}
