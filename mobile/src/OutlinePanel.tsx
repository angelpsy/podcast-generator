import {Button, TextInput, useApp, useThemedStyles} from './theme';
import React, {useCallback, useEffect, useState} from 'react';
import {Alert, AppState, Modal, ScrollView, StyleSheet, Text, View} from 'react-native';
import {SafeAreaView} from 'react-native-safe-area-context';
import {useFocusEffect} from '@react-navigation/native';
import Topics from '../specs/NativeTopics';
import Siwc from '../specs/NativeSiwc';
import {initialSnapshot, parseSnapshot} from './state';

type Proposal = {id: string; status: string; mode: string; error: string; model: string};
type Preview = {id: string; mode: string; baseRevision: number; reviewedRevision: number; currentRevision: number;
  stale: boolean; canApply: boolean; error: string; input: {targetTitle: string; targetDescription: string; path: string[]; goal: string}; currentPath: string[]; currentGoal: string; currentDescription: string;
  newNodes: {title: string; description: string; kind: string; depth: number}[]; removedNodes: {title: string}[]; removedLinks: number; convertQuestion: boolean};
const labels: Record<string, string> = {RUNNING: 'Запрос выполняется', READY: 'Готов к просмотру', APPLIED: 'Применён', DISCARDED: 'Отклонён',
  FAILED: 'Ошибка запроса', CANCELLED: 'Отменён', INTERRUPTED: 'Прерван при перезапуске'};
const errors: Record<string, string> = {revision_conflict: 'Структура изменилась. Просмотрите актуальные изменения и подтвердите их заново.',
  outline_duplicate_title: 'Есть совпадение с существующим названием. Измените структуру или запросите новый план.',
  outline_depth: 'Предложение превышает доступную глубину.', node_limit: 'Превышен предел 200 узлов в топике.',
  outline_interrupted: 'Запрос прерван. Для повтора создайте новый запрос.', select_model: 'Выбранная модель больше недоступна. Обновите список моделей.',
  cancelled: 'Запрос отменён.', outline_busy: 'Другой запрос структуры ещё выполняется.', proposal_not_ready: 'Предложение уже обработано.',
  proposal_not_found: 'Предложение или его ветка удалены.'};
