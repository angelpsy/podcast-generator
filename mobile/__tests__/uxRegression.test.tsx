import React from 'react';
import Renderer, {act} from 'react-test-renderer';

jest.mock('../src/theme', () => ({Button: 'Button', TextInput: 'Input', useThemedStyles: (s: unknown) => s,
  useApp: () => ({prefs: {model: '', profile: 'ru_standard', settings: {}}, colors: {}})}));
jest.mock('react-native-safe-area-context', () => ({SafeAreaView: 'SafeAreaView'}));
jest.mock('@react-navigation/native', () => ({useRoute: () => ({params: {}}), useNavigation: () => ({navigate: jest.fn()}),
  useFocusEffect: (callback: () => void) => { require('react').useEffect(callback, [callback]); }}));
jest.mock('@react-navigation/native-stack', () => ({createNativeStackNavigator: () => ({
  Navigator: ({children}: any) => children,
  Screen: ({name, component: Component}: any) => name === 'Topic' ? <Component route={{params: {id: 'topic'}}} navigation={{navigate: jest.fn()}} /> : null,
})}));
jest.mock('../src/UIForms', () => ({ContextMenu: 'Menu', NameDialog: 'NameDialog'}));
jest.mock('../src/OutlinePanel', () => 'OutlinePanel');
jest.mock('../src/GenerationPanel', () => ({...jest.requireActual('../src/GenerationPanel'), __esModule: true, default: 'GenerationPanel'}));
jest.mock('../specs/NativeLibraryUI', () => ({__esModule: true, default: {getTopicPlaylist: jest.fn()}}));
jest.mock('../specs/NativeTopics', () => ({__esModule: true, default: {mutate: jest.fn()}}));
jest.mock('../specs/NativePlayback', () => ({__esModule: true, default: {playEpisode: jest.fn()}}));
jest.mock('../specs/NativeSiwc', () => ({__esModule: true, default: {onSnapshot: jest.fn(() => ({remove: jest.fn()})), getSnapshot: jest.fn(async () => '{}')}}));
jest.mock('../specs/NativePodcast', () => ({__esModule: true, default: {
  onSnapshot: jest.fn(() => ({remove: jest.fn()})), getSnapshot: jest.fn(), searchLibrary: jest.fn(), selectEpisode: jest.fn(),
}}));

import TopicsNavigator from '../src/TopicsScreen';
import PodcastScreen from '../src/PodcastScreen';
import LibraryUI from '../specs/NativeLibraryUI';
import Podcast from '../specs/NativePodcast';
import Playback from '../specs/NativePlayback';
import Topics from '../specs/NativeTopics';

// Native bridges are controlled here so publication, delayed selection and late
// reads can be delivered separately, as they are on the Android worker threads.
let tree: Renderer.ReactTestRenderer;
const detail = (question = false) => ({topic: {id: 'topic', rootNodeId: 'root', revision: 1, title: 'Topic', outlineDepth: 2},
  nodes: [{id: 'root', parentId: null, kind: 'ROOT', title: 'Topic', position: 0}, {id: 'question', parentId: 'root', kind: 'QUESTION', title: 'Question', position: 0}],
  selectedNodeId: question ? 'question' : 'root', links: [], playlist: []});
const button = (title: string) => tree.root.findAll(n => n.type === ('Button' as any) && n.props.title.replace(/^● /, '') === title)[0];
const emit = async (snapshot: object) => act(async () => {
  for (const [listener] of (Podcast.onSnapshot as jest.Mock).mock.calls) { listener(JSON.stringify(snapshot)); }
});
const completion = (revision: number, id = 'new', topicId = 'topic') => ({revision, stage: 'Ready', busy: false, episode: {id, sourceTopicId: topicId}});
const librarySnapshot = {revision: 1, stage: 'Ready', busy: false, storageReady: true, selectedId: 'a',
  library: [{id: 'a', title: 'A', status: 'READY'}, {id: 'b', title: 'B', status: 'READY'}],
  episode: {id: 'a', topic: 'A', durationMs: 1000, bytes: 1, script: 'Text A'}};
