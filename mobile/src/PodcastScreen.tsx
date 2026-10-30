import {Button, TextInput, useApp, useThemedStyles} from './theme';
import {useFocusEffect, useNavigation, useRoute, type NavigationProp, type RouteProp} from '@react-navigation/native';
import Playback from '../specs/NativePlayback';
import React, {useCallback, useEffect, useRef, useState} from 'react';
import {ActivityIndicator, Alert, Modal, Pressable, ScrollView, StyleSheet, Text, View} from 'react-native';
import {SafeAreaView} from 'react-native-safe-area-context';
import NativePodcast from '../specs/NativePodcast';
import NativeSiwc from '../specs/NativeSiwc';
import {initialSnapshot, parseSnapshot} from './state';
import {siwcErrors} from './siwcErrors';
import GenerationPanel from './GenerationPanel';
import {profiles} from './ContentSettings';

type Episode = {id: string; topic?: string; script?: string; languagePolicy?: string; actualWordCount?: number; qualityWarnings?: string[]; attachmentWarning?: string; durationMs: number; bytes: number};
type LibraryItem = {id: string; title: string; status: string; selected: boolean; durationMs: number; profile?: string; topics?: {id: string; title: string}[]};
type Snapshot = {
  library: LibraryItem[];
  storageReady: boolean;
  revision: number;
  stage: string;
  busy: boolean;
  error: string;
  script: string;
  inputTopic: string;
  selectedModel: string;
  selectedId: string;
  chunk: number;
  chunks: number;
  canRetry: boolean;
  playback: string;
  positionMs: number;
  durationMs: number;
  episode: Episode | null;
};
const initial: Snapshot = {
  library: [], storageReady: false, revision: 0, stage: 'Loading', busy: true, error: '', script: '', inputTopic: '', selectedModel: '', chunk: 0, chunks: 0,
  canRetry: false, playback: 'Stopped', positionMs: 0, durationMs: 0, episode: null, selectedId: '',
};
const labels: Record<string, string> = {
  StorageError: 'Нужно восстановление библиотеки', Loading: 'Проверяем библиотеку', Idle: 'Готов к созданию', PreparingVoice: 'Подготавливаем голос',
  GeneratingText: 'Создаём текст через ChatGPT',
  DraftReady: 'Есть незавершённый подкаст',
  Synthesizing: 'Озвучиваем', Encoding: 'Сжимаем аудио', Saving: 'Сохраняем файл',
  Ready: 'Подкаст сохранён', Cancelling: 'Останавливаем', Cancelled: 'Создание остановлено', Failed: 'Не удалось создать подкаст',
};
const errors: Record<string, string> = {
  ...siwcErrors,
  topic_length: 'Введите тему: от 1 до 500 символов.',
  answer_too_large: 'Ответ превысил предел выбранного объёма. Сценарий не сохранён; повтор запускается явно.',
  script_language: 'Язык ответа не соответствует выбранному профилю.',
  checkpoint_mismatch: 'Сохранённые фрагменты не совпадают со сценарием или повреждены. Данные сохранены; создайте новый выпуск.',
  empty_script: 'Модель не вернула текст для озвучки.',
  response_refused: 'Модель отклонила запрос. Измените тему.',
  response_incomplete: 'Ответ не завершён. Озвучка не запускалась; можно повторить запрос.',
  response_failed: 'Не удалось завершить ответ OpenAI. Можно повторить запрос.',
  script_markup: 'В ответе есть разметка вместо обычного текста. Повторите генерацию.',
  script_not_russian: 'Ответ получен не на русском языке. Повторите генерацию.',
  no_models: 'Для этой сессии нет доступных текстовых моделей.',
  operation_busy: 'Дождитесь завершения операции SIWC и повторите создание.',
  no_draft: 'Нет сохранённого черновика. Нажмите «Создать подкаст», чтобы начать заново.',
  generatingtext_failed: 'Не удалось получить и сохранить текст. Повторите запрос.',
  cancelled: '',
  native_runtime_unavailable: 'Не удалось загрузить библиотеку озвучки.',
  preparingvoice_failed: 'Не удалось подготовить голос или сохранённые файлы.',
  synthesizing_failed: 'Не удалось завершить озвучку. Текст можно озвучить повторно.',
  encoding_failed: 'Не удалось сжать аудио. Полный WAV сохранён для повтора.',
  saving_failed: 'Не удалось сохранить файл в общую папку. Аудио сохранено для повтора.',
  cleanup_pending: 'Файл готов. Не удалось завершить очистку временных файлов; повторите проверку восстановления.',
  storage_operation_failed: 'Не удалось завершить операцию с файлами. Данные сохранены; повторите восстановление.',
  storage_recovery_failed: 'Не удалось восстановить файлы. Проверьте свободное место и повторите.',
  not_enough_space: 'Недостаточно свободного места для безопасного сохранения нового файла.',
  aac_unavailable: 'На устройстве не найден кодировщик AAC.',
  out_of_memory: 'Недостаточно памяти для озвучки.',
  external_player_unavailable: 'Не найдено приложение для этого аудиофайла. Файл сохранён.',
  file_unavailable: 'Сохранённый файл недоступен или удалён.',
  playback_failed: 'Не удалось воспроизвести файл.',
  app_not_foreground: 'Вернитесь в приложение для создания подкаста.',
};
const seconds = (ms: number) => `${Math.floor((ms || 0) / 60000)}:${String(Math.floor((ms || 0) / 1000) % 60).padStart(2, '0')}`;

