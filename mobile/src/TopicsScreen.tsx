import {Button, TextInput, useThemedStyles} from './theme';
import React, {useCallback, useEffect, useRef, useState} from 'react';
import {ActivityIndicator, Alert, BackHandler, Keyboard, Modal, Pressable, RefreshControl, ScrollView, StyleSheet, Text, View} from 'react-native';
import {SafeAreaView} from 'react-native-safe-area-context';
import {useFocusEffect} from '@react-navigation/native';
import {createNativeStackNavigator, type NativeStackScreenProps} from '@react-navigation/native-stack';
import Topics from '../specs/NativeTopics';
import Podcast from '../specs/NativePodcast';
import LibraryUI from '../specs/NativeLibraryUI';
import {ContextMenu, NameDialog} from './UIForms';
import {useApp} from './theme';
import Playback from '../specs/NativePlayback';
import OutlinePanel from './OutlinePanel';
import ContentEditor, {defaultContent, profiles, type Content} from './ContentSettings';
import GenerationPanel from './GenerationPanel';

type Routes = {TopicList: undefined; Topic: {id: string}};
const Stack = createNativeStackNavigator<Routes>();
type Topic = {id: string; title: string; goal: string; rootNodeId: string; outlineDepth: number; revision: number; languagePolicy?: string; contentSettings?: Content; nodeCount?: number; podcastCount?: number};
type Node = {id: string; title: string; description: string; kind: string; parentId: string | null; position: number};
type Episode = {profile?: string; id: string; title: string; status: string; durationMs?: number; createdAt?: number};
type Link = Episode & {nodeId: string; role: string; profile: string; position: number};
type Detail = {topic: Topic; nodes: Node[]; links: Link[]; playlist: Episode[]; selectedNodeId: string};
const errors: Record<string, string> = {
  revision_conflict: 'Структура уже изменилась. Закройте форму и обновите топик перед повторным редактированием.',
  root_locked: 'Корень нельзя переместить или удалить отдельно. Измените или удалите весь топик.',
  cycle: 'Нельзя перенести узел внутрь собственной ветки.', max_depth: 'Допустимо не более четырёх уровней ниже топика.',
  node_limit: 'В одном топике допускается до 200 узлов вместе с корнем.',
  question_has_no_children: 'Вопрос не может содержать дочерние узлы. Используйте раздел.',
  cross_topic_move: 'Перенос возможен только внутри текущего топика.',
  topic_not_found: 'Топик удалён. Вернитесь к списку.', node_not_found: 'Узел уже удалён. Обновите топик.',
  text_length: 'Введите название до 200 символов и описание до 2000 символов.',
  podcast_not_found: 'Выпуск удалён из библиотеки. Обновите поиск.',
  podcast_not_ready: 'Можно прикреплять только готовые записи библиотеки.',
  empty_playlist: 'В этой ветке нет готовых выпусков для прослушивания.',
  link_limit: 'К одному узлу можно прикрепить до 50 выпусков.', playlist_limit: 'В очередь можно загрузить до 1000 выпусков.',
  queue_load_failed: 'Не удалось открыть очередь. Повторите действие.',
};
function message(error: unknown) { return errors[(error as {code?: string})?.code ?? ''] ?? 'Не удалось прочитать или сохранить данные. Повторите действие.'; }
const cut = (value: string, limit: number) => Array.from(value).slice(0, limit).join('');
const episodeInfo = (item: Episode) => [item.durationMs ? `${(item.durationMs / 1000).toFixed(1)} с` : '', item.createdAt ? new Date(item.createdAt).toLocaleString('ru-RU') : ''].filter(Boolean).join(' · ');
const kindLabel = (kind: string) => ({ROOT: 'Топик', SECTION: 'Раздел', QUESTION: 'Вопрос'}[kind] ?? kind);
const ordered = <T extends {position: number}>(items: T[]) => [...items].sort((a, b) => a.position - b.position);

