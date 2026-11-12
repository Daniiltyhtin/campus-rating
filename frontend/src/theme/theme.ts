import { ActionIcon, Button, Checkbox, createTheme, Input, NavLink, SegmentedControl, Select, Tabs, Textarea, TextInput } from '@mantine/core';
import type { CSSVariablesResolver, MantineColorsTuple } from '@mantine/core';

const blue: MantineColorsTuple = ['#e8eefc', '#d6e2fc', '#bfd1fb', '#9bbcff', '#6b94ee', '#4977db', '#2450c4', '#1d43a8', '#18378a', '#152d6e'];

export const palettes = {
  light: { body: '#f5f6f8', surface: '#ffffff', text: '#161b22', muted: '#57606a', rule: '#d6dbe1', link: '#2450c4',
    button: '#2450c4', buttonText: '#ffffff', selection: '#eef0f3', field: '#7d8590', track: '#e1e5ea', primarySoft: '#e8eefc',
    overdue: '#b42318', overdueBg: '#fdecea', accepted: '#18753c', acceptedBg: '#e6f4ea',
    submitted: '#8a4b00', submittedBg: '#fff3dc', progress: '#1d4fb8', progressBg: '#e8effc',
    ar: '#2450c4', bua: '#0b6e69', boa: '#a3480a' },
  dark: { body: '#0f1419', surface: '#161c23', text: '#e6edf3', muted: '#9da7b3', rule: '#2d3640', link: '#8fb1ff',
    button: '#7aa2ff', buttonText: '#0b1530', selection: '#1e252e', field: '#6b7685', track: '#2d3640', primarySoft: '#1a2540',
    overdue: '#ff9b8f', overdueBg: '#3a1d1b', accepted: '#7fd19b', acceptedBg: '#15301f',
    submitted: '#f0c27a', submittedBg: '#362a14', progress: '#a8c1ff', progressBg: '#1c2742',
    ar: '#8fb1ff', bua: '#5fd0c6', boa: '#f0a46b' },
  contrast: { body: '#ffffff', surface: '#ffffff', text: '#000000', muted: '#333333', rule: '#595959', link: '#0033a0',
    button: '#0033a0', buttonText: '#ffffff', selection: '#f0f0f0', field: '#000000', track: '#c4c4c4', primarySoft: '#e5ecff',
    overdue: '#a00000', overdueBg: '#ffe5e5', accepted: '#00561e', acceptedBg: '#e0f5e5',
    submitted: '#6b3600', submittedBg: '#fff0d0', progress: '#002f8f', progressBg: '#e5ecff',
    ar: '#0033a0', bua: '#00563f', boa: '#7a3300' },
};

