import {Button, TextInput, useApp, useThemedStyles} from './theme';
import React, {useEffect, useRef, useState} from 'react';
import { Modal, ScrollView, StyleSheet, Text, View} from 'react-native';
import {SafeAreaView} from 'react-native-safe-area-context';
import Podcast from '../specs/NativePodcast';
import Siwc from '../specs/NativeSiwc';
import {initialSnapshot, parseSnapshot} from './state';
import {generationErrors} from './generationLabels';
import ContentEditor, { immersionLabels, profiles, volumes, type Content} from './ContentSettings';

type Request = {mode: string; title?: string; topicId?: string; nodeId?: string; expectedRevision?: number; profile?: string; settings?: Content; model?: string};
type Preview = {token: string; items: {nodeId: string; title: string; profile?: string; settings?: Content}[]; skippedReady: number; skippedActive: number; remaining: number};
export default function GenerationPanel({request, close, done, showQueue}: {request: Request; close: () => void; done?: () => void; showQueue?: () => void}) {
  const s = useThemedStyles(styleDefinitions);
  const {prefs} = useApp();
  const [profile, setProfile] = useState(request.profile ?? prefs.profile);
  const [settings, setSettings] = useState(request.settings ?? prefs.settings);
  const [session, setSession] = useState(initialSnapshot);

  const [title, setTitle] = useState(request.title ?? ''); const [includeSections, setIncludeSections] = useState(true);
  const [additional, setAdditional] = useState(false);
  const [preview, setPreview] = useState<Preview | null>(null);
  const [overrides, setOverrides] = useState<Record<string, {profile: string; settings: Content}>>({});
  const [editing, setEditing] = useState<string | null>(null);
  const [busy, setBusy] = useState(false); const [error, setError] = useState('');
  const [queued, setQueued] = useState<number | null>(null);
  const scroll = useRef<ScrollView>(null);
  useEffect(() => { scroll.current?.scrollTo({y: 0, animated: false}); }, [preview?.token]);
  const requestId = useRef(`request-${Date.now()}-${Math.random().toString(36).slice(2)}`);
  useEffect(() => {
    const update = (raw: string) => setSession(old => { const next = parseSnapshot(raw); return next.revision >= old.revision ? next : old; });
    const sub = Siwc.onSnapshot(update); Siwc.getSnapshot().then(update).catch(() => setError('Не удалось прочитать сессию.')); return () => sub.remove();
  }, []);
  const selected = prefs.model || request.model || session.models[0]?.slug || '';
  const input = {...request, title, includeSections, profile, settings, model: selected, additional, overrides, requestId: requestId.current};
  const run = async (action: () => Promise<void>) => { setBusy(true); setError(''); try { await action(); } catch (e) {
    const code = (e as {code?: string}).code ?? 'generation_failed';
    setError(code === 'revision_conflict' ? 'Структура или очередь изменилась. Закройте форму, обновите топик и просмотрите пакет заново.' : generationErrors[code] ?? 'Не удалось выполнить действие. Повторите проверку очереди.');
  } finally { setBusy(false); } };
  return <Modal visible animationType="slide" onRequestClose={() => { if (!busy) { close(); } }}><SafeAreaView style={s.safe}>
    <ScrollView ref={scroll} contentContainerStyle={s.content} keyboardShouldPersistTaps="handled">
      <Button title="Закрыть создание" disabled={busy} onPress={close} />
      {queued !== null ? <>
        <Text accessibilityRole="header" style={s.heading}>Добавлено в очередь: {queued}</Text>
        <Text style={s.text}>Задания будут выполнены по очереди. Их состояние и управление доступны на отдельной вкладке «Очередь».</Text>
        {showQueue && <Button variant="primary" title="Открыть очередь" disabled={busy} onPress={() => { close(); showQueue(); }} />}
        <Button title="Вернуться" disabled={busy} onPress={close} />
      </> : <>
      <Text style={s.heading}>{request.mode === 'BATCH' ? 'Подкасты для разделов и вопросов' : request.mode === 'STANDALONE' ? 'Отдельный подкаст' : 'Подкаст выбранного узла'}</Text>
      {!preview ? <>
        {request.mode === 'STANDALONE' && <><Text style={s.text}>Тема или вопрос</Text><TextInput accessibilityLabel="Тема подкаста" value={title} onChangeText={value => setTitle(Array.from(value).slice(0, 500).join(''))} editable={!busy} multiline style={s.input} placeholder="О чём рассказать?" /></>}
        {request.mode === 'BATCH' && <><Text style={s.heading}>Для каких пунктов?</Text><Button title={`${includeSections ? '● ' : ''}Все разделы и вопросы`} onPress={() => setIncludeSections(true)} disabled={busy} /><Button title={`${!includeSections ? '● ' : ''}Только вопросы`} onPress={() => setIncludeSections(false)} disabled={busy} /><Text style={s.hint}>Отдельный подкаст для каждого выбранного пункта в этой ветке. Уже готовые записи и задания в очереди по умолчанию пропускаются. Подкаст по всему топику создаётся отдельно.</Text></>}
        {!session.planEnabled && <Text style={s.hint}>Для нового рассказа подключите ChatGPT в настройках приложения.</Text>}
        <ContentEditor profile={profile} settings={settings} onProfile={setProfile} onSettings={setSettings} disabled={busy} />
        {request.mode !== 'STANDALONE' && <Button title={additional ? '● Создавать дополнительные версии готовых' : 'Пропускать готовые · изменить'} disabled={busy} onPress={() => setAdditional(!additional)} />}
        <Text style={s.heading}>Текстовая модель</Text>
        <Text style={s.text}>{session.models.find(m => m.slug === selected)?.name ?? 'Выберите модель в настройках'}</Text>
        <Text style={s.hint}>Новые задания можно добавлять, пока создаются предыдущие. Они будут выполнены по очереди.</Text>
        <Button variant="primary" title="Посмотреть состав очереди" disabled={busy || (request.mode === 'STANDALONE' && !title.trim()) || !session.models.some(m => m.slug === selected) || !session.planEnabled}
          onPress={() => run(async () => setPreview(JSON.parse(await Podcast.previewGeneration(JSON.stringify(input))) as Preview))} />
      </> : <>
        <Text style={s.text}>{profiles[profile]} · {volumes[settings.volume]} · {immersionLabels[settings.immersion]}</Text>
        <Text style={s.text}>Новых: {preview.items.length}. Пропущено готовых: {preview.skippedReady}, активных: {preview.skippedActive}. Осталось вне лимита: {preview.remaining}.</Text>
        {preview.items.map((item, i) => <View key={item.nodeId || i}>
          <Text style={s.text}>{i + 1}. {item.title}</Text>
          <Text style={s.hint}>{profiles[item.profile ?? profile]} · {volumes[(item.settings ?? settings).volume]} · {immersionLabels[(item.settings ?? settings).immersion]}</Text>
          {request.mode === 'BATCH' && <Button title={`Настроить выпуск ${i + 1}`} disabled={busy} onPress={() => {
            setOverrides(old => ({...old, [item.nodeId]: old[item.nodeId] ?? {profile: item.profile ?? profile, settings: item.settings ?? settings}})); setEditing(item.nodeId);
          }} />}
        </View>)}
        {editing !== null && overrides[editing] && <>
          <ContentEditor profile={overrides[editing].profile} settings={overrides[editing].settings}
            onProfile={value => setOverrides(old => ({...old, [editing]: {...old[editing], profile: value}}))}
            onSettings={value => setOverrides(old => ({...old, [editing]: {...old[editing], settings: value}}))} disabled={busy} />
          <Button title="Пересчитать состав после настройки" disabled={busy} onPress={() => run(async () => {
            setPreview(JSON.parse(await Podcast.previewGeneration(JSON.stringify(input))) as Preview); setEditing(null);
          })} />
        </>}
        <Text style={s.hint}>До 20 новых заданий за запуск. Настройки фиксируются для каждого выпуска. Генерация последовательная; вкладки можно менять, приложение оставьте открытым. После паузы без сохранённого сценария потребуется новый запрос текста.</Text>
        <Button title="Изменить настройки" disabled={busy} onPress={() => { setEditing(null); setPreview(null); }} />
        <Button variant="primary" title="Добавить в очередь" disabled={busy || editing !== null || !preview.items.length}
          onPress={() => run(async () => {
            const result = JSON.parse(await Podcast.enqueueGeneration(JSON.stringify({...input, previewToken: preview.token})));
            setQueued(result.ids.length); done?.();
          })} />
      </>}
      </>}
      {!!error && <Text accessibilityRole="alert" style={s.error}>{error}</Text>}
    </ScrollView></SafeAreaView></Modal>;
}
const styleDefinitions = StyleSheet.create({safe: {flex: 1, backgroundColor: '#f3f5f7'}, content: {padding: 16, gap: 12, paddingBottom: 32},
  input: {minHeight: 90, borderWidth: 1, borderColor: '#ccd5dd', borderRadius: 14, padding: 12, color: '#132638', textAlignVertical: 'top'}, heading: {fontSize: 20, fontWeight: '600', color: '#132638'}, text: {fontSize: 16, lineHeight: 24, color: '#263c4f'}, hint: {fontSize: 12, color: '#657381'}, error: {color: '#ac2525'}, card: {backgroundColor: '#fff', padding: 12, gap: 8}});
