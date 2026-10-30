import {mapStyles, palettes, resolveDark} from '../src/themeTokens';
function luminance(hex: string) {
  const channels = hex.slice(1).match(/../g)!.map(value => parseInt(value, 16) / 255).map(n => n <= 0.04045 ? n / 12.92 : Math.pow((n + 0.055) / 1.055, 2.4));
  return channels[0] * 0.2126 + channels[1] * 0.7152 + channels[2] * 0.0722;
}
function contrast(a: string, b: string) { const x = luminance(a); const y = luminance(b); return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05); }
test.each(Object.entries(palettes))('%s theme keeps readable text, hints, errors and buttons', (_, colors) => {
  for (const background of [colors.background, colors.surface, colors.bar]) {
    for (const text of [colors.title, colors.text, colors.muted, colors.error, colors.accent]) { expect(contrast(text, background)).toBeGreaterThanOrEqual(4.5); }
  }
  expect(contrast(colors.buttonText, colors.button)).toBeGreaterThanOrEqual(4.5);
  expect(contrast(colors.neutralText, colors.neutralButton)).toBeGreaterThanOrEqual(4.5);
});
test('system setting follows the OS, explicit preferences override it', () => {
  expect(resolveDark('system', 'dark')).toBe(true); expect(resolveDark('system', 'light')).toBe(false); expect(resolveDark('system', null)).toBe(false);
  expect(resolveDark('dark', 'light')).toBe(true); expect(resolveDark('light', 'dark')).toBe(false);
});
test('theme conversion is immutable and preserves layout and nested styles', () => {
  const styles = {box: {color: '#132638', backgroundColor: '#fff', padding: 20}, nested: [{borderColor: '#ccd5dd'}]};
  const result = mapStyles(styles, palettes.dark);
  expect(result.box.color).toBe(palettes.dark.title); expect(result.box.backgroundColor).toBe(palettes.dark.surface);
  expect(result.box.padding).toBe(20); expect(result.nested[0].borderColor).toBe(palettes.dark.border); expect(styles.box.color).toBe('#132638');
});
