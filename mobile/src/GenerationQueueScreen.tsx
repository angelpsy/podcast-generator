import React, {useState} from 'react';
import {ActivityIndicator, ScrollView, StyleSheet, Text, View} from 'react-native';
import {SafeAreaView} from 'react-native-safe-area-context';
import Podcast from '../specs/NativePodcast';
import {Button, useThemedStyles} from './theme';
import {profiles} from './contentTypes';
import {generationErrors, phaseLabels, statusLabels} from './generationLabels';
import {useGenerationQueue} from './generationQueue';

export default function GenerationQueueScreen() {
  const s = useThemedStyles(styles); const {state, loaded, failed, recovering, canControl, reload} = useGenerationQueue();
  const [commandError, setCommandError] = useState(false);
  const items = state.generationQueue;
  const resumable = items.some(item => ['QUEUED', 'PAUSED'].includes(item.status));
  const command = (action: () => void) => { setCommandError(false); try { action(); } catch { setCommandError(true); } };
  const groups = [
    {title: 'Создаётся', items: items.filter(item => item.status === 'RUNNING')},
    {title: 'Ожидают', items: items.filter(item => item.status === 'QUEUED')},
    {title: 'Требуют внимания', items: items.filter(item => ['PAUSED', 'FAILED'].includes(item.status))},
  ];
  return <SafeAreaView edges={['left', 'right']} style={s.safe}><ScrollView contentContainerStyle={s.content}>
    <Text style={s.title}>Очередь создания</Text>
    <Text style={s.text}>Задания из всех топиков и отдельные выпуски. План топика хранится во вкладке «Топики», готовые записи — в «Подкастах».</Text>
    {!loaded && !failed && <ActivityIndicator />}
    {failed && <><Text accessibilityRole="alert" style={s.error}>Не удалось загрузить очередь.</Text><Button title="Повторить загрузку очереди" disabled={recovering} onPress={reload} /></>}
    {recovering && <><ActivityIndicator /><Text style={s.hint}>Восстанавливаем очередь…</Text></>}
    {commandError && <Text accessibilityRole="alert" style={s.error}>Не удалось выполнить команду. Повторите попытку.</Text>}
    {state.error === 'generation_storage_failed' && <Text accessibilityRole="alert" style={s.error}>{generationErrors[state.error]}</Text>}
    {loaded && !failed && !recovering && !items.length && <View style={s.card}><Text style={s.heading}>Очередь пуста</Text><Text style={s.hint}>Добавьте выпуск из топика или кнопкой «Новый подкаст» в библиотеке. Готовые задания исчезают из очереди, записи сохраняются в библиотеке.</Text></View>}
    {!!items.length && <>
      <Text style={s.heading}>Незавершённых: {items.length} / 100</Text>
      {state.busy ? <Button title="Приостановить очередь" disabled={!canControl} onPress={() => command(() => Podcast.cancel())} />
        : <Button title="Продолжить очередь" disabled={!canControl || !resumable} onPress={() => command(() => Podcast.resumeQueue(''))} />}
      <Text style={s.hint}>По одному выпуску за раз. Можно добавлять новые задания во время создания. Для генерации оставьте приложение открытым.</Text>
      {groups.filter(group => group.items.length).map(group => <View key={group.title} style={s.group}>
        <Text style={s.heading}>{group.title} · {group.items.length}</Text>
        {group.items.map((item, index) => <View key={item.id} style={s.card}>
          <Text style={s.text}>{item.status === 'QUEUED' ? `${index + 1}. ` : ''}{item.title}</Text>
          <Text style={s.hint}>{profiles[item.profile] ?? item.profile} · {statusLabels[item.status] ?? item.status} · {phaseLabels[item.phase] ?? item.phase}
            {item.status === 'RUNNING' && item.phase === 'Synthesizing' && state.chunks > 0 ? ` · ${state.chunk} / ${state.chunks}` : ''}</Text>
          {!!item.error && <Text style={s.error}>{generationErrors[item.error] ?? 'Создание не завершилось. Сохранённые этапы можно продолжить.'}</Text>}
          {['PAUSED', 'FAILED'].includes(item.status) && <>
            <Text style={s.hint}>{item.hasScript ? 'Сценарий сохранён, повторный запрос не нужен.' : 'Сценарий ещё не сохранён. При продолжении текст будет запрошен заново.'}</Text>
            <Button title="Продолжить этот выпуск" accessibilityLabel={`Продолжить выпуск: ${item.title}`} disabled={!canControl || state.busy} onPress={() => command(() => Podcast.resumeQueue(item.id))} />
          </>}
          {item.status !== 'RUNNING' && <Button title="Убрать из очереди" accessibilityLabel={`Убрать задание: ${item.title}`} disabled={!canControl} onPress={() => command(() => Podcast.cancelTask(item.id))} />}
        </View>)}
      </View>)}
    </>}
  </ScrollView></SafeAreaView>;
}
const styles = StyleSheet.create({safe: {flex: 1, backgroundColor: '#f3f5f7'}, content: {padding: 20, gap: 16, paddingBottom: 36},
  title: {fontSize: 30, fontWeight: '700', color: '#132638'}, heading: {fontSize: 18, fontWeight: '600', color: '#132638'},
  text: {fontSize: 16, lineHeight: 24, color: '#263c4f'}, hint: {fontSize: 12, lineHeight: 18, color: '#657381'},
  error: {color: '#ac2525', lineHeight: 22}, card: {padding: 16, gap: 10, borderRadius: 20, backgroundColor: '#fff'}, group: {gap: 12}});
