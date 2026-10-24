import type {TurboModule, CodegenTypes} from 'react-native';
import {TurboModuleRegistry} from 'react-native';

export interface Spec extends TurboModule {
  getSnapshot(): Promise<string>;
  create(topic: string, model: string): void;
  runRussianSample(): void;
  retryAudio(): void;
  cancel(): void;
  play(): void;
  pause(): void;
  restartPlayback(): void;
  openExternal(): void;
  readonly onSnapshot: CodegenTypes.EventEmitter<string>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('Podcast');
