import { useMediaQuery } from '@mantine/hooks';
import mark from '../assets/brand/logo-mark.svg?raw';
import loader from '../assets/brand/loader.svg?raw';
import wait from '../assets/brand/loader-wait.svg?raw';
import { BRAND_CYCLE_MS } from './useBrandCycle';

function decorative(svg: string) {
  return svg.replace(/role="img" aria-label="[^"]*"/, 'aria-hidden="true" focusable="false"');
}

const markViewBox = /viewBox="([^"]*)"/.exec(mark)?.[1] ?? '';
const cycle = decorative(loader)
  .replace(/viewBox="[^"]*"/, `viewBox="${markViewBox}" overflow="visible"`)
  .replaceAll('dur="3.6s"', `dur="${BRAND_CYCLE_MS / 1000}s"`)
  .replaceAll('repeatCount="indefinite"', 'repeatCount="1"')
  .replaceAll('720 13.07 15', '360 13.07 15')
  .replace('keyTimes="0;0.16;0.28;0.72;0.84;1"', 'keyTimes="0;0.16;0.28;0.72;0.9;1"')
  .replaceAll('keyTimes="0;0.06;0.18;0.82;0.94;1"', 'keyTimes="0;0.06;0.18;0.8;0.98;1"');

export function BrandMark({ playing = null }: { playing?: number | null }) {
  const svg = playing === null ? mark : cycle;
  return <span key={playing ?? 'static'} className="cr-brand-mark" data-playing={playing !== null || undefined}
    dangerouslySetInnerHTML={{ __html: decorative(svg) }} />;
}

export function BrandLoader({ session = false }: { session?: boolean }) {
  const reduced = useMediaQuery('(prefers-reduced-motion: reduce)', false, { getInitialValueInEffect: false });
  const svg = reduced ? mark : session ? loader : wait;
  const size = session ? '6rem' : '3rem';
  return <div role="status" aria-label="Загрузка" className={session ? 'cr-session-loading' : 'cr-content-loading'}>
    <span className="cr-loader" style={{ width: size, height: size }} dangerouslySetInnerHTML={{ __html: decorative(svg) }} />
  </div>;
}
