import type {TurboModule, CodegenTypes} from 'react-native';
import {TurboModuleRegistry} from 'react-native';

export interface Spec extends TurboModule {
  getSnapshot(): Promise<string>;
  previewGeneration(request: string): Promise<string>;
  enqueueGeneration(request: string): Promise<string>;
  resumeQueue(id: string): void;
  cancelTask(id: string): void;
  create(topic: string, model: string): void;
  runRussianSample(): void;
  retryAudio(): void;
  selectEpisode(id: string): void;
  deleteEpisode(id: string): void;
  searchLibrary(query: string): void;
  recoverLibrary(): void;
  cancel(): void;
  play(): void;
  pause(): void;
  restartPlayback(): void;
  openExternal(): void;
  readonly onSnapshot: CodegenTypes.EventEmitter<string>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('Podcast');
