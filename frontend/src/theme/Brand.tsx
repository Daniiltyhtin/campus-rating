import { useMediaQuery } from '@mantine/hooks';
import mark from '../assets/brand/logo-mark.svg?raw';
import loader from '../assets/brand/loader.svg?raw';
import wait from '../assets/brand/loader-wait.svg?raw';

function decorative(svg: string) {
  return svg.replace(/role="img" aria-label="[^"]*"/, 'aria-hidden="true" focusable="false"');
}

export function BrandMark() {
  return <span className="cr-brand-mark" dangerouslySetInnerHTML={{ __html: decorative(mark) }} />;
}

export function BrandLoader({ session = false }: { session?: boolean }) {
  const reduced = useMediaQuery('(prefers-reduced-motion: reduce)', false, { getInitialValueInEffect: false });
  const svg = reduced ? mark : session ? loader : wait;
  const size = session ? '6rem' : '3rem';
  return <div role="status" aria-label="Загрузка" className={session ? 'cr-session-loading' : 'cr-content-loading'}>
    <span className="cr-loader" style={{ width: size, height: size }} dangerouslySetInnerHTML={{ __html: decorative(svg) }} />
  </div>;
}
