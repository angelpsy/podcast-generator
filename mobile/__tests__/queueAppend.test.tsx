import React from 'react';
import Renderer, {act} from 'react-test-renderer';
import {defaultContent} from '../src/contentTypes';

jest.mock('../src/theme', () => ({Button: 'Button', TextInput: 'Input', useThemedStyles: (s: unknown) => s,
  useApp: () => ({prefs: {model: 'model', profile: 'ru_standard', settings: require('../src/contentTypes').defaultContent}, colors: {}})}));
jest.mock('react-native-safe-area-context', () => ({SafeAreaView: 'SafeAreaView'}));
jest.mock('@react-navigation/native', () => ({useRoute: () => ({params: {}}), useNavigation: () => ({navigate: jest.fn()}),
  useFocusEffect: (callback: () => void) => { require('react').useEffect(callback, [callback]); }}));
jest.mock('../src/ContentSettings', () => ({...jest.requireActual('../src/ContentSettings'), __esModule: true, default: 'ContentEditor'}));
jest.mock('../specs/NativeSiwc', () => ({__esModule: true, default: {getSnapshot: jest.fn(), onSnapshot: jest.fn(() => ({remove: jest.fn()}))}}));
jest.mock('../specs/NativePodcast', () => ({__esModule: true, default: {getSnapshot: jest.fn(), onSnapshot: jest.fn(() => ({remove: jest.fn()})),
  searchLibrary: jest.fn(), previewGeneration: jest.fn(), enqueueGeneration: jest.fn()}}));
jest.mock('../specs/NativePlayback', () => ({__esModule: true, default: {}}));

import PodcastScreen from '../src/PodcastScreen';
import GenerationPanel from '../src/GenerationPanel';
import Siwc from '../specs/NativeSiwc';
import Podcast from '../specs/NativePodcast';

let tree: Renderer.ReactTestRenderer;
const session = {revision: 1, busy: true, stage: 'Streaming', planEnabled: true, signedIn: true, models: [{slug: 'model', name: 'Model'}]};
const button = (title: string) => tree.root.findAll(n => n.type === ('Button' as any) && n.props.title === title)[0];
const preview = JSON.stringify({token: 'token', items: [{nodeId: '', title: 'Next episode'}], skippedReady: 0, skippedActive: 0, remaining: 0});
beforeEach(() => {
  jest.resetAllMocks();
  (Podcast.onSnapshot as jest.Mock).mockReturnValue({remove: jest.fn()});
  (Siwc.onSnapshot as jest.Mock).mockReturnValue({remove: jest.fn()});
  (Siwc.getSnapshot as jest.Mock).mockResolvedValue(JSON.stringify(session));
  (Podcast.getSnapshot as jest.Mock).mockResolvedValue(JSON.stringify({revision: 1, busy: true, storageReady: true, library: []}));
  (Podcast.previewGeneration as jest.Mock).mockResolvedValue(preview);
  (Podcast.enqueueGeneration as jest.Mock).mockResolvedValue('{"ids":["next"]}');
});
afterEach(async () => { await act(async () => tree?.unmount()); });

test.each(['PreparingVoice', 'GeneratingText', 'Synthesizing', 'Encoding', 'Saving'])('library allows adding a podcast during %s', async stage => {
  (Podcast.getSnapshot as jest.Mock).mockResolvedValue(JSON.stringify({revision: 1, stage, busy: true, storageReady: true, library: []}));
  await act(async () => { tree = Renderer.create(<PodcastScreen onBusyChange={() => {}} />); });
  expect(button('Новый подкаст').props.disabled).toBe(false);
  await act(async () => button('Новый подкаст').props.onPress());
  expect(tree.root.findByType(GenerationPanel)).toBeDefined();
});

test.each(['STANDALONE', 'SINGLE', 'BATCH'])('%s can preview and enqueue while SIWC streams the current episode', async mode => {
  const close = jest.fn(); const showQueue = jest.fn();
  await act(async () => { tree = Renderer.create(<GenerationPanel request={{mode, title: 'Next episode', topicId: mode === 'STANDALONE' ? undefined : 'topic', nodeId: 'node', expectedRevision: 1}} close={close} showQueue={showQueue} />); });
  expect(button('Посмотреть состав очереди').props.disabled).toBe(false);
  await act(async () => button('Посмотреть состав очереди').props.onPress());
  expect(button('Добавить в очередь').props.disabled).toBe(false);
  await act(async () => button('Добавить в очередь').props.onPress());
  const input = JSON.parse((Podcast.enqueueGeneration as jest.Mock).mock.calls[0][0]);
  expect(input).toMatchObject({mode, title: 'Next episode', model: 'model', settings: defaultContent, previewToken: 'token'});
  expect(close).not.toHaveBeenCalled();
  expect(JSON.stringify(tree.toJSON())).toContain('Добавлено в очередь: ');
  expect(button('Добавить в очередь')).toBeUndefined();
  await act(async () => button('Открыть очередь').props.onPress());
  expect(close).toHaveBeenCalledTimes(1); expect(showQueue).toHaveBeenCalledTimes(1);
});

test('an in-flight local enqueue still disables duplicate submit', async () => {
  let resolve!: (value: string) => void;
  (Podcast.enqueueGeneration as jest.Mock).mockReturnValue(new Promise(done => { resolve = done; }));
  await act(async () => { tree = Renderer.create(<GenerationPanel request={{mode: 'STANDALONE', title: 'Next'}} close={jest.fn()} />); });
  await act(async () => button('Посмотреть состав очереди').props.onPress());
  await act(async () => { button('Добавить в очередь').props.onPress(); });
  expect(button('Добавить в очередь').props.disabled).toBe(true);
  expect(button('Закрыть создание').props.disabled).toBe(true);
  expect(Podcast.enqueueGeneration).toHaveBeenCalledTimes(1);
  await act(async () => resolve('{"ids":["next"]}'));
});

test.each(['empty_title', 'no_models', 'no_plan'])('preview still validates %s while generation runs', async reason => {
  (Siwc.getSnapshot as jest.Mock).mockResolvedValue(JSON.stringify({...session, models: reason === 'no_models' ? [] : session.models, planEnabled: reason !== 'no_plan'}));
  await act(async () => { tree = Renderer.create(<GenerationPanel request={{mode: 'STANDALONE', title: reason === 'empty_title' ? ' ' : 'Next'}} close={jest.fn()} />); });
  expect(button('Посмотреть состав очереди').props.disabled).toBe(true);
  expect(Podcast.previewGeneration).not.toHaveBeenCalled();
});

test('new podcast remains disabled until library storage is ready', async () => {
  (Podcast.getSnapshot as jest.Mock).mockResolvedValue(JSON.stringify({revision: 1, busy: true, storageReady: false, library: []}));
  await act(async () => { tree = Renderer.create(<PodcastScreen onBusyChange={() => {}} />); });
  expect(button('Новый подкаст').props.disabled).toBe(true);
});

test('return from enqueue confirmation preserves the caller instead of navigating away', async () => {
  const close = jest.fn(); const showQueue = jest.fn();
  await act(async () => { tree = Renderer.create(<GenerationPanel request={{mode: 'STANDALONE', title: 'Next'}} close={close} showQueue={showQueue} />); });
  await act(async () => button('Посмотреть состав очереди').props.onPress());
  await act(async () => button('Добавить в очередь').props.onPress());
  await act(async () => button('Вернуться').props.onPress());
  expect(close).toHaveBeenCalledTimes(1); expect(showQueue).not.toHaveBeenCalled();
  expect(Podcast.enqueueGeneration).toHaveBeenCalledTimes(1);
});