function Sheet({title, children, close, busy = false}: {title: string; children: React.ReactNode; close: () => void; busy?: boolean}) {
  const styles = useThemedStyles(styleDefinitions);
  return <Modal visible animationType="slide" onRequestClose={() => { if (!busy) { close(); } }}>
    <SafeAreaView style={styles.safe}><ScrollView keyboardShouldPersistTaps="handled" contentContainerStyle={styles.content}>
      <Text style={styles.title}>{title}</Text>
      <Button title="Закрыть форму" disabled={busy} onPress={close} />{children}
    </ScrollView></SafeAreaView>
  </Modal>;
}
function Editor({topic, node, save, close, initialKind = 'SECTION'}: {topic?: Topic; node?: Node; initialKind?: string; save: (title: string, description: string, kind: string, profile: string, settings: Content) => Promise<void>; close: () => void}) {
  const styles = useThemedStyles(styleDefinitions);
  const [title, setTitle] = useState(node?.title ?? topic?.title ?? '');
  const [description, setDescription] = useState(node?.description ?? topic?.goal ?? '');
  const [kind, setKind] = useState(node?.kind ?? initialKind);
  const [profile, setProfile] = useState(topic?.languagePolicy ?? 'ru_standard');
  const [settings, setSettings] = useState(topic?.contentSettings ?? defaultContent);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  return <Sheet title={topic ? (topic.id ? 'Изменить топик' : 'Новый топик') : node ? 'Изменить узел' : 'Добавить узел'} busy={busy} close={close}>
    <Text style={styles.text}>Название</Text>
    <TextInput accessibilityLabel="Название" value={title} editable={!busy} style={styles.input}
      onChangeText={v => setTitle(cut(v, 200))} placeholder="Название темы или вопрос" placeholderTextColor="#657381" />
    <Text style={styles.text}>{topic ? 'Цель и контекст' : 'Описание'}</Text>
    <TextInput accessibilityLabel="Описание" value={description} editable={!busy} multiline style={styles.multiline}
      onChangeText={v => setDescription(cut(v, 2000))} />
    {!topic && <View style={styles.row}>{['SECTION', 'QUESTION'].map(value => <Button key={value}
      title={`${kind === value ? '● ' : ''}${kindLabel(value)}`} disabled={busy} onPress={() => setKind(value)} />)}</View>}
    <Text style={styles.hint}>До четырёх уровней ниже топика. Вопрос — конечный узел, раздел может содержать другие узлы.</Text>
    {!!topic && <ContentEditor profile={profile} settings={settings} onProfile={setProfile} onSettings={setSettings} disabled={busy} />}
    {!!error && <Text accessibilityRole="alert" style={styles.error}>{error}</Text>}
    {busy && <ActivityIndicator />}
    <Button variant="primary" title="Сохранить" disabled={busy || !title.trim()} onPress={() => {
      Keyboard.dismiss(); setBusy(true); setError('');
      save(title, description, kind, profile, settings).catch(e => setError(message(e))).finally(() => setBusy(false));
    }} />
  </Sheet>;
}