export const theme = createTheme({
  primaryColor: 'brand', primaryShade: 6, colors: { brand: blue },
  fontFamily: '"PT Sans", system-ui, sans-serif',
  fontSizes: { xs: '0.6875rem', sm: '0.8125rem', md: '0.9375rem', lg: '1.0625rem', xl: '1.25rem' },
  headings: { fontFamily: '"Literata", Georgia, serif', fontWeight: '600',
    sizes: { h1: { fontSize: '2.5rem', lineHeight: '1.12' }, h2: { fontSize: '1.75rem', lineHeight: '1.2' } } },
  defaultRadius: 'sm', radius: { xs: '0.25rem', sm: '0.375rem', md: '0.5rem', lg: '0.625rem', xl: '0.75rem' },
  shadows: { xs: 'none', sm: 'none', md: 'none', lg: 'none', xl: 'none' },
  components: {
    Button: Button.extend({ styles: (_theme, props) => ({ root: { minHeight: 'var(--cr-touch-target)', fontSize: 'var(--cr-body-size)',
      ...(props.disabled ? { backgroundColor: 'var(--cr-selection)', color: 'var(--cr-muted)', border: 'var(--cr-rule-width) dashed var(--cr-muted)' } : {}),
      ...(!props.disabled && (props.variant ?? 'filled') === 'filled' ? { backgroundColor: 'var(--cr-button)', color: 'var(--cr-button-text)' } : {}),
      ...(props.variant === 'default' ? { backgroundColor: 'transparent', color: 'var(--cr-text)', border: 'var(--cr-rule-width) solid var(--cr-text)' } : {}),
      ...(props.variant === 'outline' || props.variant === 'subtle' ? { color: 'var(--cr-link)', borderColor: props.variant === 'outline' ? 'var(--cr-link)' : undefined } : {}),
    } }) }),
    Input: Input.extend({ styles: (_theme, props) => ({ input: { minHeight: 'var(--cr-touch-target)', fontSize: 'var(--cr-body-size)', background: 'var(--cr-surface)', color: 'var(--cr-text)',
      borderWidth: 'var(--cr-rule-width)', borderColor: props.error ? 'var(--cr-status-overdue)' : 'var(--cr-field)' } }) }),
    TextInput: TextInput.extend({ styles: { error: { color: 'var(--cr-status-overdue)' } } }),
    Textarea: Textarea.extend({ styles: { error: { color: 'var(--cr-status-overdue)' } } }),
    Select: Select.extend({ styles: { error: { color: 'var(--cr-status-overdue)' },
      option: { minHeight: 'var(--cr-touch-target)', display: 'flex', alignItems: 'center' } } }),
    ActionIcon: ActionIcon.extend({ styles: { root: { minWidth: 'var(--cr-touch-target)', minHeight: 'var(--cr-touch-target)' } } }),
    NavLink: NavLink.extend({ styles: { root: { minHeight: 'var(--cr-touch-target)' } } }),
    Tabs: Tabs.extend({ styles: { tab: { minHeight: 'var(--cr-touch-target)' } } }),
    SegmentedControl: SegmentedControl.extend({ styles: { root: { background: 'transparent', borderBottom: 'var(--cr-rule-width) solid var(--cr-rule)' },
      indicator: { background: 'transparent', boxShadow: 'none', borderBottom: '0.1875rem solid var(--cr-link)', borderRadius: 0 },
      label: { minHeight: 'var(--cr-touch-target)', display: 'flex', alignItems: 'center', justifyContent: 'center' } } }),
    Checkbox: Checkbox.extend({ vars: () => ({ root: { '--checkbox-size': 'var(--cr-checkbox-size)' } }),
      styles: { body: { minHeight: 'var(--cr-touch-target)', alignItems: 'center' },
      label: { minHeight: 'var(--cr-touch-target)', display: 'flex', alignItems: 'center' } } }),
  },
});

function variables(p: typeof palettes.light) {
  return { '--mantine-color-body': p.body, '--mantine-color-text': p.text, '--cr-bg': p.body,
    '--cr-surface': p.surface, '--cr-text': p.text, '--cr-muted': p.muted, '--cr-rule': p.rule, '--cr-link': p.link,
    '--cr-button': p.button, '--cr-button-text': p.buttonText, '--cr-selection': p.selection,
    '--cr-field': p.field, '--cr-track': p.track, '--cr-primary-soft': p.primarySoft,
    '--mantine-color-brand-filled': p.button, '--mantine-color-brand-filled-hover': p.button,
    '--cr-surface-2': p.selection, '--cr-primary': p.button, '--cr-on-primary': p.buttonText,
    '--cr-status-overdue': p.overdue, '--cr-status-overdue-bg': p.overdueBg,
    '--cr-status-accepted': p.accepted, '--cr-status-accepted-bg': p.acceptedBg,
    '--cr-status-submitted': p.submitted, '--cr-status-submitted-bg': p.submittedBg,
    '--cr-status-in-progress': p.progress, '--cr-status-in-progress-bg': p.progressBg,
    '--cr-currency-ar': p.ar, '--cr-currency-bua': p.bua, '--cr-currency-boa': p.boa };
}

export const cssVariablesResolver: CSSVariablesResolver = () => ({
  variables: { '--cr-touch-target': '2.75rem', '--cr-rule-width': '0.0625rem', '--cr-motion-duration': '120ms',
    '--cr-status-weight': '400', '--cr-status-border': '0rem', '--cr-status-radius': '62.4375rem', '--cr-body-size': '0.9375rem', '--cr-selected-width': '0.125rem',
    '--cr-checkbox-size': '1.125rem', '--cr-row-height': '3.25rem' },
  light: variables(palettes.light), dark: variables(palettes.dark),
});

export const contrastVariablesResolver: CSSVariablesResolver = () => ({
  variables: { '--cr-touch-target': '3.25rem', '--cr-rule-width': '0.125rem', '--cr-motion-duration': '120ms',
    '--cr-status-weight': '700', '--cr-status-border': '0.125rem', '--cr-status-radius': '62.4375rem', '--cr-body-size': '1.0625rem', '--cr-selected-width': '0.1875rem',
    '--cr-checkbox-size': '1.375rem', '--cr-row-height': '3.75rem' },
  light: variables(palettes.contrast), dark: variables(palettes.contrast),
});
