import type {TurboModule, CodegenTypes} from 'react-native';
import {TurboModuleRegistry} from 'react-native';

// Only safe UI snapshots cross this boundary. OAuth credentials stay in Kotlin.
export interface Spec extends TurboModule {
  getSnapshot(): Promise<string>;
  signIn(): Promise<void>;
  cancel(): void;
  listModels(): Promise<void>;
  sendPrompt(model: string, text: string): Promise<void>;
  signOut(): Promise<void>;
  readonly onSnapshot: CodegenTypes.EventEmitter<string>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('Siwc');