const openB = async () => {
  (Podcast.getSnapshot as jest.Mock).mockResolvedValue(JSON.stringify(librarySnapshot));
  await act(async () => { tree = Renderer.create(<PodcastScreen onBusyChange={() => {}} />); });
  const b = tree.root.findAll(n => n.props.accessibilityLabel === 'Открыть: B' && typeof n.props.onPress === 'function')[0];
  await act(async () => b.props.onPress());
};
beforeEach(() => {
  jest.clearAllMocks();
  (Podcast.getSnapshot as jest.Mock).mockReset().mockResolvedValue('{}');
  (LibraryUI.getTopicPlaylist as jest.Mock).mockReset();
});
afterEach(async () => { if (tree) { await act(async () => tree.unmount()); } });

test('question can open AI expansion without editing its kind first', async () => {
  (LibraryUI.getTopicPlaylist as jest.Mock).mockResolvedValue(JSON.stringify(detail(true)));
  await act(async () => { tree = Renderer.create(<TopicsNavigator />); });
  const more = tree.root.findAll(n => n.type === ('Button' as any) && n.props.accessibilityLabel === 'Действия с разделом')[0];
  await act(async () => more.props.onPress());
  const action = tree.root.findByType('Menu' as any).props.actions.find((a: any) => a.title === 'Структура с AI');
  expect(action.disabled).toBe(false);
  await act(async () => action.onPress());
  expect(tree.root.findByType('OutlinePanel' as any).props.nodeId).toBe('question');
  expect(tree.root.findByType('OutlinePanel' as any).props.depth).toBe(3);
});

test.each(['QUESTION', 'SECTION'])('AI expansion stays disabled at the depth limit for %s', async kind => {
  const value = detail();
  value.nodes = Array.from({length: 5}, (_, i) => ({id: i === 0 ? 'root' : `n${i}`, parentId: i === 0 ? null : i === 1 ? 'root' : `n${i - 1}`,
    kind: i === 0 ? 'ROOT' : i === 4 ? kind : 'SECTION', title: `Level ${i}`, position: 0}));
  value.selectedNodeId = 'n4';
  (LibraryUI.getTopicPlaylist as jest.Mock).mockResolvedValue(JSON.stringify(value));
  await act(async () => { tree = Renderer.create(<TopicsNavigator />); });
  await act(async () => tree.root.findAll(n => n.type === ('Button' as any) && n.props.accessibilityLabel === 'Действия с разделом')[0].props.onPress());
  expect(tree.root.findByType('Menu' as any).props.actions.find((a: any) => a.title === 'Структура с AI').disabled).toBe(true);
});

test('completed generation refreshes topic playlist and the next request revision', async () => {
  const initial = detail();
  (LibraryUI.getTopicPlaylist as jest.Mock).mockResolvedValue(JSON.stringify(initial));
  await act(async () => { tree = Renderer.create(<TopicsNavigator />); });
  await act(async () => button('Создать подкасты для всего топика').props.onPress());
  const form = tree.root.findByType('GenerationPanel' as any);
  await act(async () => { form.props.done(); form.props.close(); });
  expect(LibraryUI.getTopicPlaylist).toHaveBeenCalledTimes(2);
  (LibraryUI.getTopicPlaylist as jest.Mock).mockResolvedValue(JSON.stringify({...initial, topic: {...initial.topic, revision: 2},
    playlist: [{id: 'new', title: 'New podcast', status: 'READY'}]}));
  await act(async () => {
    for (const [listener] of (Podcast.onSnapshot as jest.Mock).mock.calls) {
      listener(JSON.stringify({revision: 10, stage: 'Ready', busy: false, generationQueue: [], episode: {id: 'new', sourceTopicId: 'topic'}}));
    }
  });
  expect(LibraryUI.getTopicPlaylist).toHaveBeenCalledTimes(3);
  await act(async () => button('Слушать').props.onPress());
  expect(button('Слушать ветку').props.disabled).toBe(false);
  await act(async () => button('Структура').props.onPress());
  await act(async () => button('Создать подкасты для всего топика').props.onPress());
  expect(tree.root.findByType('GenerationPanel' as any).props.request.expectedRevision).toBe(2);
});

