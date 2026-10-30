import React, {useEffect, useState} from 'react';
import {ActivityIndicator, Alert, ScrollView, Text, View} from 'react-native';
import {SafeAreaView} from 'react-native-safe-area-context';
import Siwc from '../specs/NativeSiwc';
import Podcast from '../specs/NativePodcast';
import {initialSnapshot, parseSnapshot} from './state';
import {siwcErrors} from './siwcErrors';
import ContentEditor from './ContentSettings';
import {Button, sharedStyles, useApp, useThemedStyles} from './theme';
import PlayerBar from './PlayerBar';
export default function SettingsScreen() {
  const s = useThemedStyles(sharedStyles); const {prefs, save, colors} = useApp(); const [session, setSession] = useState(initialSnapshot);
  const [profile, setProfile] = useState(prefs.profile); const [settings, setSettings] = useState(prefs.settings); const [error, setError] = useState(''); const [busy, setBusy] = useState(false); const [modelsOpen, setModelsOpen] = useState(false); const [defaultsOpen, setDefaultsOpen] = useState(false);
  useEffect(() => { const update = (raw: string) => setSession(old => { const next = parseSnapshot(raw); return next.revision >= old.revision ? next : old; });
    const sub = Siwc.onSnapshot(update); Siwc.getSnapshot().then(update).catch(() => setError('Не удалось прочитать аккаунт.')); return () => sub.remove(); }, []);
  const run = async (action: () => Promise<unknown>) => { setBusy(true); setError(''); try { await action(); } catch { setError('Не удалось сохранить настройки или выполнить действие. Повторите попытку.'); } finally { setBusy(false); } };
  return <SafeAreaView edges={['left', 'right', 'bottom']} style={s.safe}><ScrollView contentContainerStyle={s.content}>
    <Text style={s.title}>Настроить под себя</Text>
    <View style={s.card}><Text style={s.heading}>Оформление</Text>
      <View style={s.row}>{Object.entries({system: 'Как в системе', light: 'Светлая', dark: 'Тёмная'}).map(([key, name]) => <Button key={key} title={`${prefs.theme === key ? '● ' : ''}${name}`} disabled={busy} onPress={() => run(() => save({theme: key as typeof prefs.theme}))} />)}</View>
    </View>
    <View style={s.card}><Text style={s.heading}>Аккаунт ChatGPT</Text>
      <Text style={s.text}>{session.planEnabled ? 'Подписка подключена' : session.signedIn ? 'Аккаунт подключён без доступа к подписке' : 'Вход не выполнен'}</Text>
      {!!session.email && <Text style={s.text}>{session.email}</Text>}
      {!!session.error && session.error !== 'cancelled' && <Text style={s.error}>{siwcErrors[session.error] ?? 'Не удалось завершить действие. Повторите попытку.'}</Text>}
      {!session.planEnabled && <><Button title="Войти через ChatGPT" disabled={busy || session.busy} onPress={() => run(() => Siwc.signIn())} />
        <Text style={s.hint}>Завершите вход в браузере и вернитесь в приложение.</Text></>}
      {session.busy && <><ActivityIndicator color={colors.accent} /><Button title="Отменить подключение" onPress={() => Siwc.cancel()} /></>}
      {session.signedIn && <Button title="Выйти из аккаунта" disabled={busy || session.busy} onPress={() => Alert.alert('Выйти из ChatGPT?', 'Сохранённые подкасты останутся на устройстве. Запросы текста будут приостановлены.',
        [{text: 'Отмена', style: 'cancel'}, {text: 'Выйти', onPress: () => { Podcast.cancel(); run(() => Siwc.signOut()); }}])} />}
    </View>
    <View style={s.card}><Button title="Текстовая модель" onPress={() => setModelsOpen(!modelsOpen)} />
      {modelsOpen && <>
      <Text style={s.hint}>Выбор применяется к новым запросам. Настройки уже поставленных заданий сохраняются.</Text>
      <Button title="Обновить доступные модели" disabled={busy || session.busy || !session.planEnabled} onPress={() => run(() => Siwc.listModels())} />
      {session.models.map(model => <Button key={model.slug} title={`${(prefs.model || session.models[0]?.slug) === model.slug ? '● ' : ''}${model.name}`} disabled={busy} onPress={() => run(() => save({model: model.slug}))} />)}
      {!!prefs.model && !session.models.some(m => m.slug === prefs.model) && <Text style={s.hint}>Сохранённая модель пока недоступна. Обновите каталог и выберите другую.</Text>}
      </>}
    </View>
    <View style={s.card}><Button title="Настройки новых выпусков" onPress={() => setDefaultsOpen(!defaultsOpen)} />
      {defaultsOpen && <>
      <ContentEditor profile={profile} settings={settings} onProfile={setProfile} onSettings={setSettings} disabled={busy} />
      <Button variant="primary" title="Сохранить настройки выпусков" disabled={busy} onPress={() => run(() => save({profile, settings}))} />
      </>}
    </View>
    <View style={s.card}><Text style={s.heading}>Файлы</Text><Text style={s.text}>M4A / AAC · Podcasts/PodcastGenerator</Text>
      <Text style={s.hint}>Готовые записи сохраняются до явного удаления в библиотеке. Создание требует открытого приложения; слушать можно с заблокированным экраном.</Text></View>
    {!!error && <Text accessibilityRole="alert" style={s.error}>{error}</Text>}
  </ScrollView><PlayerBar /></SafeAreaView>;
}
