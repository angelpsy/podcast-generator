import React from 'react';
import Renderer, {act} from 'react-test-renderer';
jest.mock('../src/theme', () => ({Button: 'Button', useThemedStyles: (s: unknown) => s}));
jest.mock('react-native-safe-area-context', () => ({SafeAreaView: 'SafeAreaView'}));
jest.mock('../specs/NativePodcast', () => ({__esModule: true, default: {onSnapshot: jest.fn(), getSnapshot: jest.fn(), cancel: jest.fn(), resumeQueue: jest.fn(), cancelTask: jest.fn(), recoverLibrary: jest.fn()}}));
import Screen from '../src/GenerationQueueScreen';
import Podcast from '../specs/NativePodcast';

let tree: Renderer.ReactTestRenderer;
const task = (id: string, status: string) => ({id, title: `Task ${id}`, status, phase: status === 'RUNNING' ? 'Synthesizing' : 'QUEUED', profile: 'ru_standard', error: '', hasScript: false});
const page = {revision: 5, busy: true, storageReady: true, chunk: 2, chunks: 4,
  generationQueue: [task('running', 'RUNNING'), task('first', 'QUEUED'), task('second', 'QUEUED'), {...task('paused', 'PAUSED'), hasScript: true}, {...task('failed', 'FAILED'), error: 'script_language'}]};
const buttons = (title: string) => tree.root.findAll(n => n.type === ('Button' as any) && n.props.title === title);
const label = (text: string) => tree.root.findAll(n => n.type === ('Button' as any) && n.props.accessibilityLabel === text)[0];
const text = () => JSON.stringify(tree.toJSON());
const render = async () => act(async () => { tree = Renderer.create(<Screen />); });
const emit = async (snapshot: object) => act(async () => {
  for (const [listener] of (Podcast.onSnapshot as jest.Mock).mock.calls) { listener(JSON.stringify(snapshot)); }
});
beforeEach(() => {
  jest.resetAllMocks();
  (Podcast.onSnapshot as jest.Mock).mockReturnValue({remove: jest.fn()});
  (Podcast.getSnapshot as jest.Mock).mockResolvedValue(JSON.stringify(page));
});
afterEach(async () => { await act(async () => tree?.unmount()); });

test('separate screen groups running, FIFO waiting and paused/failed tasks', async () => {
  await render();
  expect(text()).toContain('Очередь создания');
  expect(text()).toContain('Незавершённых: ');
  expect(text()).toContain('Требуют внимания');
  expect(text()).toContain('Ответ не соответствует выбранному языку.');
  expect(text()).toContain('Сценарий сохранён, повторный запрос не нужен.');
  expect(text().indexOf('Task first')).toBeLessThan(text().indexOf('Task second'));
  expect(label('Убрать задание: Task running')).toBeUndefined();
  expect(label('Продолжить выпуск: Task paused').props.disabled).toBe(true);
  expect(Podcast.resumeQueue).not.toHaveBeenCalled();
  expect(Podcast.cancel).not.toHaveBeenCalled();
  expect(Podcast.cancelTask).not.toHaveBeenCalled();
});

test('pause and removal invoke the existing commands with the correct target', async () => {
  await render();
  await act(async () => buttons('Приостановить очередь')[0].props.onPress());
  expect(Podcast.cancel).toHaveBeenCalledTimes(1);
  await act(async () => label('Убрать задание: Task second').props.onPress());
  expect(Podcast.cancelTask).toHaveBeenCalledWith('second');
});

test('resume controls distinguish the whole queue from an individual failed task', async () => {
  (Podcast.getSnapshot as jest.Mock).mockResolvedValue(JSON.stringify({...page, busy: false, generationQueue: [task('paused', 'PAUSED'), task('failed', 'FAILED')]}));
  await render();
  await act(async () => buttons('Продолжить очередь')[0].props.onPress());
  expect(Podcast.resumeQueue).toHaveBeenCalledWith('');
  await act(async () => label('Продолжить выпуск: Task failed').props.onPress());
  expect(Podcast.resumeQueue).toHaveBeenLastCalledWith('failed');
  await emit({revision: 6, busy: false, storageReady: true, generationQueue: [task('failed', 'FAILED')]});
  expect(buttons('Продолжить очередь')[0].props.disabled).toBe(true);
  expect(label('Продолжить выпуск: Task failed').props.disabled).toBe(false);
});

test('completion removes jobs from the screen and empty state has no resume action', async () => {
  await render();
  await emit({revision: 6, busy: false, generationQueue: []});
  expect(text()).toContain('Очередь пуста');
  expect(text()).not.toContain('Task first');
  expect(buttons('Продолжить очередь')).toHaveLength(0);
  expect(Podcast.resumeQueue).not.toHaveBeenCalled();
});

test('loading snapshot without queue is not mistaken for an empty queue', async () => {
  (Podcast.getSnapshot as jest.Mock).mockResolvedValue('{"revision":1,"stage":"Loading","busy":true}');
  await render();
  expect(text()).not.toContain('Очередь пуста');
  await emit({revision: 2, busy: false, generationQueue: []});
  expect(text()).toContain('Очередь пуста');
});

