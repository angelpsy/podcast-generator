import React, {useCallback, useState} from 'react';
import {Alert, Pressable, ScrollView, Text, View} from 'react-native';
import {SafeAreaView} from 'react-native-safe-area-context';
import {useFocusEffect} from '@react-navigation/native';
import {createNativeStackNavigator, type NativeStackScreenProps} from '@react-navigation/native-stack';
import LibraryUI from '../specs/NativeLibraryUI';
import Playback from '../specs/NativePlayback';
import {Attach} from './TopicsScreen';
import {profiles} from './contentTypes';
import {Button, sharedStyles, useThemedStyles} from './theme';
import {NameDialog, uiError} from './UIForms';

type Routes = {PlaylistList: undefined; Playlist: {id: string; kind: 'MANUAL' | 'TOPIC'}};
const Stack = createNativeStackNavigator<Routes>();
type Summary = {id: string; title: string; count?: number; podcastCount?: number};
type Item = {id: string; title: string; status: string; profile: string; durationMs: number; position: number};
type Detail = {id: string; title: string; revision: number; items: Item[]; nodeId?: string};
function PlaylistList({navigation}: NativeStackScreenProps<Routes, 'PlaylistList'>) {
  const s = useThemedStyles(sharedStyles); const [data, setData] = useState<{manual: Summary[]; topics: Summary[]}>({manual: [], topics: []});
  const [creating, setCreating] = useState(false); const [error, setError] = useState('');
  const load = useCallback(async () => { try { setData(JSON.parse(await LibraryUI.getPlaylists())); setError(''); } catch (e) { setError(uiError(e)); } }, []);
  useFocusEffect(useCallback(() => { load(); }, [load]));
  return <SafeAreaView edges={['left', 'right']} style={s.safe}><ScrollView contentContainerStyle={s.content}>
    <Text style={s.title}>Плейлисты</Text><Button title="Создать плейлист" onPress={() => setCreating(true)} />
    {!!error && <Text style={s.error}>{error}</Text>}
    <Text style={s.heading}>Мои плейлисты</Text>
    {data.manual.map(p => <Pressable key={p.id} style={s.card} accessibilityRole="button" accessibilityLabel={`Плейлист: ${p.title}`} onPress={() => navigation.navigate('Playlist', {id: p.id, kind: 'MANUAL'})}>
      <Text style={s.heading}>{p.title}</Text><Text style={s.hint}>Записей: {p.count}</Text>
    </Pressable>)}
    {!data.manual.length && !error && <Text style={s.hint}>Создайте плейлист или скопируйте его из топика.</Text>}
    <Text style={s.heading}>Плейлисты топиков</Text>
    {data.topics.map(p => <Pressable key={p.id} style={s.card} accessibilityRole="button" accessibilityLabel={`Плейлист топика: ${p.title}`} onPress={() => navigation.navigate('Playlist', {id: p.id, kind: 'TOPIC'})}>
      <Text style={s.heading}>{p.title}</Text><Text style={s.hint}>По структуре топика · Выпусков всех профилей: {p.podcastCount}</Text>
    </Pressable>)}
  </ScrollView>{creating && <NameDialog title="Новый плейлист" close={() => setCreating(false)} save={async title => {
    const next = JSON.parse(await LibraryUI.mutatePlaylist(JSON.stringify({action: 'create', title}))) as Detail;
    setCreating(false); navigation.navigate('Playlist', {id: next.id, kind: 'MANUAL'});
  }} />}</SafeAreaView>;
}
function PlaylistDetail({route, navigation}: NativeStackScreenProps<Routes, 'Playlist'>) {
  const s = useThemedStyles(sharedStyles); const manual = route.params.kind === 'MANUAL';
  const [data, setData] = useState<Detail | null>(null); const [error, setError] = useState(''); const [busy, setBusy] = useState(false);
  const [all, setAll] = useState(false); const [name, setName] = useState<'rename' | 'copy' | null>(null); const [attach, setAttach] = useState(false); const [editing, setEditing] = useState(false);
  const load = useCallback(async () => {
    setBusy(true); try {
      if (manual) { setData(JSON.parse(await LibraryUI.getPlaylist(route.params.id))); }
      else { const next = JSON.parse(await LibraryUI.getTopicPlaylist(route.params.id, '', all)); setData({id: next.topic.id, title: next.topic.title, revision: next.topic.revision, nodeId: next.selectedNodeId, items: next.playlist}); }
      setError('');
    } catch (e) { setError(uiError(e)); } finally { setBusy(false); }
  }, [route.params.id, manual, all]);
  useFocusEffect(useCallback(() => { load(); }, [load]));
  const mutate = async (action: string, values: object = {}) => {
    if (!data) { return; } setBusy(true); setError('');
    try { const raw = await LibraryUI.mutatePlaylist(JSON.stringify({action, id: data.id, expectedRevision: data.revision, ...values})); if (action !== 'delete') { setData(JSON.parse(raw)); } }
    finally { setBusy(false); }
  };
  const run = (action: () => Promise<unknown>) => { action().catch(e => setError(uiError(e))); };
  return <SafeAreaView edges={['left', 'right']} style={s.safe}><ScrollView contentContainerStyle={s.content}>
    <Button title="Все плейлисты" onPress={() => navigation.goBack()} /><Text style={s.title}>{data?.title ?? 'Плейлист'}</Text>
    <Button title={editing ? 'Готово' : '⋯'} accessibilityLabel="Действия с плейлистом" variant="quiet" onPress={() => setEditing(!editing)} />{!!error && <Text accessibilityRole="alert" style={s.error}>{error}</Text>}
    {data && <>
      {!manual && <><Text style={s.hint}>Плейлист следует дереву. Для своего порядка создайте копию.</Text>
        <Button title={all ? 'Все языки · изменить' : 'Язык топика · изменить'} disabled={busy} onPress={() => setAll(!all)} />
        <Button title="Открыть топик" onPress={() => navigation.getParent()?.navigate('Topics', {screen: 'Topic', params: {id: data.id}})} />
        <Button title="Скопировать в свой плейлист" disabled={busy} onPress={() => setName('copy')} /></>}
      <Button variant="primary" title="Слушать плейлист" disabled={busy || !data.items.some(i => i.status === 'READY')} onPress={() => run(() => manual ? LibraryUI.playPlaylist(data.id) : LibraryUI.playTopicPlaylist(data.id, '', all))} />
      {editing && <Button title="Обновить очередь из плейлиста" disabled={busy || !data.items.some(i => i.status === 'READY')} onPress={() => run(() => manual ? LibraryUI.updatePlaylistQueue(data.id) : LibraryUI.updateTopicQueue(data.id, '', all))} />}
      <Text style={s.hint}>Изменения сохраняются для следующего запуска. Уже загруженная очередь не меняется.</Text>
      {manual && editing && <><Button title="Переименовать плейлист" disabled={busy} onPress={() => setName('rename')} />
        <Button title="Добавить из библиотеки" disabled={busy} onPress={() => setAttach(true)} />
        <Button title="Удалить плейлист" disabled={busy} onPress={() => Alert.alert('Удалить плейлист?', 'Удалятся только список и его ссылки. Подкасты и аудиофайлы останутся.',
          [{text: 'Отмена', style: 'cancel'}, {text: 'Удалить', style: 'destructive', onPress: () => run(async () => { await mutate('delete'); navigation.goBack(); })}])} /></>}
      {data.items.map((item, index) => <View key={item.id} style={s.card}>
        <Text style={s.heading}>{index + 1}. {item.title}</Text><Text style={s.hint}>{profiles[item.profile]} · {(item.durationMs / 1000).toFixed(1)} с · {item.status === 'READY' ? 'Готов' : 'Файл недоступен'}</Text>
        <Button title="Слушать запись" disabled={item.status !== 'READY'} onPress={() => Playback.playEpisode(item.id)} />
        {manual && editing && <><View style={s.row}>
          <Button title={`Выше: ${item.title}`} disabled={busy || index === 0} onPress={() => run(() => mutate('move', {podcastId: item.id, position: index - 1}))} />
          <Button title={`Ниже: ${item.title}`} disabled={busy || index === data.items.length - 1} onPress={() => run(() => mutate('move', {podcastId: item.id, position: index + 1}))} /></View>
          <Button title="Убрать из плейлиста" disabled={busy} onPress={() => Alert.alert('Убрать запись?', 'Аудиофайл и выпуск останутся в библиотеке.',
            [{text: 'Отмена', style: 'cancel'}, {text: 'Убрать', onPress: () => run(() => mutate('remove', {podcastId: item.id}))}])} /></>}
      </View>)}
      {!data.items.length && !error && <Text style={s.hint}>Записей пока нет.</Text>}
    </>}
  </ScrollView>
    {attach && data && <Attach attached={data.items.map(i => i.id)} close={() => setAttach(false)} attach={id => mutate('add', {podcastId: id})} />}
    {name && data && <NameDialog title={name === 'copy' ? 'Копия плейлиста топика' : 'Название плейлиста'} initial={data.title} close={() => setName(null)} save={async title => {
      if (name === 'copy') { const next = JSON.parse(await LibraryUI.mutatePlaylist(JSON.stringify({action: 'copyTopic', title, topicId: data.id, nodeId: data.nodeId, expectedRevision: data.revision, allProfiles: all}))) as Detail;
        setName(null); navigation.replace('Playlist', {id: next.id, kind: 'MANUAL'});
      } else { await mutate('rename', {title}); setName(null); }
    }} />}
  </SafeAreaView>;
}
export default function PlaylistsNavigator() { return <Stack.Navigator screenOptions={{headerShown: false}}><Stack.Screen name="PlaylistList" component={PlaylistList} /><Stack.Screen name="Playlist" component={PlaylistDetail} /></Stack.Navigator>; }