test('selecting B hides A until Android confirms B', async () => {
  await openB();
  expect(Podcast.selectEpisode).toHaveBeenCalledWith('b');
  expect(button('Слушать выпуск')).toBeUndefined();
  expect(button('Открыть в другом приложении')).toBeUndefined();
  expect(button('Показать текст сохранённого подкаста')).toBeUndefined();
  expect(Playback.playEpisode).not.toHaveBeenCalled();
  await emit({...librarySnapshot, revision: 2, busy: true});
  expect(button('Слушать выпуск')).toBeUndefined();
  await act(async () => {
    for (const [listener] of (Podcast.onSnapshot as jest.Mock).mock.calls) {
      listener(JSON.stringify({revision: 3, stage: 'Ready', busy: false, storageReady: true, selectedId: 'b', episode: {id: 'b', topic: 'B', durationMs: 1000, bytes: 1}}));
    }
  });
  await emit(librarySnapshot); // A delayed old snapshot must not replace confirmed B.
  await act(async () => button('Слушать выпуск').props.onPress());
  expect(Playback.playEpisode).toHaveBeenCalledWith('b');
});

test('completion preserves the open branch, listening mode and all-language filter', async () => {
  const initial = detail(true);
  (LibraryUI.getTopicPlaylist as jest.Mock).mockResolvedValue(JSON.stringify(initial));
  await act(async () => { tree = Renderer.create(<TopicsNavigator />); });
  await act(async () => button('Слушать').props.onPress());
  await act(async () => button('Язык топика · изменить').props.onPress());
  (LibraryUI.getTopicPlaylist as jest.Mock).mockResolvedValue(JSON.stringify({...initial, topic: {...initial.topic, revision: 2},
    playlist: [{id: 'new', title: 'New podcast', status: 'READY'}]}));
  await emit(completion(10));
  expect(LibraryUI.getTopicPlaylist).toHaveBeenLastCalledWith('topic', 'question', true);
  expect(button('Слушать').props.title).toBe('● Слушать');
  expect(button('Слушать ветку').props.disabled).toBe(false);
});

test('progress, other topics, duplicate completions and older snapshots do not reload the topic', async () => {
  (LibraryUI.getTopicPlaylist as jest.Mock).mockResolvedValue(JSON.stringify(detail()));
  await act(async () => { tree = Renderer.create(<TopicsNavigator />); });
  await emit({...completion(2), stage: 'Synthesizing', busy: true});
  await emit(completion(3, 'other', 'other-topic'));
  expect(LibraryUI.getTopicPlaylist).toHaveBeenCalledTimes(1);
  await emit(completion(5));
  expect(LibraryUI.getTopicPlaylist).toHaveBeenCalledTimes(2);
  await emit(completion(6));
  await emit(completion(4, 'old'));
  expect(LibraryUI.getTopicPlaylist).toHaveBeenCalledTimes(2);
  await emit(completion(7, 'next'));
  expect(LibraryUI.getTopicPlaylist).toHaveBeenCalledTimes(3);
});

test('a delayed pre-publication topic read cannot overwrite the refreshed revision', async () => {
  let resolveOld!: (value: string) => void;
  const initial = detail();
  (LibraryUI.getTopicPlaylist as jest.Mock).mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve; }))
    .mockResolvedValue(JSON.stringify({...initial, topic: {...initial.topic, revision: 2}}));
  await act(async () => { tree = Renderer.create(<TopicsNavigator />); });
  await emit(completion(10));
  await act(async () => resolveOld(JSON.stringify(initial)));
  await act(async () => button('Создать подкасты для всего топика').props.onPress());
  expect(tree.root.findByType('GenerationPanel' as any).props.request.expectedRevision).toBe(2);
});

test('leaving the topic removes subscriptions and ignores a late initial snapshot', async () => {
  let resolveSnapshot!: (value: string) => void;
  (Podcast.getSnapshot as jest.Mock).mockReturnValue(new Promise(resolve => { resolveSnapshot = resolve; }));
  (LibraryUI.getTopicPlaylist as jest.Mock).mockResolvedValue(JSON.stringify(detail()));
  await act(async () => { tree = Renderer.create(<TopicsNavigator />); });
  await act(async () => tree.unmount());
  expect((Podcast.onSnapshot as jest.Mock).mock.results.every(result => result.value.remove.mock.calls.length === 1)).toBe(true);
  await act(async () => resolveSnapshot(JSON.stringify(completion(10))));
  await emit(completion(11));
  expect(LibraryUI.getTopicPlaylist).toHaveBeenCalledTimes(1);
});