function TopicList({navigation}: NativeStackScreenProps<Routes, 'TopicList'>) {
  const styles = useThemedStyles(styleDefinitions);
  const {prefs} = useApp();
  const [topics, setTopics] = useState<Topic[]>([]);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [creating, setCreating] = useState(false); const [menuId, setMenuId] = useState<string | null>(null);
  const reload = useCallback(() => {
    setBusy(true); setError('');
    return Topics.getTopics().then(raw => setTopics(JSON.parse(raw).topics as Topic[]))
      .catch(e => setError(message(e))).finally(() => setBusy(false));
  }, []);
  useFocusEffect(useCallback(() => { reload(); }, [reload]));
  const menuTopic = topics.find(t => t.id === menuId);
  return <SafeAreaView edges={['left', 'right']} style={styles.safe}>
    <ScrollView refreshControl={<RefreshControl refreshing={busy} onRefresh={reload} />} contentContainerStyle={styles.content}>
      <Text style={styles.title}>Что изучим?</Text>
      <Text style={styles.text}>Соберите тему по частям. Слушайте в своём темпе.</Text>
      <Text style={styles.heading}>Мои топики</Text>
      {busy && <ActivityIndicator />}{!!error && <Text accessibilityRole="alert" style={styles.error}>{error}</Text>}
      {!topics.length && !busy && !error && <Text style={styles.hint}>Топиков пока нет.</Text>}
      {topics.map(t => <View key={t.id} style={styles.card}>
        <View style={styles.tileRow}><Pressable style={styles.grow} accessibilityRole="button" accessibilityLabel={`Открыть топик: ${t.title}`} onPress={() => navigation.navigate('Topic', {id: t.id})}>
          <Text style={styles.heading}>{t.title}</Text>
          {!!t.goal && <Text numberOfLines={3} style={styles.text}>{t.goal}</Text>}
          <Text style={styles.hint}>Пунктов: {t.nodeCount} · Записей: {t.podcastCount} · {profiles[t.languagePolicy ?? 'ru_standard']}</Text>
        </Pressable>
        <Button title="⋯" accessibilityLabel={`Действия с топиком: ${t.title}`} variant="quiet" onPress={() => setMenuId(menuId === t.id ? null : t.id)} /></View>
      </View>)}
      <Button title="Новый топик" variant="neutral" disabled={busy} onPress={() => setCreating(true)} />
      <Button title="Новый подкаст" disabled={busy} onPress={() => navigation.getParent()?.navigate('Podcasts', {create: Date.now()})} />
    </ScrollView>
    {menuTopic && <ContextMenu title={menuTopic.title} close={() => setMenuId(null)} actions={[{title: 'Удалить топик', destructive: true, disabled: busy, onPress: () => Alert.alert('Удалить топик?',
      `«${menuTopic.title}»: структура и связи будут удалены. Подкасты останутся в библиотеке.`,
      [{text: 'Отмена', style: 'cancel'}, {text: 'Удалить', style: 'destructive', onPress: () => {
        setBusy(true); Topics.mutate(JSON.stringify({schema: 1, action: 'deleteTopic', topicId: menuTopic.id, expectedRevision: menuTopic.revision}))
          .then(reload).catch(e => { setError(message(e)); setBusy(false); });
      }}])}]} />}
    {creating && <Editor topic={{id: '', rootNodeId: '', title: '', goal: '', outlineDepth: 2, revision: 0, languagePolicy: prefs.profile, contentSettings: prefs.settings}}
      close={() => setCreating(false)} save={async (title, goal, _kind, languagePolicy, contentSettings) => {
        const detail = JSON.parse(await Topics.mutate(JSON.stringify({schema: 1, action: 'createTopic', title, goal, outlineDepth: 2, languagePolicy, contentSettings}))) as Detail;
        setCreating(false); navigation.navigate('Topic', {id: detail.topic.id});
      }} />}
  </SafeAreaView>;
}

