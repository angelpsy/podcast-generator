import React, {createContext, useContext, useEffect, useMemo, useRef, useState} from 'react';
import {ActivityIndicator, Button as NativeButton, type ButtonProps, Pressable, StatusBar, StyleSheet, Text, TextInput as NativeInput, type TextInputProps, useColorScheme, View} from 'react-native';
import {DarkTheme, DefaultTheme} from '@react-navigation/native';
import LibraryUI from '../specs/NativeLibraryUI';
import Siwc from '../specs/NativeSiwc';
import {parseSnapshot} from './state';
import {defaultContent, type Content} from './contentTypes';
export type Preferences = {theme: 'system' | 'light' | 'dark'; profile: string; model: string; settings: Content};
export const defaultPreferences: Preferences = {theme: 'system', profile: 'ru_standard', model: '', settings: defaultContent};
import {palettes, mapStyles, resolveDark} from './themeTokens';
export {palettes, mapStyles} from './themeTokens';
const Context = createContext({prefs: defaultPreferences, colors: palettes.light, dark: false,
  save: async (_value: Partial<Preferences>) => {}});
export function AppProvider({children}: {children: React.ReactNode}) {
  const [prefs, setPrefs] = useState(defaultPreferences); const [ready, setReady] = useState(false); const [error, setError] = useState(false); const attempted = useRef(false);
  const system = useColorScheme(); const dark = resolveDark(prefs.theme, system);
  const colors = dark ? palettes.dark : palettes.light;
  const load = () => { setError(false); LibraryUI.getPreferences().then(raw => { setPrefs(JSON.parse(raw) as Preferences); setReady(true); }).catch(() => setError(true)); };
  useEffect(() => { load(); }, []);
  useEffect(() => {
    const update = (raw: string) => { const session = parseSnapshot(raw);
      if (!session.signedIn) { attempted.current = false; }
      if (session.planEnabled && !session.busy && !session.models.length && !attempted.current) { attempted.current = true; Siwc.listModels().catch(() => {}); }
    };
    const sub = Siwc.onSnapshot(update); Siwc.getSnapshot().then(update).catch(() => {}); return () => sub.remove();
  }, []);
  const value = useMemo(() => ({prefs, colors, dark, save: async (patch: Partial<Preferences>) => {
    const next = JSON.parse(await LibraryUI.savePreferences(JSON.stringify(patch))) as Preferences; setPrefs(next);
  }}), [prefs, colors, dark]);
  return <Context.Provider value={value}><StatusBar barStyle={dark ? 'light-content' : 'dark-content'} />
    <View style={[providerStyles.body, {backgroundColor: colors.background}]}>{ready ? children : error ? <><Text style={{color: colors.text}}>Не удалось прочитать настройки.</Text><NativeButton title="Повторить" onPress={load} /></> : <ActivityIndicator color={colors.accent} />}</View>
  </Context.Provider>;
}
export const useApp = () => useContext(Context);
export function useNavigationTheme() {
  const {dark, colors} = useApp(); const base = dark ? DarkTheme : DefaultTheme;
  return {...base, colors: {...base.colors, background: colors.background, card: colors.surface, text: colors.text, border: colors.border, primary: colors.accent}};
}
export function useThemedStyles<T>(definitions: T): T { const {colors} = useApp(); return useMemo(() => mapStyles(definitions, colors), [definitions, colors]); }
export function Button(props: ButtonProps & {variant?: 'primary' | 'secondary' | 'neutral' | 'quiet'}) {
  const {colors} = useApp(); const selected = props.title.startsWith('● ');
  const backgroundColor = props.variant === 'primary' ? colors.button : props.variant === 'neutral' ? colors.neutralButton : props.variant === 'quiet' ? 'transparent' : colors.bar;
  const color = props.variant === 'primary' ? colors.buttonText : props.variant === 'neutral' ? colors.neutralText : selected ? colors.accent : colors.text;
  return <Pressable accessibilityRole="button" accessibilityLabel={props.accessibilityLabel ?? props.title}
    accessibilityState={{...props.accessibilityState, disabled: props.disabled, selected: props.title.startsWith('● ')}}
    testID={props.testID} disabled={props.disabled} onPress={props.onPress} style={({pressed}) => [providerStyles.button, {backgroundColor, borderWidth: selected ? 1 : 0, borderColor: colors.accent, opacity: props.disabled ? 0.45 : pressed ? 0.8 : 1}]}>
    <Text style={[providerStyles.buttonText, {color}]}>{props.title.replace(/^● /, '')}</Text></Pressable>;
}
export function TextInput(props: TextInputProps) { const {colors} = useApp(); return <NativeInput {...props} placeholderTextColor={colors.muted} selectionColor={colors.accent} />; }
export const sharedStyles = StyleSheet.create({safe: {flex: 1, backgroundColor: '#f3f5f7'}, content: {padding: 20, gap: 14, paddingBottom: 36},
  title: {fontSize: 30, fontWeight: '700', color: '#132638'}, heading: {fontSize: 18, fontWeight: '600', color: '#132638'},
  text: {fontSize: 16, lineHeight: 24, color: '#263c4f'}, hint: {fontSize: 12, lineHeight: 18, color: '#657381'},
  error: {color: '#ac2525'}, card: {padding: 14, borderRadius: 20, backgroundColor: '#fff', gap: 10},
  input: {minHeight: 48, padding: 12, borderWidth: 1, borderColor: '#ccd5dd', borderRadius: 14, color: '#132638'}, row: {flexDirection: 'row', flexWrap: 'wrap', gap: 8}});

const providerStyles = StyleSheet.create({body: {flex: 1}, button: {minHeight: 48, paddingVertical: 10, paddingHorizontal: 12, justifyContent: 'center', alignItems: 'center', borderRadius: 14, maxWidth: '100%', flexShrink: 1}, buttonText: {fontSize: 16, lineHeight: 22, fontWeight: '600', textAlign: 'center'}});