test('a delayed edit response cannot roll back a newer publication snapshot', async () => {
  let resolveEdit!: (value: string) => void;
  const initial = detail();
  (Topics.mutate as jest.Mock).mockReturnValue(new Promise(resolve => { resolveEdit = resolve; }));
  (LibraryUI.getTopicPlaylist as jest.Mock).mockResolvedValue(JSON.stringify(initial));
  await act(async () => { tree = Renderer.create(<TopicsNavigator />); });
  await act(async () => tree.root.findAll(n => n.type === ('Button' as any) && n.props.accessibilityLabel === 'Действия с разделом')[0].props.onPress());
  await act(async () => tree.root.findByType('Menu' as any).props.actions.find((a: any) => a.title === 'Изменить топик').onPress());
  await act(async () => button('Сохранить').props.onPress());
  (LibraryUI.getTopicPlaylist as jest.Mock).mockResolvedValue(JSON.stringify({...initial, topic: {...initial.topic, revision: 3}}));
  await emit(completion(10));
  await act(async () => resolveEdit(JSON.stringify({...initial, topic: {...initial.topic, revision: 2}})));
  await act(async () => button('Создать подкасты для всего топика').props.onPress());
  expect(tree.root.findByType('GenerationPanel' as any).props.request.expectedRevision).toBe(3);
});

test('a confirmed draft shows its own text and retry, never the previous audio', async () => {
  await openB();
  await emit({...librarySnapshot, revision: 3, selectedId: 'b', stage: 'DraftReady', episode: null, script: 'Draft B', canRetry: true});
  expect(button('Слушать выпуск')).toBeUndefined();
  expect(button('Открыть в другом приложении')).toBeUndefined();
  expect(button('Продолжить создание')).toBeDefined();
  await act(async () => button('Показать текст текущего подкаста').props.onPress());
  const content = JSON.stringify(tree.toJSON());
  expect(content).toContain('Draft B');
  expect(content).not.toContain('Text A');
});

test('failed selection offers retry without exposing the previous episode', async () => {
  await openB();
  await emit({...librarySnapshot, revision: 3, error: 'storage_operation_failed'});
  expect(button('Слушать выпуск')).toBeUndefined();
  expect(button('Повторить открытие')).toBeDefined();
  await act(async () => button('Повторить открытие').props.onPress());
  expect(Podcast.selectEpisode).toHaveBeenCalledTimes(2);
  expect(button('Повторить открытие')).toBeUndefined();
  await emit({...librarySnapshot, revision: 5, selectedId: 'b', episode: {...librarySnapshot.episode, id: 'b', topic: 'B'}});
  expect(button('Слушать выпуск')).toBeDefined();
});

test('closing a pending selection does not reopen the modal on confirmation', async () => {
  await openB();
  await act(async () => button('Закрыть выпуск').props.onPress());
  await emit({...librarySnapshot, revision: 3, selectedId: 'b', episode: {...librarySnapshot.episode, id: 'b', topic: 'B'}});
  expect(button('Слушать выпуск')).toBeUndefined();
  expect(Playback.playEpisode).not.toHaveBeenCalled();
});

test('topic plan and library do not render global queue tasks', async () => {
  (Podcast.getSnapshot as jest.Mock).mockResolvedValue(JSON.stringify({...librarySnapshot,
    generationQueue: [{id: 'queued', title: 'GLOBAL_QUEUE_ONLY_TASK', status: 'PAUSED', phase: 'QUEUED', profile: 'ru_standard', hasScript: false}]}));
  (LibraryUI.getTopicPlaylist as jest.Mock).mockResolvedValue(JSON.stringify(detail()));
  await act(async () => { tree = Renderer.create(<TopicsNavigator />); });
  expect(JSON.stringify(tree.toJSON())).not.toContain('GLOBAL_QUEUE_ONLY_TASK');
  await act(async () => tree.unmount());
  await act(async () => { tree = Renderer.create(<PodcastScreen onBusyChange={() => {}} />); });
  expect(JSON.stringify(tree.toJSON())).not.toContain('GLOBAL_QUEUE_ONLY_TASK');
});