const describe = (code: string) => errors[code] ?? 'Не удалось подготовить структуру. Повторный запрос запускается вручную.';
const failure = (e: unknown) => describe((e as {code?: string})?.code ?? 'request_failed');
function Sheet({children, close}: {children: React.ReactNode; close: () => void}) {
  const s = useThemedStyles(styleDefinitions);
  return <Modal visible animationType="slide" onRequestClose={close}><SafeAreaView style={s.safe}>
    <ScrollView contentContainerStyle={s.content} keyboardShouldPersistTaps="handled"><Button title="Закрыть предложение" onPress={close} />{children}</ScrollView>
  </SafeAreaView></Modal>;
}
export default function OutlinePanel({topicId, nodeId, revision, depth, defaultDepth, onApplied}: {
  topicId: string; nodeId: string; revision: number; depth: number; defaultDepth: number; onApplied: (raw: string) => void;
}) {
  const s = useThemedStyles(styleDefinitions);
  const {prefs} = useApp();
  const [items, setItems] = useState<Proposal[]>([]);
  const [globalBusy, setGlobalBusy] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [form, setForm] = useState(false);
  const [mode, setMode] = useState('APPEND');
  const [levels, setLevels] = useState(Math.min(defaultDepth, depth));
  const [limit, setLimit] = useState('12');
  const [intent, setIntent] = useState('');
  const [session, setSession] = useState(initialSnapshot);
  const [preview, setPreview] = useState<Preview | null>(null);
  const refresh = useCallback(async () => {
    try { const page = JSON.parse(await Topics.getOutlines(topicId, nodeId)); setItems(page.items); setGlobalBusy(page.busy); }
    catch (e) { setError(failure(e)); }
  }, [topicId, nodeId]);
  const polling = globalBusy || items.some(p => p.status === 'RUNNING');
  useFocusEffect(useCallback(() => {
    refresh(); const timer = polling ? setInterval(refresh, 1500) : undefined;
    const sub = AppState.addEventListener('change', state => { if (state === 'active') { refresh(); } });
    return () => { if (timer) { clearInterval(timer); } sub.remove(); };
  }, [refresh, polling]));
  useEffect(() => {
    const update = (raw: string) => setSession(old => { const next = parseSnapshot(raw); return next.revision >= old.revision ? next : old; });
    const sub = Siwc.onSnapshot(update); Siwc.getSnapshot().then(update).catch(() => {}); return () => sub.remove();
  }, []);
  const run = async (action: () => Promise<void>) => {
    setBusy(true); setError(''); try { await action(); } catch (e) { setError(failure(e)); } finally { setBusy(false); await refresh(); }
  };
  const selected = prefs.model || session.models[0]?.slug || '';
  const showPreview = async (id: string) => setPreview(JSON.parse(await Topics.previewOutline(id)) as Preview);
  return <View style={s.card}>
    <Text style={s.heading}>Структура через ChatGPT</Text>
    <Text style={s.hint}>Создаётся только предложение. Дерево изменится после просмотра и подтверждения. Подкасты создаются отдельно.</Text>
    <Button title="Предложить структуру" disabled={busy || globalBusy || depth < 1} onPress={() => {
      setError(''); setLevels(Math.min(defaultDepth, depth)); setForm(true);
    }} />
    {!!error && <Text accessibilityRole="alert" style={s.error}>{error}</Text>}
    {items.map(p => <View key={p.id} style={s.card}>
      <Text style={s.text}>{labels[p.status] ?? p.status} · {p.mode === 'APPEND' ? 'Добавление' : 'Замена'}</Text>
      <Text style={s.hint}>{p.model}</Text>{!!p.error && <Text style={s.error}>{describe(p.error)}</Text>}
      {p.status === 'READY' && <Button title="Посмотреть предложение" disabled={busy} onPress={() => run(() => showPreview(p.id))} />}
      {p.status === 'RUNNING' && <Button title="Отменить запрос" onPress={() => Topics.cancelOutline(p.id)} />}
    </View>)}
    {form && <Sheet close={() => { if (!busy) { setForm(false); } }}>
      <Text style={s.heading}>Запрос структуры</Text>
      <Text style={s.hint}>Максимум уровней и новых узлов. Модель может предложить меньше. Язык структуры соответствует настройке топика.</Text>
      <View style={s.row}>{['APPEND', 'REPLACE'].map(value => <Button key={value} title={`${mode === value ? '● ' : ''}${value === 'APPEND' ? 'Добавить' : 'Заменить дочерние узлы'}`} disabled={busy} onPress={() => setMode(value)} />)}</View>
      <Text style={s.text}>Уровней ниже выбранного узла</Text>
      <View style={s.row}>{Array.from({length: depth}, (_, i) => i + 1).map(value => <Button key={value} title={`${levels === value ? '● ' : ''}${value}`} disabled={busy} onPress={() => setLevels(value)} />)}</View>
      <Text style={s.text}>Новых узлов, от 1 до 50</Text>
      <TextInput accessibilityLabel="Лимит узлов" style={s.input} keyboardType="number-pad" value={limit} maxLength={2} onChangeText={setLimit} editable={!busy} />
      <Text style={s.text}>Пожелания к плану</Text>
      <TextInput accessibilityLabel="Пожелания к плану" style={s.input} multiline value={intent} maxLength={1000} onChangeText={setIntent} editable={!busy} />
      <Text style={s.text}>Модель</Text>
      <Text style={s.text}>{session.models.find(m => m.slug === selected)?.name ?? 'Выберите модель в настройках'}</Text>
      {!session.planEnabled && <Text style={s.error}>Подключите ChatGPT в настройках.</Text>}
      {!!error && <Text accessibilityRole="alert" style={s.error}>{error}</Text>}
      <Button title="Запросить предложение" disabled={busy || globalBusy || session.busy || !session.planEnabled || !session.models.some(m => m.slug === selected) || !/^\d+$/.test(limit) || +limit < 1 || +limit > 50}
        onPress={() => run(async () => {
          await Topics.requestOutline(JSON.stringify({schema: 1, topicId, nodeId, expectedRevision: revision, depth: levels, maxNodes: +limit, mode, model: selected, intent}));
          setForm(false);
        })} />
    </Sheet>}
    {preview && <Sheet close={() => { if (!busy) { setPreview(null); } }}>
      <Text style={s.heading}>Предпросмотр изменений</Text>
      <Text style={s.text}>Исходный запрос: {preview.input.path.join(' / ')} · {preview.input.goal} · {preview.input.targetDescription}</Text>
      <Text style={s.text}>Сейчас: {preview.currentPath.join(' / ')} · {preview.currentGoal} · {preview.currentDescription}</Text>
      <Text style={s.hint}>Версия запроса {preview.baseRevision}; просмотрена {preview.reviewedRevision}; текущая {preview.currentRevision}. Название выбранного узла сохраняется.</Text>
      {preview.convertQuestion && <Text style={s.text}>Вопрос станет разделом; его ID и прикреплённые выпуски сохранятся.</Text>}
      <Text style={s.heading}>Добавить: {preview.newNodes.length}</Text>
      {preview.newNodes.map((n, i) => <View key={i} style={{paddingLeft: (n.depth - 1) * 12}}><Text style={s.text}>{n.kind === 'SECTION' ? 'Раздел' : 'Вопрос'}: {n.title}</Text><Text style={s.hint}>{n.description}</Text></View>)}
      <Text style={s.heading}>Удалить узлов: {preview.removedNodes.length}; связей: {preview.removedLinks}</Text>
      {preview.removedNodes.map((n, i) => <Text key={i} style={s.text}>{n.title}</Text>)}
      <Text style={s.hint}>Подкасты, аудиофайлы и запущенная очередь сохранятся.</Text>
      {!!preview.error && <Text style={s.error}>{describe(preview.error)}</Text>}
      {preview.stale && <><Text style={s.error}>Структура изменилась после предыдущего просмотра. Проверьте новые и удаляемые узлы выше.</Text>
        <Button title="Подтвердить актуальный просмотр" disabled={busy || !!preview.error} onPress={() => run(async () => setPreview(JSON.parse(await Topics.reviewOutline(preview.id, preview.currentRevision)) as Preview))} /></>}
      {!!error && <Text accessibilityRole="alert" style={s.error}>{error}</Text>}
      <Button title="Применить структуру" disabled={busy || !preview.canApply} onPress={() => Alert.alert('Применить предложение?',
        `Добавить узлов: ${preview.newNodes.length}. Удалить узлов: ${preview.removedNodes.length}, связей: ${preview.removedLinks}.`,
        [{text: 'Отмена', style: 'cancel'}, {text: 'Применить', onPress: () => run(async () => {
          try { const raw = await Topics.applyOutline(preview.id, preview.currentRevision); onApplied(raw); setPreview(null); }
          catch (e) { await showPreview(preview.id); throw e; }
        })}])} />
      <Button title="Отклонить предложение" disabled={busy} onPress={() => run(async () => { await Topics.discardOutline(preview.id); setPreview(null); })} />
    </Sheet>}
  </View>;
}
const styleDefinitions = StyleSheet.create({safe: {flex: 1, backgroundColor: '#f3f5f7'}, content: {padding: 20, gap: 16, paddingBottom: 36},
  card: {padding: 12, gap: 8, borderRadius: 20, backgroundColor: '#fff'}, heading: {fontSize: 18, fontWeight: '600', color: '#132638'},
  text: {fontSize: 16, lineHeight: 24, color: '#263c4f'}, hint: {fontSize: 12, lineHeight: 18, color: '#657381'},
  row: {flexDirection: 'row', flexWrap: 'wrap', gap: 8}, input: {minHeight: 48, borderWidth: 1, borderColor: '#ccd5dd', borderRadius: 14, padding: 12, color: '#132638'}, error: {color: '#ac2525', lineHeight: 22}});