export default function PodcastScreen({onBusyChange}: {onBusyChange: (busy: boolean) => void}) {
  const styles = useThemedStyles(styleDefinitions);
  const navigation = useNavigation<NavigationProp<{GenerationQueue: undefined}>>();
  const {prefs} = useApp(); const route = useRoute<RouteProp<{Podcasts: {create?: number} | undefined}, 'Podcasts'>>();
  const [state, setState] = useState(initial);
  const [session, setSession] = useState(initialSnapshot);
  const [topic, setTopic] = useState('');
  const [search, setSearch] = useState('');
  const [showText, setShowText] = useState(false);
  const [creating, setCreating] = useState(false); const [selectedOpen, setSelectedOpen] = useState(false); const [selectedItem, setSelectedItem] = useState<LibraryItem | null>(null); const [libraryActions, setLibraryActions] = useState(false); const [itemMenu, setItemMenu] = useState<string | null>(null);
  const [bridgeError, setBridgeError] = useState(false);
  const inputRestored = useRef(false);
  const [selectionRevision, setSelectionRevision] = useState(0);
  const openItem = (item: LibraryItem) => {
    setSelectionRevision(state.revision);
    setShowText(false); setBridgeError(false); setSelectedItem(item); setSelectedOpen(true);
    // This bridge command returns void; its result arrives in onSnapshot.
    try { NativePodcast.selectEpisode(item.id); } catch { setBridgeError(true); }
  };
  const act = (action: () => Promise<void>) => {
    setBridgeError(false);
    action().catch(() => setBridgeError(true));
  };
  useEffect(() => { onBusyChange(state.busy); }, [state.busy, onBusyChange]);
  useEffect(() => {
    if (state.stage !== 'Loading' && !inputRestored.current) {
      inputRestored.current = true;
      setTopic(state.inputTopic);
    }
  }, [state.stage, state.inputTopic, state.selectedModel]);
  useEffect(() => {
    let mounted = true;
    const apply = (raw: string) => {
      const next = parseSnapshot(raw);
      if (mounted) { setSession(previous => next.revision >= previous.revision ? next : previous); }
    };
    const sub = NativeSiwc.onSnapshot(apply);
    NativeSiwc.getSnapshot().then(apply).catch(() => { if (mounted) { setBridgeError(true); } });
    return () => { mounted = false; sub.remove(); };
  }, []);
  useEffect(() => {
    let mounted = true;
    const apply = (raw: string) => {
      const next = {...initial, ...JSON.parse(raw)} as Snapshot;
      if (mounted) {
        setState(previous => next.revision >= previous.revision ? next : previous);
      }
    };
    const sub = NativePodcast.onSnapshot(apply);
    NativePodcast.getSnapshot().then(apply).catch(() => {
      if (mounted) { setState(previous => ({...previous, error: 'storage_recovery_failed', busy: false})); }
    });
    return () => { mounted = false; sub.remove(); };
  }, []);
  useEffect(() => { if (route.params?.create) { inputRestored.current = true; setTopic(''); setCreating(true); } }, [route.params?.create]);
  useFocusEffect(useCallback(() => { NativePodcast.searchLibrary(search); }, [search]));
  const episode = state.episode;
  const selected = prefs.model || session.models[0]?.slug || '';
  const busy = state.busy || session.busy;
  const error = state.error || session.error;
  const script = state.script || episode?.script;
  const selectionReady = !!selectedItem && state.storageReady && !state.busy && state.selectedId === selectedItem.id && (!episode || episode.id === selectedItem.id);
  const selectionFailed = bridgeError || (!state.busy && state.revision > selectionRevision);
  return <SafeAreaView edges={['left', 'right']} style={styles.safe}>
    <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
      <Text style={styles.title}>Подкасты</Text>
      <Button title="Новый подкаст" variant="primary" disabled={!state.storageReady} onPress={() => { setTopic(''); setCreating(true); }} />
      {!session.planEnabled && <Button title="Войти через ChatGPT" disabled={busy} onPress={() => act(async () => NativeSiwc.signIn())} />}
      {(state.busy || state.canRetry || !!error || bridgeError) && <View style={styles.card}>
        <Text accessibilityRole="header" style={styles.heading}>{labels[state.stage] ?? state.stage}</Text>
        {state.busy && <ActivityIndicator />}
        {state.stage === 'Synthesizing' && <Text style={styles.text}>Фрагмент {state.chunk} из {state.chunks}</Text>}
        {!!error && error !== 'cancelled' && <Text accessibilityRole="alert" style={styles.error}>
          {errors[error] ?? 'Не удалось завершить создание. Сохранённые этапы можно продолжить.'}
        </Text>}
        {bridgeError && <Text style={styles.error}>Не удалось выполнить команду. Повторите действие.</Text>}
        {state.busy && state.stage !== 'Loading' && <Button title="Отменить создание" disabled={state.stage === 'Cancelling'} onPress={() => NativePodcast.cancel()} />}
        {state.canRetry && !state.busy && <Button title="Продолжить с сохранённого этапа"
          disabled={!state.storageReady || (session.busy && !state.script)} onPress={() => NativePodcast.retryAudio()} />}
        {state.canRetry && <Text style={styles.hint}>Продолжение использует прежнюю тему и сохранённый текст или аудио. Чтобы отправить изменённую тему, нажмите «Новый подкаст».</Text>}
        <Text style={styles.hint}>Оставьте приложение открытым на время создания. Готовые выпуски и незавершённые черновики сохраняются в библиотеке.</Text>
        {state.stage === 'StorageError' && <Button title="Восстановить доступ к библиотеке" onPress={() => NativePodcast.recoverLibrary()} />}
      </View>}
      <View style={styles.card}>
        <View style={styles.headerRow}><Text style={styles.heading}>Все записи</Text><Button title="⋯" accessibilityLabel="Действия с библиотекой" variant="quiet" onPress={() => setLibraryActions(!libraryActions)} /></View>
        {libraryActions && <>
        <Button title="Обновить библиотеку" disabled={state.busy || !state.storageReady} onPress={() => NativePodcast.recoverLibrary()} />
        <Button title="Слушать библиотеку" disabled={!state.storageReady} onPress={() => Playback.playLibrary()} />
        </>}
        <TextInput accessibilityLabel="Поиск по названию" value={search} placeholder="Найти по названию"
          placeholderTextColor="#657381" style={[styles.input, styles.search]}
          onChangeText={value => { setSearch(value); }} />
        {!state.library.length && <Text style={styles.hint}>{search ? 'Ничего не найдено' : 'Записей пока нет'}</Text>}
        {state.library.map(item => <View key={item.id} style={styles.model}>
          <View style={styles.headerRow}><Pressable style={styles.grow} accessibilityRole="button" accessibilityLabel={`Открыть: ${item.title}`}
            disabled={state.busy || !state.storageReady} onPress={() => openItem(item)}>
            <Text style={styles.text}>{item.selected ? '● ' : ''}{item.title}</Text>
            <Text style={styles.hint}>{{READY: 'M4A сохранён', DRAFT: 'Черновик · можно продолжить',
              FILE_MISSING: 'Файл удалён или недоступен', DELETING: 'Удаление не завершено'}[item.status] ?? item.status} · {profiles[item.profile ?? 'ru_standard']}{item.durationMs > 0 ? ` · ${seconds(item.durationMs)}` : ''}</Text>
            <Text style={styles.hint}>{item.topics?.length ? `Топики: ${item.topics.map(t => t.title).join(' · ')}` : 'Без топика'}</Text>
          </Pressable>
          <Button title="⋯" accessibilityLabel={`Действия с выпуском: ${item.title}`} variant="quiet" onPress={() => setItemMenu(itemMenu === item.id ? null : item.id)} /></View>
          {itemMenu === item.id && <Button title="Удалить" disabled={state.busy || !state.storageReady} onPress={() => Alert.alert('Удалить подкаст?',
            `«${item.title}»: запись, черновик и её аудиофайл будут удалены с устройства. Связи в топиках и плейлистах также удалятся.`,
            [{text: 'Отмена', style: 'cancel'}, {text: 'Удалить', style: 'destructive',
              onPress: () => NativePodcast.deleteEpisode(item.id)}])} />}
        </View>)}
      </View>
    </ScrollView>
    <Modal visible={selectedOpen} animationType="slide" onRequestClose={() => setSelectedOpen(false)}><SafeAreaView style={styles.safe}><ScrollView contentContainerStyle={styles.content}>
      <Button title="Закрыть выпуск" variant="quiet" onPress={() => setSelectedOpen(false)} />
      {!selectionReady ? <View style={styles.card}>
        <Text style={styles.heading}>{selectedItem?.title ?? 'Подкаст'}</Text>
        {selectionFailed ? <><Text accessibilityRole="alert" style={styles.error}>Не удалось открыть выбранный выпуск. Повторите попытку.</Text>
          <Button title="Повторить открытие" disabled={state.busy} onPress={() => { if (selectedItem) { openItem(selectedItem); } }} /></>
          : <><ActivityIndicator /><Text style={styles.hint}>Открываем выпуск…</Text></>}
      </View> : <>
      {!episode && <View style={styles.card}><Text style={styles.heading}>{selectedItem?.title ?? 'Подкаст'}</Text><Text style={styles.hint}>Готового аудиофайла пока нет.</Text>{state.canRetry && <Button title="Продолжить создание" disabled={state.busy} onPress={() => { setSelectedOpen(false); NativePodcast.retryAudio(); }} />}</View>}
      {!!episode && <View style={styles.card}>
        <Text style={styles.heading}>Выбранный выпуск</Text>
        {!!episode.topic && <Text style={styles.text}>{episode.topic}</Text>}
        {!!episode.actualWordCount && <Text style={styles.hint}>Слов в рассказе: {episode.actualWordCount} · {profiles[episode.languagePolicy ?? 'ru_standard']}</Text>}
        {!!episode.attachmentWarning && <Text style={styles.hint}>Исходная ветка изменилась или недоступна. Выпуск сохранён отдельно; его можно прикрепить вручную.</Text>}
        {!!episode.qualityWarnings?.length && <Text style={styles.hint}>Объём или длина предложений отличаются от ориентира. Рассказ сохранён без обрезки.</Text>}

        <Text style={styles.hint}>Podcasts/PodcastGenerator/ · M4A / AAC · {Math.round(episode.bytes / 1024)} КБ</Text>
        <Text style={styles.text}>Длительность: {seconds(episode.durationMs)}</Text>
        <Button variant="primary" title="Слушать выпуск" onPress={() => Playback.playEpisode(episode.id)} />
        <Button title="Открыть в другом приложении" disabled={state.busy} onPress={() => NativePodcast.openExternal()} />

      </View>}
      {!!script && <View style={styles.card}>
        <Button title={showText ? 'Скрыть текст' : state.script ? 'Показать текст текущего подкаста' : 'Показать текст сохранённого подкаста'} onPress={() => setShowText(!showText)} />
        {showText && <Text selectable style={styles.text}>{script}</Text>}
      </View>}
      </>}
    </ScrollView></SafeAreaView></Modal>
    {creating && <GenerationPanel request={{mode: 'STANDALONE', title: topic, model: selected}} close={() => setCreating(false)} showQueue={() => navigation.navigate('GenerationQueue')} />}
  </SafeAreaView>;
}

const styleDefinitions = StyleSheet.create({
  safe: {flex: 1, backgroundColor: '#f3f5f7'},
  content: {padding: 20, gap: 16, paddingBottom: 40},
  title: {fontSize: 30, fontWeight: '700', color: '#132638'},
  heading: {fontSize: 18, fontWeight: '600', color: '#132638'},
  text: {fontSize: 16, lineHeight: 24, color: '#263c4f'},
  hint: {fontSize: 12, lineHeight: 18, color: '#657381'},
  card: {backgroundColor: '#fff', padding: 18, borderRadius: 20, gap: 12},
  input: {minHeight: 90, borderWidth: 1, borderColor: '#ccd5dd', borderRadius: 14, padding: 12, color: '#132638', textAlignVertical: 'top'},
  search: {minHeight: 48}, grow: {flex: 1}, headerRow: {flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 8},
  model: {padding: 10, borderWidth: 1, borderColor: '#e0e5ea', borderRadius: 14},
  error: {color: '#ac2525', lineHeight: 21},
});
