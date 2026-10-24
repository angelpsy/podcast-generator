import React, {useEffect, useRef, useState} from 'react';
import {ActivityIndicator, Button, Keyboard, Pressable, ScrollView, StyleSheet, Text, TextInput, View} from 'react-native';
import {SafeAreaView} from 'react-native-safe-area-context';
import NativePodcast from '../specs/NativePodcast';
import NativeSiwc from '../specs/NativeSiwc';
import {initialSnapshot, parseSnapshot} from './state';
import {siwcErrors} from './siwcErrors';

type Episode = {
  topic?: string;
  script?: string;
  textModel?: string;
  textMs?: number;
  name: string;
  durationMs: number;
  bytes: number;
  sampleRate: number;
  prepareMs: number;
  synthesisMs: number;
  encodingMs: number;
  coldInit: boolean;
  observedPssKb: number;
};
type Snapshot = {
  revision: number;
  stage: string;
  busy: boolean;
  error: string;
  script: string;
  inputTopic: string;
  selectedModel: string;
  chunk: number;
  chunks: number;
  canRetry: boolean;
  playback: string;
  positionMs: number;
  durationMs: number;
  episode: Episode | null;
};
const initial: Snapshot = {
  revision: 0, stage: 'Loading', busy: true, error: '', script: '', inputTopic: '', selectedModel: '', chunk: 0, chunks: 0,
  canRetry: false, playback: 'Stopped', positionMs: 0, durationMs: 0, episode: null,
};
const labels: Record<string, string> = {
  Loading: 'Проверяем сохранённый файл', Idle: 'Готов к созданию', PreparingVoice: 'Подготавливаем голос',
  GeneratingText: 'Создаём текст через ChatGPT',
  DraftReady: 'Есть незавершённый подкаст',
  Synthesizing: 'Озвучиваем', Encoding: 'Сжимаем аудио', Saving: 'Сохраняем файл',
  Ready: 'Подкаст сохранён', Cancelling: 'Останавливаем', Cancelled: 'Создание остановлено', Failed: 'Не удалось создать подкаст',
};
const errors: Record<string, string> = {
  ...siwcErrors,
  topic_length: 'Введите тему: от 1 до 500 символов.',
  answer_too_large: 'Ответ длиннее 3000 символов. Попробуйте создать текст заново.',
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
  cleanup_pending: 'Новый файл готов. Предыдущий пока не удалён; очистку повторим перед следующим выпуском.',
  storage_recovery_failed: 'Не удалось восстановить файлы. Проверьте свободное место и повторите.',
  not_enough_space: 'Недостаточно свободного места для безопасного сохранения нового файла.',
  aac_unavailable: 'На устройстве не найден кодировщик AAC.',
  out_of_memory: 'Недостаточно памяти для озвучки.',
  external_player_unavailable: 'Не найдено приложение для этого аудиофайла. Файл сохранён.',
  file_unavailable: 'Сохранённый файл недоступен или удалён.',
  playback_failed: 'Не удалось воспроизвести файл.',
  app_not_foreground: 'Вернитесь в приложение для создания подкаста.',
};
const seconds = (ms: number) => `${((ms || 0) / 1000).toFixed(1)} с`;

