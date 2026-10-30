import type {TurboModule, CodegenTypes} from 'react-native';
import {TurboModuleRegistry} from 'react-native';

export interface Spec extends TurboModule {
  getSnapshot(): Promise<string>;
  playEpisode(id: string): void;
  playLibrary(): void;
  play(): void;
  pause(): void;
  seek(positionMs: number): void;
  skip(deltaMs: number): void;
  previous(): void;
  next(): void;
  setSpeed(speed: number): void;
  setRepeat(mode: number): void;
  readonly onSnapshot: CodegenTypes.EventEmitter<string>;
}
export default TurboModuleRegistry.getEnforcing<Spec>('Playback');
