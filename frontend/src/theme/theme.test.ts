import { describe, expect, it } from 'vitest';
import { palettes, theme } from './theme';

function luminance(color: string) {
  const [r = 0, g = 0, b = 0] = color.slice(1).match(/../g)!.map((value) => {
    const channel = parseInt(value, 16) / 255;
    return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

describe('Редакция theme', () => {
  it('uses the selected typefaces and blue primary color', () => {
    expect(theme.fontFamily).toContain('PT Sans');
    expect(theme.headings?.fontFamily).toContain('Literata');
    expect(theme.colors?.brand?.[6]).toBe('#2450c4');
  });
  it.each(['light', 'dark', 'contrast'] as const)('%s text/background pairs meet their contrast target', (scheme) => {
    const p = palettes[scheme];
    for (const [text, background] of [[p.text, p.body], [p.text, p.surface], [p.text, p.selection],
      ...[p.muted, p.link, p.ar, p.bua, p.boa].flatMap((color) => [[color, p.body], [color, p.surface]]),
      [p.link, p.selection], [p.muted, p.selection],
      [p.overdue, p.overdueBg], [p.accepted, p.acceptedBg], [p.submitted, p.submittedBg],
      [p.progress, p.progressBg], [p.buttonText, p.button]]) {
      const a = luminance(text!);
      const b = luminance(background!);
      expect((Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05), `${text} on ${background}`).toBeGreaterThanOrEqual(scheme === 'contrast' ? 7 : 4.5);
    }
  });
});