export default function PodcastScreen({onBusyChange}: {onBusyChange: (busy: boolean) => void}) {
  const [state, setState] = useState(initial);
  const [session, setSession] = useState(initialSnapshot);
  const [topic, setTopic] = useState('');
  const [model, setModel] = useState('');
  const [showModels, setShowModels] = useState(false);
  const [showText, setShowText] = useState(false);
  const [bridgeError, setBridgeError] = useState(false);
  const inputRestored = useRef(false);
  const catalogAttempted = useRef(false);
  const act = (action: () => Promise<void>) => {
    setBridgeError(false);
    action().catch(() => setBridgeError(true));
  };
  useEffect(() => { onBusyChange(state.busy); }, [state.busy, onBusyChange]);
  useEffect(() => {
    if (state.stage !== 'Loading' && !inputRestored.current) {
      inputRestored.current = true;
      setTopic(state.inputTopic);
      setModel(state.selectedModel);
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
    if (!session.signedIn) { catalogAttempted.current = false; }
    if (session.planEnabled && !session.busy && !session.models.length && !catalogAttempted.current) {
      catalogAttempted.current = true;
      NativeSiwc.listModels().catch(() => setBridgeError(true));
    }
  }, [session.signedIn, session.planEnabled, session.busy, session.models.length]);
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
    return () => { mounted = false; sub.remove(); NativePodcast.pause(); NativePodcast.cancel(); };
  }, []);
  const episode = state.episode;
  const selected = session.models.some(item => item.slug === model) ? model : session.models[0]?.slug ?? '';
  const topicLength = Array.from(topic.trim()).length;
  const busy = state.busy || session.busy;
  const error = state.error || session.error;
  const script = state.script || episode?.script;
  return <SafeAreaView style={styles.safe}>
    <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
      <Text style={styles.title}>Короткий подкаст</Text>
      <Text style={styles.text}>Задайте тему — ChatGPT подготовит русский текст, а телефон озвучит и сохранит его.</Text>
      <View style={styles.card}>
        <Text style={styles.text}>{session.planEnabled ? 'ChatGPT подключён · подписка разрешена' : 'Подключите подписку ChatGPT'}</Text>
        {!session.planEnabled && <Button title="Continue with ChatGPT" disabled={busy}
          onPress={() => act(() => NativeSiwc.signIn())} />}
        {session.busy && !state.busy && <>
          <Text style={styles.hint}>{['Authorizing', 'AwaitingReturn'].includes(session.stage)
            ? 'Завершите вход в браузере и вернитесь в приложение.' : 'Подготавливаем сессию ChatGPT…'}</Text>
          <Button title="Отменить вход или загрузку" onPress={() => NativeSiwc.cancel()} />
        </>}
        <Text style={styles.heading}>О чём рассказать?</Text>
        <TextInput accessibilityLabel="Тема подкаста" multiline editable={!busy} value={topic}
          placeholder="Например, почему море солёное" placeholderTextColor="#657381"
          onChangeText={value => setTopic(Array.from(value).slice(0, 500).join(''))} style={styles.input} />
        <Text style={styles.hint}>{topicLength} / 500 · Только русский язык</Text>
        <Button title="Создать подкаст" disabled={busy || !session.planEnabled || !topicLength || !selected}
          onPress={() => { Keyboard.dismiss(); setShowText(false); NativePodcast.create(topic, selected); }} />
        <Button title={showModels ? 'Скрыть выбор модели' : 'Выбрать текстовую модель'} onPress={() => setShowModels(!showModels)} />
        {showModels && <>
          {session.models.map(item => <Pressable key={item.slug} accessibilityRole="radio"
            accessibilityState={{checked: item.slug === selected, disabled: busy}} disabled={busy}
            onPress={() => setModel(item.slug)} style={styles.model}>
            <Text style={styles.text}>{item.slug === selected ? '● ' : '○ '}{item.name}</Text>
          </Pressable>)}
          <Button title="Обновить список моделей" disabled={busy || !session.planEnabled} onPress={() => act(() => NativeSiwc.listModels())} />
          <Button title="Тест голоса без OpenAI" disabled={busy} onPress={() => NativePodcast.runRussianSample()} />
        </>}
      </View>
      <View style={styles.card}>
        <Text accessibilityRole="header" style={styles.heading}>{labels[state.stage] ?? state.stage}</Text>
        {state.busy && <ActivityIndicator />}
        {state.stage === 'Synthesizing' && <Text style={styles.text}>Фрагмент {state.chunk} из {state.chunks}</Text>}
        {!!error && error !== 'cancelled' && <Text accessibilityRole="alert" style={styles.error}>
          {errors[error] ?? `Код ошибки: ${error}`}
        </Text>}
        {bridgeError && <Text style={styles.error}>Не удалось выполнить команду. Повторите действие.</Text>}
        {state.busy && state.stage !== 'Loading' && <Button title="Отменить создание" disabled={state.stage === 'Cancelling'} onPress={() => NativePodcast.cancel()} />}
        {state.canRetry && !state.busy && <Button title="Продолжить с сохранённого этапа"
          disabled={session.busy && !state.script} onPress={() => NativePodcast.retryAudio()} />}
        {state.canRetry && <Text style={styles.hint}>Продолжение использует прежнюю тему и сохранённый текст или аудио. Чтобы отправить изменённую тему, нажмите «Создать подкаст».</Text>}
        <Text style={styles.hint}>Оставьте приложение открытым. После успешного сохранения новый файл заменит предыдущий.</Text>
      </View>
      {!!episode && <View style={styles.card}>
        <Text style={styles.heading}>Последний файл</Text>
        {!!episode.topic && <Text style={styles.text}>{episode.topic}</Text>}
        <Text selectable style={styles.text}>{episode.name}</Text>
        <Text style={styles.hint}>Podcasts/PodcastGenerator/ · M4A / AAC · {Math.round(episode.bytes / 1024)} КБ</Text>
        <Text style={styles.text}>{seconds(state.positionMs)} / {seconds(episode.durationMs)}</Text>
        <Button title={state.playback === 'Playing' ? 'Пауза' : 'Слушать'} disabled={state.busy}
          onPress={() => state.playback === 'Playing' ? NativePodcast.pause() : NativePodcast.play()} />
        <Button title="Сначала" disabled={state.busy} onPress={() => NativePodcast.restartPlayback()} />
        <Button title="Открыть в другом приложении" disabled={state.busy} onPress={() => NativePodcast.openExternal()} />
        <Text style={styles.hint}>
          {!!episode.textModel && `Текст: ${episode.textModel} · ${seconds(episode.textMs ?? 0)}\n`}
          Подготовка: {seconds(episode.prepareMs)} ({episode.coldInit ? 'холодный запуск' : 'голос уже загружен'})
          {'\n'}Озвучка: {seconds(episode.synthesisMs)} · Сжатие: {seconds(episode.encodingMs)}
          {'\n'}Частота: {episode.sampleRate} Гц · Наблюдаемая PSS: {Math.round(episode.observedPssKb / 1024)} МБ
        </Text>
      </View>}
      {!!script && <View style={styles.card}>
        <Button title={showText ? 'Скрыть текст' : state.script ? 'Показать текст текущего подкаста' : 'Показать текст сохранённого подкаста'} onPress={() => setShowText(!showText)} />
        {showText && <Text selectable style={styles.text}>{script}</Text>}
      </View>}
    </ScrollView>
  </SafeAreaView>;
}

const styles = StyleSheet.create({
  safe: {flex: 1, backgroundColor: '#f3f5f7'},
  content: {padding: 20, gap: 16, paddingBottom: 40},
  title: {fontSize: 28, fontWeight: '700', color: '#132638'},
  heading: {fontSize: 18, fontWeight: '600', color: '#132638'},
  text: {fontSize: 16, lineHeight: 24, color: '#263c4f'},
  hint: {fontSize: 12, lineHeight: 18, color: '#657381'},
  card: {backgroundColor: '#fff', padding: 18, borderRadius: 16, gap: 12},
  input: {minHeight: 90, borderWidth: 1, borderColor: '#ccd5dd', borderRadius: 8, padding: 12, color: '#132638', textAlignVertical: 'top'},
  model: {padding: 10, borderWidth: 1, borderColor: '#e0e5ea', borderRadius: 8},
  error: {color: '#ac2525', lineHeight: 21},
});
