import React, {useState} from 'react';
import {Modal, Pressable, ScrollView, StyleSheet, Text, useWindowDimensions, View} from 'react-native';
import {SafeAreaView} from 'react-native-safe-area-context';
import {Button, TextInput, sharedStyles, useApp, useThemedStyles} from './theme';
export function uiError(e: unknown) {
  const code = (e as {code?: string})?.code ?? '';
  return ({revision_conflict: 'Данные изменились. Обновите список и повторите действие.', playlist_not_found: 'Плейлист удалён.',
    empty_playlist: 'Нет доступных записей для прослушивания.', playlist_limit: 'В плейлисте допускается до 1000 записей.',
    podcast_not_found: 'Выпуск уже удалён из библиотеки.', podcast_not_ready: 'Можно добавлять готовые записи.',
    text_length: 'Введите название до 200 символов.'} as Record<string, string>)[code] ?? 'Не удалось выполнить действие. Повторите попытку.';
}
export function NameDialog({title, initial = '', close, save}: {title: string; initial?: string; close: () => void; save: (name: string) => Promise<void>}) {
  const s = useThemedStyles(sharedStyles); const [name, setName] = useState(initial); const [busy, setBusy] = useState(false); const [error, setError] = useState('');
  return <Modal visible onRequestClose={() => { if (!busy) { close(); } }}><SafeAreaView style={s.safe}><ScrollView contentContainerStyle={s.content} keyboardShouldPersistTaps="handled">
    <Text style={s.title}>{title}</Text><Button title="Закрыть форму" disabled={busy} onPress={close} />
    <TextInput accessibilityLabel="Название плейлиста" value={name} editable={!busy} style={s.input} onChangeText={v => setName(Array.from(v).slice(0, 200).join(''))} />
    {!!error && <Text accessibilityRole="alert" style={s.error}>{error}</Text>}
    <Button title="Сохранить плейлист" disabled={busy || !name.trim()} onPress={async () => {
      setBusy(true); setError(''); try { await save(name); } catch (e) { setError(uiError(e)); } finally { setBusy(false); }
    }} />
  </ScrollView></SafeAreaView></Modal>;
}

export type MenuAction = {title: string; onPress: () => void; disabled?: boolean; destructive?: boolean};
/** Modal context menu: actions overlay the screen and never move its content. */
export function ContextMenu({title, actions, close}: {title: string; actions: MenuAction[]; close: () => void}) {
  const {colors} = useApp(); const {height} = useWindowDimensions();
  return <Modal visible transparent animationType="fade" onRequestClose={close} statusBarTranslucent>
    <View style={menuStyles.overlay}>
      <Pressable style={StyleSheet.absoluteFill} accessibilityRole="button" accessibilityLabel="Закрыть меню" onPress={close} />
      <View accessibilityViewIsModal style={[menuStyles.menu, {backgroundColor: colors.surface, maxHeight: height * 0.75}]}>
        <Text accessibilityRole="header" style={[menuStyles.title, {color: colors.muted}]}>{title}</Text>
        <ScrollView>{actions.map((action, i) => <Pressable key={`${i}-${action.title}`} accessibilityRole="button" accessibilityState={{disabled: !!action.disabled}}
          disabled={action.disabled} onPress={() => { close(); action.onPress(); }}
          style={({pressed}) => [menuStyles.action, {backgroundColor: pressed ? colors.bar : colors.surface, opacity: action.disabled ? 0.45 : 1}]}>
          <Text style={{color: action.destructive ? colors.error : colors.text}}>{action.title}</Text>
        </Pressable>)}</ScrollView>
      </View>
    </View>
  </Modal>;
}
const menuStyles = StyleSheet.create({overlay: {flex: 1, backgroundColor: '#00000066', justifyContent: 'center', alignItems: 'flex-end', padding: 20},
  menu: {width: '100%', maxWidth: 340, borderRadius: 20, overflow: 'hidden', paddingVertical: 10, elevation: 12},
  title: {fontSize: 13, paddingHorizontal: 20, paddingVertical: 12}, action: {minHeight: 50, paddingHorizontal: 20, paddingVertical: 14, justifyContent: 'center'}});
