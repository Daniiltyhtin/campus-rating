import '@testing-library/jest-dom/vitest';

const DESKTOP_WIDTH = 1280;
let viewportWidth = DESKTOP_WIDTH;

export function setViewportWidth(width: number) {
  viewportWidth = width;
}

function matches(query: string) {
  const minWidth = /min-width:\s*([\d.]+)(px|em)/.exec(query);
  if (!minWidth) return false;
  const value = Number(minWidth[1]);
  const pixels = minWidth[2] === 'em' ? value * 16 : value;
  return viewportWidth >= pixels;
}

if (typeof window !== 'undefined') {
window.matchMedia = (query: string): MediaQueryList => ({
  matches: matches(query),
  media: query,
  onchange: null,
  addListener: () => {},
  removeListener: () => {},
  addEventListener: () => {},
  removeEventListener: () => {},
  dispatchEvent: () => false,
});

window.ResizeObserver = class {
  observe() {}
  unobserve() {}
  disconnect() {}
};

Element.prototype.scrollIntoView = () => {};
}

afterEach(() => {
  setViewportWidth(DESKTOP_WIDTH);
});
