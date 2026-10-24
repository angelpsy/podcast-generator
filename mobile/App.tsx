import React, {useEffect, useState} from 'react';
import {
  ActivityIndicator,
  Button,
  Platform,
  Pressable,
  ScrollView,
  Share,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import {SafeAreaProvider, SafeAreaView} from 'react-native-safe-area-context';
import NativeSiwc from './specs/NativeSiwc';
import {initialSnapshot, parseSnapshot, safeDiagnostics, type Snapshot} from './src/state';
import PodcastScreen from './src/PodcastScreen';
import {siwcErrors as errors} from './src/siwcErrors';

const stages: Record<string, string> = {
  SignedOut: 'Вход не выполнен',
  Authorizing: 'Завершите вход в браузере и вернитесь сюда',
  Validating: 'Проверка входа и разрешений',
  AwaitingReturn: 'Вернитесь в приложение для завершения входа',
  SignedInWithoutPlan: 'Вход выполнен, подписка не разрешена',
  Ready: 'Подключено',
  LoadingModels: 'Загрузка моделей',
  Refreshing: 'Обновление сессии',
  Streaming: 'Получение ответа',
  Completed: 'Запрос успешно завершён',
  Incomplete: 'Ответ не завершён',
  Cancelled: 'Операция отменена',
  SigningOut: 'Выход из аккаунта',
  Failed: 'Операция не выполнена',
};



function SiwcScreen() {
  const [snapshot, setSnapshot] = useState<Snapshot>(initialSnapshot);
  const [model, setModel] = useState('');
  const [prompt, setPrompt] = useState('Ответь одним коротким предложением: связь работает.');
  const [bridgeError, setBridgeError] = useState(false);

  useEffect(() => {
    let mounted = true;
    const apply = (raw: string) => {
      const next = parseSnapshot(raw);
      if (mounted) {
        setSnapshot(previous => next.revision >= previous.revision ? next : previous);
      }
    };
    const subscription = NativeSiwc.onSnapshot(apply);
    NativeSiwc.getSnapshot().then(apply).catch(() => setBridgeError(true));
    return () => { mounted = false; subscription.remove(); };
  }, []);

  const act = (action: () => Promise<void>) => {
    setBridgeError(false);
    action().catch(() => setBridgeError(true));
  };
  const selected = snapshot.models.some(item => item.slug === model)
    ? model : snapshot.models[0]?.slug ?? '';
  const errorText = snapshot.error === 'cancelled' ? ''
    : errors[snapshot.error] ?? (snapshot.error ? `Код ошибки: ${snapshot.error}` : '');

  return (
    <SafeAreaView style={styles.safe}>
      <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
        <Text style={styles.eyebrow}>ANDROID · SIWC SPIKE</Text>
        <Text style={styles.title}>Проверка подписки ChatGPT</Text>
        <Text style={styles.description}>Вход, разрешение на подписку и один короткий ответ OpenAI.</Text>
        <View style={styles.card}>
          <Text accessibilityRole="header" style={styles.heading}>{stages[snapshot.stage] ?? snapshot.stage}</Text>
          {snapshot.busy && <ActivityIndicator style={styles.spinner} />}
          {!!snapshot.email && <Text style={styles.text}>{snapshot.email}</Text>}
          {snapshot.signedIn && !snapshot.email && <Text style={styles.text}>Аккаунт подключён</Text>}
          <Text style={styles.text}>Подписка: {snapshot.planEnabled ? 'разрешена' : 'не подключена'}</Text>
          {!!errorText && <Text accessibilityRole="alert" style={styles.error}>{errorText}</Text>}
          {!!snapshot.error && !!snapshot.responseContentType && <Text style={styles.hint}>
            HTTP {snapshot.httpStatus} · {snapshot.responseContentType} · {snapshot.responseFormat}
          </Text>}
          {bridgeError && <Text accessibilityRole="alert" style={styles.error}>Не удалось выполнить команду. Попробуйте ещё раз.</Text>}
          <Button title="Continue with ChatGPT" disabled={snapshot.busy}
            onPress={() => act(() => NativeSiwc.signIn())} />
          <Text style={styles.hint}>Вход откроется в браузере телефона. После входа вернитесь в приложение.</Text>
          <Button title="Обновить сессию" disabled={snapshot.busy || !snapshot.signedIn}
            onPress={() => act(() => NativeSiwc.refreshSession())} />
          {snapshot.busy && <Button title="Отменить" onPress={() => NativeSiwc.cancel()} />}
        </View>
        <View style={styles.card}>
          <Button title="Загрузить модели" disabled={snapshot.busy || !snapshot.planEnabled}
            onPress={() => act(() => NativeSiwc.listModels())} />
          {snapshot.models.map(item => (
            <Pressable key={item.slug} accessibilityRole="radio" accessibilityState={{checked: item.slug === selected, disabled: snapshot.busy}}
              disabled={snapshot.busy} style={[styles.model, item.slug === selected && styles.selectedModel]}
              onPress={() => setModel(item.slug)}>
              <Text style={styles.text}>{item.slug === selected ? '● ' : '○ '}{item.name}</Text>
            </Pressable>
          ))}
          <Text style={styles.label}>Тестовый запрос</Text>
          <TextInput accessibilityLabel="Тестовый запрос" multiline maxLength={4000}
            editable={!snapshot.busy} value={prompt} onChangeText={setPrompt} style={styles.input} />
          <Button title="Получить ответ" disabled={snapshot.busy || !snapshot.planEnabled || !selected || !prompt.trim()}
            onPress={() => act(() => NativeSiwc.sendPrompt(selected, prompt))} />
        </View>
        {!!snapshot.answer && <View style={styles.card}>
          <Text style={styles.heading}>Ответ</Text>
          <Text selectable style={styles.answer}>{snapshot.answer}</Text>
          <Text style={styles.hint}>{snapshot.stage === 'Completed' ? 'response.completed получен' : 'Завершение ещё не подтверждено'}</Text>
        </View>}
        <Button title="Поделиться диагностикой" onPress={() => {
          Share.share({message: safeDiagnostics(snapshot, String(Platform.Version))}).catch(() => setBridgeError(true));
        }} />
        <Button title="Выйти" disabled={snapshot.busy || !snapshot.signedIn}
          onPress={() => act(() => NativeSiwc.signOut())} />
        <Text style={styles.hint}>Прототип для локального тестирования. Диагностика не содержит токенов, email и текста ответа.</Text>
      </ScrollView>
    </SafeAreaView>
  );
}

export default function App() {
  const [showSiwc, setShowSiwc] = useState(false);
  const [podcastBusy, setPodcastBusy] = useState(false);
  return <SafeAreaProvider>
    <View style={showSiwc ? styles.hidden : styles.screen}>
      <PodcastScreen onBusyChange={setPodcastBusy} />
    </View>
    {showSiwc && <SiwcScreen />}
    <SafeAreaView edges={['bottom']}>
      <Button disabled={podcastBusy} title={showSiwc ? 'Вернуться к подкасту' : 'Настройки SIWC'} onPress={() => setShowSiwc(!showSiwc)} />
    </SafeAreaView>
  </SafeAreaProvider>;
}

const styles = StyleSheet.create({
  screen: {flex: 1},
  hidden: {display: 'none'},
  safe: {flex: 1, backgroundColor: '#f3f5f7'},
  content: {padding: 20, gap: 16, paddingBottom: 40},
  eyebrow: {fontSize: 11, letterSpacing: 2, color: '#546475', fontWeight: '700'},
  title: {fontSize: 28, fontWeight: '700', color: '#132638'},
  description: {fontSize: 16, lineHeight: 23, color: '#526170'},
  card: {backgroundColor: '#fff', padding: 18, borderRadius: 16, gap: 14},
  heading: {fontSize: 18, fontWeight: '600', color: '#132638'},
  text: {fontSize: 15, color: '#263c4f'},
  label: {fontSize: 14, fontWeight: '600', color: '#263c4f'},
  hint: {fontSize: 12, lineHeight: 18, color: '#657381'},
  error: {color: '#ac2525', lineHeight: 21},
  spinner: {alignSelf: 'flex-start'},
  input: {minHeight: 90, borderWidth: 1, borderColor: '#ccd5dd', borderRadius: 8, padding: 12, color: '#132638', textAlignVertical: 'top'},
  model: {padding: 12, borderRadius: 8, borderWidth: 1, borderColor: '#e0e5ea'},
  selectedModel: {borderColor: '#277ca4', backgroundColor: '#eaf5fb'},
  answer: {fontSize: 17, lineHeight: 26, color: '#132638'},
});
