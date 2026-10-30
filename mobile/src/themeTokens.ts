export const palettes = {
  light: {background: '#f5f5f5', surface: '#ffffff', bar: '#ebebeb', title: '#242424', text: '#303030', muted: '#626262', border: '#dddddd', error: '#a03832', tint: '#ebebeb', accent: '#a94308', button: '#ef8535', buttonText: '#25170d', neutralButton: '#dedede', neutralText: '#242424'},
  dark: {background: '#141414', surface: '#202020', bar: '#2c2c2c', title: '#f3f3f3', text: '#ededed', muted: '#b5b5b5', border: '#3c3c3c', error: '#ffb4ab', tint: '#2c2c2c', accent: '#ffa45f', button: '#f39145', buttonText: '#25170d', neutralButton: '#dedede', neutralText: '#242424'},
};
/** Palette migration for existing layout definitions; all rendered colors come from semantic tokens. */
export function mapStyles<T>(definitions: T, colors: typeof palettes.light): T {
  const aliases: Record<string, string> = {'#f3f5f7': colors.background, '#fff': colors.surface, '#ffffff': colors.surface,
    '#eaf1f6': colors.bar, '#132638': colors.title, '#263c4f': colors.text, '#657381': colors.muted, '#546475': colors.muted,
    '#526170': colors.muted, '#ccd5dd': colors.border, '#e0e5ea': colors.border, '#ac2525': colors.error, '#eaf5fb': colors.tint, '#277ca4': colors.accent};
  const replace = (value: unknown): unknown => {
    if (typeof value === 'string') { return aliases[value.toLowerCase()] ?? value; }
    if (Array.isArray(value)) { return value.map(replace); }
    if (value && typeof value === 'object') { return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, replace(item)])); }
    return value;
  };
  return replace(definitions) as T;
}

export function resolveDark(mode: string, system: string | null | undefined) { return mode === 'dark' || (mode === 'system' && system === 'dark'); }