export function Attach({attached, attach, close}: {attached: string[]; attach: (id: string) => Promise<void>; close: () => void}) {
  const styles = useThemedStyles(styleDefinitions);
  const [query, setQuery] = useState('');
  const [items, setItems] = useState<Episode[]>([]);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(false);
  const [attaching, setAttaching] = useState(false);
  const [error, setError] = useState('');
  const sequence = useRef(0);
  const active = useRef(true);
  useEffect(() => { active.current = true; return () => { active.current = false; }; }, []);
  const load = useCallback((search: string, offset: number) => {
    const serial = ++sequence.current; setLoading(true); setError('');
    Topics.searchPodcasts(search, offset).then(raw => {
      if (!active.current || sequence.current !== serial) { return; }
      const page = JSON.parse(raw) as {items: Episode[]; hasMore: boolean};
      setItems(old => offset ? [...old, ...page.items.filter(x => !old.some(y => y.id === x.id))] : page.items);
      setHasMore(page.hasMore);
    }).catch(e => { if (active.current && sequence.current === serial) { setError(message(e)); } })
      .finally(() => { if (active.current && sequence.current === serial) { setLoading(false); } });
  }, []);
  useEffect(() => { load(query, 0); }, [query, load]);
  return <Sheet title="Прикрепить из библиотеки" busy={attaching} close={close}>
    <TextInput accessibilityLabel="Поиск выпуска" value={query} editable={!attaching} style={styles.input}
      onChangeText={v => setQuery(cut(v, 500))} placeholder="Поиск по названию" placeholderTextColor="#657381" />
    <Text style={styles.hint}>Первый выпуск без основного становится основным. Другие добавляются как дополнительные. Один выпуск можно прикрепить к нескольким узлам.</Text>
    {!!error && <Text accessibilityRole="alert" style={styles.error}>{error}</Text>}
    {items.map(item => <View key={item.id} style={styles.card}>
      <Text style={styles.text}>{item.title}</Text>
      <Text style={styles.hint}>{profiles[item.profile ?? 'ru_standard']} · {item.status === 'READY' ? 'Готов' : 'Файл недоступен'} · {episodeInfo(item)}</Text>
      <Button title={attached.includes(item.id) ? 'Уже прикреплён' : 'Прикрепить'} disabled={attaching || attached.includes(item.id)}
        onPress={() => { setAttaching(true); setError(''); attach(item.id).catch(e => setError(message(e))).finally(() => setAttaching(false)); }} />
    </View>)}
    {!items.length && !loading && !error && <Text style={styles.hint}>Записи не найдены.</Text>}
    {loading && <ActivityIndicator />}
    {hasMore && <Button title="Ещё записи" disabled={loading || attaching} onPress={() => load(query, items.length)} />}
  </Sheet>;
}

