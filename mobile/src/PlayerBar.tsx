import {Button, TextInput, useApp, useThemedStyles} from './theme';
import React, {useState} from 'react';
import {Modal, Pressable, ScrollView, StyleSheet, Text, View} from 'react-native';
import {SafeAreaView} from 'react-native-safe-area-context';
import Playback from '../specs/NativePlayback';
import {playbackProgress} from './playbackMath';
import {playbackTime, usePlayback} from './playback';

const messages: Record<string, string> = {
  queue_items_skipped: 'Недоступные записи пропущены.', file_unavailable: 'Не удалось прочитать запись. Можно повторить или выбрать другую.',
  playback_restore_failed: 'Не удалось восстановить очередь. Выберите выпуск или запустите библиотеку заново.',
  playback_save_failed: 'Не удалось сохранить позицию прослушивания.', queue_load_failed: 'Не удалось загрузить очередь. Проверьте файлы и повторите.',
};
export default function PlayerBar() {
  const styles = useThemedStyles(styleDefinitions); const {colors} = useApp(); const state = usePlayback();
  const [expanded, setExpanded] = useState(false); const [queueOpen, setQueueOpen] = useState(false);
  const [seekOpen, setSeekOpen] = useState(false); const [seek, setSeek] = useState(''); const [width, setWidth] = useState(1);
  if (!state.queue.length && !state.error) { return null; }
  const toggle = () => state.playing ? Playback.pause() : Playback.play();
  const progress = playbackProgress(state.positionMs, state.durationMs);
  const transport = (title: string, label: string, action: () => void, primary = false) => <Button title={title} accessibilityLabel={label} variant={primary ? 'primary' : 'quiet'} onPress={action} disabled={!state.id} />;
  const playButton = (large = false) => <Pressable accessibilityRole="button" accessibilityLabel={state.playing ? 'Пауза' : 'Продолжить'}
    accessibilityState={{disabled: !state.id}} disabled={!state.id} onPress={toggle}
    style={({pressed}) => [large ? styles.playLarge : styles.playSmall, {backgroundColor: large ? colors.button : colors.bar, opacity: !state.id ? 0.45 : pressed ? 0.8 : 1}]}>
    <Text style={[large ? styles.playGlyphLarge : styles.playGlyph, {color: large ? colors.buttonText : colors.text}]}>{state.playing ? 'Ⅱ' : '▶'}</Text>
  </Pressable>;
  return <View style={styles.bar}>
    <View style={styles.miniRow}><Pressable style={styles.summary} accessibilityRole="button" accessibilityLabel={`Открыть плеер: ${state.title || 'Очередь'}`} onPress={() => setExpanded(true)}>
      <Text numberOfLines={1} style={styles.miniTitle}>{state.title || 'Плеер'}</Text>
      <Text style={styles.hint}>{state.playing ? 'Воспроизводится' : 'Продолжить'} · {playbackTime(state.positionMs)} / {playbackTime(state.durationMs)}</Text>
    </Pressable>{playButton()}</View>
    <View accessible accessibilityRole="progressbar" accessibilityLabel="Прогресс прослушивания" accessibilityValue={{min: 0, max: 100, now: Math.round(progress * 100), text: `${playbackTime(state.positionMs)} из ${playbackTime(state.durationMs)}`}} style={styles.miniTrack}>
      <View style={[styles.fill, {width: `${progress * 100}%`, backgroundColor: colors.accent}]} />
    </View>
    <Modal visible={expanded} onRequestClose={() => setExpanded(false)} animationType="slide">
      <SafeAreaView style={styles.safe}><ScrollView contentContainerStyle={styles.content}>
        <View style={styles.header}><Button title="⌄" accessibilityLabel="Закрыть плеер" variant="quiet" onPress={() => setExpanded(false)} /><Text style={styles.hint}>СЕЙЧАС В ОЧЕРЕДИ · {state.index + 1} / {state.queue.length}</Text></View>
        <View style={styles.cover}><Text style={styles.hint}>PODCAST GENERATOR</Text><Text numberOfLines={5} style={styles.coverTitle}>{state.title || 'Ваша аудиобиблиотека'}</Text><Text style={styles.hint}>Сохранено на устройстве</Text></View>
        <Text style={styles.title}>{state.title || 'Очередь пуста'}</Text>
        <Pressable accessibilityRole="adjustable" accessibilityLabel="Позиция воспроизведения"
          accessibilityValue={{min: 0, max: Math.max(1, Math.round(state.durationMs / 1000)), now: Math.round(state.positionMs / 1000), text: playbackTime(state.positionMs)}}
          accessibilityActions={[{name: 'increment', label: 'Вперёд на 15 секунд'}, {name: 'decrement', label: 'Назад на 15 секунд'}]}
          onAccessibilityAction={e => Playback.skip(e.nativeEvent.actionName === 'increment' ? 15000 : -15000)}
          onLayout={e => setWidth(e.nativeEvent.layout.width)} disabled={!state.id || !state.durationMs}
          onPress={e => Playback.seek(Math.max(0, Math.min(1, e.nativeEvent.locationX / width)) * state.durationMs)} style={styles.seekTarget}>
          <View pointerEvents="none" style={styles.track}><View style={[styles.fill, {width: `${progress * 100}%`, backgroundColor: colors.accent}]} /></View>
        </Pressable>
        <View style={styles.header}><Text style={styles.hint}>{playbackTime(state.positionMs)}</Text><Text style={styles.hint}>{playbackTime(state.durationMs)}</Text></View>
        <View style={styles.transport}>
          {transport('|◀', 'Предыдущий выпуск', () => Playback.previous())}
          {transport('−15', 'Назад на 15 секунд', () => Playback.skip(-15000))}
          {playButton(true)}
          {transport('+15', 'Вперёд на 15 секунд', () => Playback.skip(15000))}
          {transport('▶|', 'Следующий выпуск', () => Playback.next())}
        </View>
        <View style={styles.row}><Button title={`${state.speed}×`} accessibilityLabel={`Скорость ${state.speed}. Изменить`} onPress={() => Playback.setSpeed(state.speed >= 2 ? 0.75 : state.speed + 0.25)} />
          <Button title={`Очередь · ${state.queue.length}`} onPress={() => setQueueOpen(!queueOpen)} />
          <Button title={`Повтор: ${['выкл.', 'выпуск', 'всё'][state.repeat]}`} onPress={() => Playback.setRepeat((state.repeat + 1) % 3)} /></View>
        <Button title={seekOpen ? 'Скрыть точную перемотку' : 'Перейти к точной позиции'} variant="quiet" onPress={() => setSeekOpen(!seekOpen)} />
        {seekOpen && <><TextInput accessibilityLabel="Позиция в секундах" keyboardType="numeric" value={seek} onChangeText={setSeek} style={styles.input} placeholder="Секунды, например 30" />
          <Button title="Перейти" disabled={!state.id || !seek.trim() || !Number.isFinite(Number(seek)) || Number(seek) < 0}
            onPress={() => { Playback.seek(Number(seek) * 1000); setSeek(''); }} /></>}
        {!!state.error && <Text accessibilityRole="alert" style={styles.error}>{messages[state.error] ?? 'Не удалось выполнить команду плеера. Повторите действие.'}</Text>}
        {queueOpen && state.queue.map((item, index) => <View key={item.id} style={styles.item}><Text style={styles.text}>{index === state.index ? '▶ ' : ''}{index + 1}. {item.title}</Text></View>)}
        <Text style={styles.hint}>Можно выключить экран и продолжить слушать.</Text>
      </ScrollView></SafeAreaView>
    </Modal>
  </View>;
}
const styleDefinitions = StyleSheet.create({
  bar: {marginHorizontal: 12, marginTop: 6, backgroundColor: '#eaf1f6', borderRadius: 16, overflow: 'hidden'},
  miniRow: {paddingHorizontal: 12, paddingVertical: 6, flexDirection: 'row', alignItems: 'center', gap: 8}, miniTrack: {height: 5, backgroundColor: '#ccd5dd'},
  playSmall: {width: 48, height: 48, borderRadius: 24, alignItems: 'center', justifyContent: 'center', flexShrink: 0},
  playLarge: {width: 64, height: 64, borderRadius: 32, alignItems: 'center', justifyContent: 'center', flexShrink: 0},
  playGlyph: {fontSize: 20, textAlign: 'center', includeFontPadding: false}, playGlyphLarge: {fontSize: 28, textAlign: 'center', includeFontPadding: false},
  summary: {flex: 1, minHeight: 48, justifyContent: 'center'}, miniTitle: {color: '#132638', fontSize: 14, fontWeight: '600'},
  safe: {flex: 1, backgroundColor: '#f3f5f7'}, content: {padding: 22, gap: 16, paddingBottom: 32},
  row: {flexDirection: 'row', flexWrap: 'wrap', gap: 8}, header: {flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center'},
  transport: {flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 2}, title: {color: '#132638', fontWeight: '600', fontSize: 28},
  cover: {backgroundColor: '#eaf1f6', borderRadius: 24, padding: 24, minHeight: 230, gap: 22, justifyContent: 'space-between'}, coverTitle: {fontSize: 30, color: '#132638', fontWeight: '600'},
  fill: {height: 5}, seekTarget: {height: 48, justifyContent: 'center'}, track: {height: 5, backgroundColor: '#ccd5dd', borderRadius: 4, overflow: 'hidden'},
  text: {color: '#263c4f', fontSize: 16}, hint: {color: '#657381', fontSize: 12},
  input: {borderWidth: 1, borderColor: '#ccd5dd', borderRadius: 14, padding: 12, color: '#132638'},
  item: {padding: 12, backgroundColor: '#fff', borderRadius: 14}, error: {color: '#ac2525'},
});
