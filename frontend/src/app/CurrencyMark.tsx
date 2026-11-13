import { Text, Tooltip } from '@mantine/core';

export type CurrencyCode = 'AR' | 'BUA' | 'BOA';

const currencies = {
  AR: { short: 'АР', full: 'Академический рейтинг: от 0 до 1000, уменьшается штрафами за просрочки, растёт начислением семестра',
    icon: 'M3 9l9-4 9 4-9 4zM7 11v4c0 1.5 2.5 3 5 3s5-1.5 5-3v-4M21 9v5' },
  BUA: { short: 'БУА', full: 'Баллы учебной активности: начисляются за учебные мероприятия и не уменьшаются',
    icon: 'M4 5h6a2 2 0 0 1 2 2v12a2 2 0 0 0-2-2H4zM20 5h-6a2 2 0 0 0-2 2v12a2 2 0 0 1 2-2h6z' },
  BOA: { short: 'БОА', full: 'Баллы общественной активности: начисляются за общественные мероприятия и не уменьшаются',
    icon: 'M12 20s-7-4.4-7-9.5A3.8 3.8 0 0 1 12 8a3.8 3.8 0 0 1 7 2.5C19 15.6 12 20 12 20z' },
} as const;

export function CurrencyIcon({ currency }: { currency: CurrencyCode }) {
  return <svg className={`cr-currency-icon cr-${currency.toLowerCase()}`} viewBox="0 0 24 24" width="16" height="16" aria-hidden="true" fill="none"
    stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d={currencies[currency].icon} /></svg>;
}

export function CurrencyMark({ currency }: { currency: CurrencyCode }) {
  const { short, full } = currencies[currency];
  return <Tooltip label={full} multiline w={260} withArrow events={{ hover: true, focus: true, touch: true }}>
    <abbr className="cr-currency-mark" tabIndex={0} aria-label={`${short} — ${full}`}><CurrencyIcon currency={currency} />{short}</abbr>
  </Tooltip>;
}

export function CurrencyLegend() {
  return <Text className="cr-caption cr-currency-legend">
    {(['AR', 'BUA', 'BOA'] as const).map((currency) => <span key={currency}><CurrencyIcon currency={currency} />{currencies[currency].short} — {
      currency === 'AR' ? 'академический рейтинг' : currency === 'BUA' ? 'баллы учебной активности' : 'баллы общественной активности'}</span>)}
  </Text>;
}