function TopicDetail({route, navigation}: NativeStackScreenProps<Routes, 'Topic'>) {
  const styles = useThemedStyles(styleDefinitions);
  const [data, setData] = useState<Detail | null>(null);
  const [allProfiles, setAllProfiles] = useState(false);
  const [copying, setCopying] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [mode, setMode] = useState<'structure' | 'listen'>('structure');
  const [podcastMenu, setPodcastMenu] = useState(false); const [menuOpen, setMenuOpen] = useState(false); const [outlineOpen, setOutlineOpen] = useState(false); const [addKind, setAddKind] = useState('SECTION'); const [linkMenu, setLinkMenu] = useState<string | null>(null);
  const [editor, setEditor] = useState<'topic' | 'add' | 'edit' | null>(null);
  const [attachOpen, setAttachOpen] = useState(false);
  const [generation, setGeneration] = useState<'SINGLE' | 'BATCH' | null>(null);
  const [targets, setTargets] = useState<{id: string; title: string}[] | null>(null);
  const branch = useRef('');
  const loadSequence = useRef(0);
  const latestDetail = useRef<Detail | null>(null);
  const apply = useCallback((next: Detail) => {
    if (latestDetail.current?.topic.id === next.topic.id && latestDetail.current.topic.revision > next.topic.revision) { return; }
    latestDetail.current = next; branch.current = next.selectedNodeId; setData(next);
  }, []);
  const load = useCallback(async (id = branch.current) => {
    const serial = ++loadSequence.current;
    // A completion arriving during navigation must refresh the requested branch.
    branch.current = id;
    setBusy(true); setError('');
    try {
      const next = JSON.parse(await LibraryUI.getTopicPlaylist(route.params.id, id, allProfiles)) as Detail;
      if (serial === loadSequence.current) { apply(next); }
    } catch (e) { if (serial === loadSequence.current) { setError(message(e)); } }
    finally { if (serial === loadSequence.current) { setBusy(false); } }
  }, [apply, route.params.id, allProfiles]);
  useFocusEffect(useCallback(() => {
    let active = true; let revision = -1;
    const seen = new Set<string>();
    const completed = (raw: string) => {
      const snapshot = JSON.parse(raw) as {revision: number; stage: string; busy: boolean; episode?: {id: string; sourceTopicId?: string}};
      if (!active || !(snapshot.revision > revision)) { return; }
      revision = snapshot.revision;
      const episode = snapshot.episode;
      // Ready is emitted after publication and the transactional topic attachment.
      // Ignore progress snapshots and repeated notifications for the same release.
      if (snapshot.stage === 'Ready' && !snapshot.busy && episode?.sourceTopicId === route.params.id && !seen.has(episode.id)) {
        seen.add(episode.id); load();
      }
    };
    const sub = Podcast.onSnapshot(completed);
    load();
    Podcast.getSnapshot().then(completed).catch(() => {});
    return () => { active = false; sub.remove(); ++loadSequence.current; };
  }, [load, route.params.id]));
  const current = data?.nodes.find(n => n.id === data.selectedNodeId);
  useFocusEffect(useCallback(() => {
    const sub = BackHandler.addEventListener('hardwareBackPress', () => {
      if (busy) { return true; }
      if (current?.parentId) { load(current.parentId); return true; }
      return false;
    });
    return () => sub.remove();
  }, [current?.parentId, busy, load]));
  const mutate = async (action: string, values: Record<string, unknown> = {}) => {
    if (!data || !current) { return; }
    ++loadSequence.current;
    setBusy(true); setError('');
    try {
      const raw = await Topics.mutate(JSON.stringify({schema: 1, action, topicId: data.topic.id,
        expectedRevision: data.topic.revision, nodeId: current.id, viewNodeId: current.id, ...values}));
      const next = JSON.parse(raw) as Detail;
      const updated = allProfiles ? JSON.parse(await LibraryUI.getTopicPlaylist(data.topic.id, next.selectedNodeId, true)) as Detail : next;
      apply(updated);
    } finally { setBusy(false); }
  };
  const run = (task: () => Promise<unknown>) => { task().catch(e => setError(message(e))); };
  if (!data || !current) { return <View style={styles.content}>
    {busy && <ActivityIndicator />}<Text style={styles.error}>{error}</Text><Button title="Повторить" onPress={() => load()} />
  </View>; }
  const children = ordered(data.nodes.filter(n => n.parentId === current.id));
  const siblings = ordered(data.nodes.filter(n => n.parentId === current.parentId));
  const nodeIndex = siblings.findIndex(n => n.id === current.id);
  const links = data.links.filter(l => l.nodeId === current.id).sort((a, b) =>
    (a.role === 'MAIN' ? 0 : 1) - (b.role === 'MAIN' ? 0 : 1) || a.position - b.position);
  const extras = links.filter(l => l.role === 'EXTRA');
  const path: Node[] = []; let cursor: Node | undefined = current;
  while (cursor && path.length <= 4) { path.unshift(cursor); cursor = data.nodes.find(n => n.id === cursor?.parentId); }
  return <SafeAreaView edges={['left', 'right']} style={styles.safe}>
    <ScrollView contentContainerStyle={styles.content}>
      <Button variant="quiet" title="← Все топики" onPress={() => navigation.navigate('TopicList')} />
      <Text style={styles.hint}>{profiles[data.topic.languagePolicy ?? 'ru_standard']} · Пунктов: {data.nodes.length}</Text>
      <View style={styles.row}>{path.slice(0, -1).map(n => <Button variant="quiet" key={n.id} title={n.title} disabled={busy || n.id === current.id} onPress={() => load(n.id)} />)}</View>
      <View style={styles.row}><Text style={[styles.title, styles.grow]}>{current.title}</Text><Button title="⋯" accessibilityLabel="Действия с разделом" variant="quiet" onPress={() => setMenuOpen(true)} /></View>

      {!!current.description && <Text style={styles.text}>{current.description}</Text>}
      <View style={styles.row}>
        <Button title={`${mode === 'structure' ? '● ' : ''}Структура`} onPress={() => setMode('structure')} />
        <Button title={`${mode === 'listen' ? '● ' : ''}Слушать`} onPress={() => setMode('listen')} />

      </View>
      {!!error && <Text accessibilityRole="alert" style={styles.error}>{error}</Text>}{busy && <ActivityIndicator />}
      {mode === 'structure' ? <>
        <View style={styles.row}>
          {current.kind !== 'QUESTION' && <>
          <Button title="+ Раздел" accessibilityLabel="Добавить раздел" disabled={busy || path.length > 4} onPress={() => { setAddKind('SECTION'); setEditor('add'); }} />
          <Button title="+ Вопрос" accessibilityLabel="Добавить вопрос" disabled={busy || path.length > 4} onPress={() => { setAddKind('QUESTION'); setEditor('add'); }} />
          </>}
          <Button title="+ Подкаст" accessibilityLabel="Добавить или создать подкаст" disabled={busy} onPress={() => setPodcastMenu(true)} />
        </View>
        {current.kind === 'QUESTION' ? <Button variant="primary" title="Создать подкаст по вопросу" disabled={busy} onPress={() => setGeneration('SINGLE')} /> : <>
          <Button variant="primary" title={current.parentId ? 'Создать подкасты для раздела' : 'Создать подкасты для всего топика'} disabled={busy} onPress={() => setGeneration('BATCH')} />
          <Text style={styles.hint}>Отдельные выпуски для разделов и вопросов. Перед запуском выберите все пункты или только вопросы и проверьте список.</Text>
          <Button title={current.parentId ? 'Один подкаст по разделу' : 'Один подкаст по теме'} disabled={busy} onPress={() => setGeneration('SINGLE')} />
          <Text style={styles.hint}>Один связный рассказ по всей выбранной теме.</Text>
        </>}

        <Text style={styles.heading}>Разделы и вопросы</Text>
        {children.map(n => <Pressable key={n.id} accessibilityRole="button" accessibilityLabel={`Открыть узел: ${n.title}`}
          disabled={busy} style={styles.card} onPress={() => load(n.id)}>
          <Text style={styles.heading}>{n.title}</Text><Text style={styles.hint}>{kindLabel(n.kind)}</Text>
        </Pressable>)}
        {!children.length && <Text style={styles.hint}>{current.kind === 'QUESTION' ? 'У вопроса нет дочерних узлов.' : 'Ветка пока пуста.'}</Text>}

        <Text style={styles.heading}>Записи раздела</Text>

        {links.map(link => <View key={link.id} style={styles.card}>
          <Text style={styles.text}>{link.title}</Text><Text style={styles.hint}>{episodeInfo(link)}</Text><Text style={styles.hint}>{profiles[link.profile] ?? link.profile} · {link.role === 'MAIN' ? 'Основной' : 'Дополнительный'} · {link.status === 'READY' ? 'Готов' : 'Недоступен'}</Text>
          <Button title="Слушать выпуск" disabled={link.status !== 'READY'} onPress={() => Playback.playEpisode(link.id)} />
          <Button title="⋯" accessibilityLabel={`Действия с записью: ${link.title}`} variant="quiet" onPress={() => setLinkMenu(link.id)} />
        </View>)}
      </> : <>
        <Text style={styles.heading}>Плейлист выбранной ветки</Text>
        <Button title={allProfiles ? 'Все языки · изменить' : 'Язык топика · изменить'} disabled={busy} onPress={() => setAllProfiles(!allProfiles)} />
        <Button title="Скопировать в свой плейлист" disabled={busy} onPress={() => setCopying(true)} />
        <Text style={styles.hint}>Обзор узла, дополнительные выпуски, затем дочерние узлы по порядку. Один выпуск включается один раз. Изменения структуры не меняют уже запущенную очередь.</Text>
        <Button title="Обновить очередь из ветки" disabled={busy || !data.playlist.some(p => p.status === 'READY')} onPress={() => run(() => LibraryUI.updateTopicQueue(data.topic.id, current.id, allProfiles))} />
        <Button variant="primary" title="Слушать ветку" disabled={busy || !data.playlist.some(p => p.status === 'READY')}
          onPress={() => run(() => LibraryUI.playTopicPlaylist(data.topic.id, current.id, allProfiles))} />
        {data.playlist.map((item, i) => <View key={item.id} style={styles.card}>
          <Text style={styles.text}>{i + 1}. {item.title}</Text><Text style={styles.hint}>{episodeInfo(item)}</Text>
          <Text style={styles.hint}>{item.status === 'READY' ? 'Готов' : 'Недоступен · будет пропущен'}</Text>
          <Button title="Слушать выпуск" disabled={item.status !== 'READY'} onPress={() => Playback.playEpisode(item.id)} />
        </View>)}
        {!data.playlist.length && <Text style={styles.hint}>В этой ветке ещё нет прикреплённых выпусков.</Text>}
      </>}
    </ScrollView>
    {podcastMenu && <ContextMenu title="Подкаст для выбранной темы" close={() => setPodcastMenu(false)} actions={[
      {title: 'Создать новый подкаст', disabled: busy, onPress: () => setGeneration('SINGLE')},
      {title: 'Добавить готовый из библиотеки', disabled: busy, onPress: () => setAttachOpen(true)},
    ]} />}
    {menuOpen && <ContextMenu title={current.title} close={() => setMenuOpen(false)} actions={[
      {title: current.parentId ? 'Изменить раздел или вопрос' : 'Изменить топик', disabled: busy, onPress: () => setEditor(current.parentId ? 'edit' : 'topic')},
      {title: 'Структура с AI', disabled: busy || path.length > 4, onPress: () => setOutlineOpen(true)},
      {title: 'Обновить', disabled: busy, onPress: () => { load(); }},
      ...(current.parentId ? [
        {title: 'Переместить выше', disabled: busy || nodeIndex === 0, onPress: () => run(() => mutate('moveNode', {parentId: current.parentId, position: nodeIndex - 1}))},
        {title: 'Переместить ниже', disabled: busy || nodeIndex === siblings.length - 1, onPress: () => run(() => mutate('moveNode', {parentId: current.parentId, position: nodeIndex + 1}))},
        {title: 'Перенести в другой раздел', disabled: busy, onPress: () => {
          setBusy(true); Topics.moveTargets(data.topic.id, current.id).then(raw => setTargets(JSON.parse(raw).targets)).catch(e => setError(message(e))).finally(() => setBusy(false));
        }},
        {title: 'Удалить ветку', destructive: true, disabled: busy, onPress: () => Alert.alert('Удалить ветку?', 'Вложенные пункты и связи удалятся. Аудио останется в библиотеке.',
          [{text: 'Отмена', style: 'cancel'}, {text: 'Удалить', style: 'destructive', onPress: () => run(() => mutate('deleteNode', {viewNodeId: current.parentId}))}])},
      ] : []),
    ]} />}
    {outlineOpen && <Sheet title="Структура с AI" close={() => setOutlineOpen(false)}><OutlinePanel key={current.id} topicId={data.topic.id} nodeId={current.id} revision={data.topic.revision} depth={5 - path.length} defaultDepth={data.topic.outlineDepth} onApplied={raw => apply(JSON.parse(raw) as Detail)} /></Sheet>}
    {linkMenu && links.filter(l => l.id === linkMenu).map(link => <ContextMenu key={link.id} title={link.title} close={() => setLinkMenu(null)} actions={[
      ...(link.role !== 'MAIN' ? [
        {title: 'Сделать основным', disabled: busy, onPress: () => run(() => mutate('makeMain', {podcastId: link.id}))},
        {title: 'Переместить раньше', disabled: busy || extras.indexOf(link) === 0, onPress: () => run(() => mutate('moveLink', {podcastId: link.id, position: extras.indexOf(link) - 1}))},
        {title: 'Переместить позже', disabled: busy || extras.indexOf(link) === extras.length - 1, onPress: () => run(() => mutate('moveLink', {podcastId: link.id, position: extras.indexOf(link) + 1}))},
      ] : []),
      {title: 'Отвязать от раздела', disabled: busy, onPress: () => Alert.alert('Отвязать выпуск?', 'Аудиофайл останется в библиотеке.', [{text: 'Отмена', style: 'cancel'}, {text: 'Отвязать', onPress: () => run(() => mutate('detach', {podcastId: link.id}))}])},
    ]} />)}
    {editor && <Editor initialKind={addKind} topic={editor === 'topic' ? data.topic : undefined} node={editor === 'edit' ? current : undefined}
      close={() => setEditor(null)} save={async (title, description, kind, languagePolicy, contentSettings) => {
        await mutate(editor === 'topic' ? 'editTopic' : editor === 'edit' ? 'editNode' : 'addNode',
          editor === 'topic' ? {title, goal: description, outlineDepth: data.topic.outlineDepth, languagePolicy, contentSettings} : {title, description, kind, parentId: current.id});
        setEditor(null);
      }} />}
    {copying && <NameDialog title="Копия плейлиста ветки" initial={current.title} close={() => setCopying(false)} save={async title => {
      await LibraryUI.mutatePlaylist(JSON.stringify({action: 'copyTopic', title, topicId: data.topic.id, nodeId: current.id, expectedRevision: data.topic.revision, allProfiles}));
      setCopying(false); Alert.alert('Плейлист сохранён', 'Копия доступна во вкладке «Плейлисты».');
    }} />}
    {generation && <GenerationPanel request={{mode: generation, topicId: data.topic.id, nodeId: current.id, expectedRevision: data.topic.revision,
      profile: data.topic.languagePolicy, settings: data.topic.contentSettings}} close={() => setGeneration(null)} done={() => load()} showQueue={() => navigation.getParent()?.navigate('GenerationQueue')} />}
    {attachOpen && <Attach attached={links.map(l => l.id)} close={() => setAttachOpen(false)} attach={id => mutate('attach', {podcastId: id})} />}
    {targets && <Sheet title="Новый родитель" busy={busy} close={() => setTargets(null)}>
      <Text style={styles.hint}>Узел будет добавлен в конец выбранного раздела. Циклы и превышение глубины исключены из списка.</Text>
      {targets.map(target => <Button key={target.id} title={target.title} disabled={busy} onPress={() => {
        const count = data.nodes.filter(n => n.parentId === target.id && n.id !== current.id).length;
        run(async () => { await mutate('moveNode', {parentId: target.id, position: count}); setTargets(null); });
      }} />)}
      {!!error && <Text accessibilityRole="alert" style={styles.error}>{error}</Text>}
    </Sheet>}
  </SafeAreaView>;
}

