import {Button, TextInput, useThemedStyles} from './theme';
import React, {useState} from 'react';
import { StyleSheet, Text, View} from 'react-native';
import {immersionLabels, profiles, volumes, type Content} from './contentTypes';
export * from './contentTypes';
export default function ContentEditor({profile, settings, onProfile, onSettings, disabled = false}: {profile: string; settings: Content;
  onProfile: (value: string) => void; onSettings: (value: Content) => void; disabled?: boolean}) {
  const s = useThemedStyles(styleDefinitions);
  const [custom, setCustom] = useState(false);
  return <View style={s.content}>
    <Text style={s.heading}>Язык</Text>
    <View style={s.options}>{Object.entries(profiles).map(([key, name]) => <Button key={key} title={`${key === profile ? '● ' : ''}${name}`} disabled={disabled} onPress={() => onProfile(key)} />)}</View>
    {profile === 'en_simple_ste' && <Text style={s.hint}>Простой английский по мотивам STE: короткие фразы и объяснение терминов. Без заявления о формальном соответствии стандарту.</Text>}
    <Text style={s.heading}>Объём и погружение</Text>
    <View style={s.options}>{Object.entries(volumes).map(([key, name]) => <Button key={key} title={`${key === settings.volume ? '● ' : ''}${name.split(' · ')[0]}`} disabled={disabled}
      onPress={() => onSettings({...settings, volume: key, immersion: key === 'BRIEF' ? 'OVERVIEW' : key === 'DEEP' ? 'DEEP' : 'EXPLANATORY'})} />)}</View>
    <Text style={s.hint}>{volumes[settings.volume]} · {immersionLabels[settings.immersion]}</Text>
    <Text style={s.hint}>Число слов — ориентир. Рассказ не удлиняется повторами и не обрезается по времени.</Text>
    <Button title={custom ? 'Скрыть расширенные настройки' : 'Настроить погружение и аудиторию'} onPress={() => setCustom(!custom)} />
    {custom && <>
      <Text style={s.heading}>Объём отдельно</Text>
      {Object.entries(volumes).map(([key, name]) => <Button key={key} title={`${key === settings.volume ? '● ' : ''}${name.split(' · ')[0]}`} disabled={disabled} onPress={() => onSettings({...settings, volume: key})} />)}
      <Text style={s.heading}>Погружение отдельно</Text>
      {Object.entries(immersionLabels).map(([key, name]) => <Button key={key}
        title={`${key === settings.immersion ? '● ' : ''}${name}`} disabled={disabled} onPress={() => onSettings({...settings, immersion: key})} />)}
    <Text style={s.heading}>На чём сделать акцент?</Text>
    <TextInput accessibilityLabel="Акцент рассказа" multiline style={s.input} value={settings.intent} maxLength={1000} editable={!disabled} onChangeText={intent => onSettings({...settings, intent})} />
    <Text style={s.heading}>Для кого рассказ?</Text>
    <TextInput accessibilityLabel="Аудитория рассказа" style={s.input} value={settings.audience} maxLength={500} editable={!disabled} onChangeText={audience => onSettings({...settings, audience})} />
    </>}
  </View>;
}
const styleDefinitions = StyleSheet.create({options: {flexDirection: 'row', flexWrap: 'wrap', gap: 8}, content: {gap: 8}, heading: {fontSize: 16, color: '#132638', fontWeight: '600'},
  hint: {fontSize: 12, lineHeight: 18, color: '#657381'}, input: {borderWidth: 1, borderColor: '#ccd5dd', minHeight: 48, padding: 10, borderRadius: 14, color: '#132638'}});
