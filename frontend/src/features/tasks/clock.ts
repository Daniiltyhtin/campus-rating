export interface Clock {
  now(): Date;
  monotonicNow(): number;
}

export const taskClock: Clock = {
  now: () => new Date(),
  monotonicNow: () => performance.now(),
};