export default function TopicsNavigator() {
  return <Stack.Navigator screenOptions={{headerShown: false}}><Stack.Screen name="TopicList" component={TopicList} options={{title: 'Топики'}} />
    <Stack.Screen name="Topic" component={TopicDetail} options={{title: 'Структура топика'}} /></Stack.Navigator>;
}
const styleDefinitions = StyleSheet.create({
  safe: {flex: 1, backgroundColor: '#f3f5f7'}, content: {padding: 20, gap: 16, paddingBottom: 36},
  title: {fontSize: 30, fontWeight: '700', color: '#132638'}, heading: {fontSize: 18, fontWeight: '600', color: '#132638'},
  text: {fontSize: 16, lineHeight: 24, color: '#263c4f'}, hint: {fontSize: 12, lineHeight: 18, color: '#657381'},
  grow: {flex: 1}, tileRow: {flexDirection: 'row', alignItems: 'center', gap: 8},
  row: {flexDirection: 'row', flexWrap: 'wrap', gap: 8}, card: {padding: 16, borderRadius: 20, backgroundColor: '#fff', gap: 8},
  input: {minHeight: 48, borderWidth: 1, borderColor: '#ccd5dd', borderRadius: 14, padding: 12, color: '#132638'},
  multiline: {minHeight: 100, borderWidth: 1, borderColor: '#ccd5dd', borderRadius: 14, padding: 12, color: '#132638', textAlignVertical: 'top'},
  error: {color: '#ac2525', lineHeight: 22},
});