test('queue load error offers a retry and recovers without polling', async () => {
  (Podcast.getSnapshot as jest.Mock).mockRejectedValueOnce(new Error('offline'));
  await render();
  expect(text()).toContain('Не удалось загрузить очередь.');
  await act(async () => buttons('Повторить загрузку очереди')[0].props.onPress());
  expect(text()).toContain('Task running');
  expect(text()).not.toContain('Не удалось загрузить очередь.');
  expect(Podcast.recoverLibrary).not.toHaveBeenCalled();
});

test('a late initial read cannot restore jobs after a newer completion event', async () => {
  let resolve!: (raw: string) => void;
  (Podcast.getSnapshot as jest.Mock).mockReturnValue(new Promise(done => { resolve = done; }));
  await render();
  await emit({revision: 6, busy: false, generationQueue: []});
  await act(async () => resolve(JSON.stringify(page)));
  expect(text()).toContain('Очередь пуста');
  expect(text()).not.toContain('Task running');
});

test('unmount removes subscription and ignores late snapshots', async () => {
  await render();
  await act(async () => tree.unmount());
  expect((Podcast.onSnapshot as jest.Mock).mock.results[0].value.remove).toHaveBeenCalledTimes(1);
  await emit({revision: 6, busy: false, generationQueue: []});
});

test('asynchronous native storage failure is visible on the queue screen', async () => {
  await render();
  await emit({...page, revision: 6, error: 'generation_storage_failed'});
  expect(text()).toContain('Не удалось сохранить очередь.');
});

test('StorageError remains visible with cached tasks and disables all queue commands', async () => {
  const healthy = {...page, busy: false, generationQueue: [task('paused', 'PAUSED')]};
  (Podcast.getSnapshot as jest.Mock).mockResolvedValue(JSON.stringify(healthy));
  await render();
  await emit({...healthy, revision: 6, stage: 'StorageError', storageReady: false, error: 'storage_recovery_failed'});
  expect(text()).toContain('Не удалось загрузить очередь.');
  expect(buttons('Продолжить очередь')[0].props.disabled).toBe(true);
  expect(label('Продолжить выпуск: Task paused').props.disabled).toBe(true);
  expect(label('Убрать задание: Task paused').props.disabled).toBe(true);
});

test('late StorageError cannot change flags of a newer healthy snapshot', async () => {
  let resolve!: (raw: string) => void;
  (Podcast.getSnapshot as jest.Mock).mockReturnValue(new Promise(done => { resolve = done; }));
  await render(); await emit(page);
  await act(async () => resolve('{"revision":1,"stage":"StorageError","storageReady":false}'));
  expect(text()).not.toContain('Не удалось загрузить очередь.');
  expect(buttons('Приостановить очередь')[0].props.disabled).toBe(false);
});

test('storage retry calls recovery once and waits for a newer final snapshot', async () => {
  const failure = {revision: 1, stage: 'StorageError', storageReady: false, busy: false};
  (Podcast.getSnapshot as jest.Mock).mockResolvedValue(JSON.stringify(failure));
  await render();
  await act(async () => { const retry = buttons('Повторить загрузку очереди')[0]; retry.props.onPress(); retry.props.onPress(); });
  expect(Podcast.recoverLibrary).toHaveBeenCalledTimes(1);
  expect(Podcast.getSnapshot).toHaveBeenCalledTimes(1);
  expect(buttons('Повторить загрузку очереди')[0].props.disabled).toBe(true);
  await emit(failure);
  expect(text()).toContain('Восстанавливаем очередь…');
  await emit({...failure, revision: 2, busy: true});
  expect(text()).toContain('Восстанавливаем очередь…');
  await emit({revision: 3, stage: 'Idle', busy: false, storageReady: true, generationQueue: []});
  expect(text()).toContain('Очередь пуста');
  expect(text()).not.toContain('Не удалось загрузить очередь.');
  expect(text()).not.toContain('Восстанавливаем очередь…');
  expect(Podcast.resumeQueue).not.toHaveBeenCalled();
});

test('failed recovery allows another explicit attempt without losing cached jobs', async () => {
  const failure = {...page, busy: false, stage: 'StorageError', storageReady: false};
  (Podcast.getSnapshot as jest.Mock).mockResolvedValue(JSON.stringify(failure));
  await render(); await act(async () => buttons('Повторить загрузку очереди')[0].props.onPress());
  await emit({...failure, revision: 6});
  expect(text()).toContain('Task first');
  expect(buttons('Повторить загрузку очереди')[0].props.disabled).toBe(false);
  await act(async () => buttons('Повторить загрузку очереди')[0].props.onPress());
  expect(Podcast.recoverLibrary).toHaveBeenCalledTimes(2);
});

test('recovery timeout restores retry while keeping controls disabled', async () => {
  jest.useFakeTimers();
  try {
    (Podcast.getSnapshot as jest.Mock).mockResolvedValue('{"revision":1,"stage":"StorageError","storageReady":false}');
    await render(); await act(async () => buttons('Повторить загрузку очереди')[0].props.onPress());
    await act(async () => jest.advanceTimersByTime(30001));
    expect(buttons('Повторить загрузку очереди')[0].props.disabled).toBe(false);
    expect(text()).not.toContain('Очередь пуста');
  } finally { jest.useRealTimers(); }
});
