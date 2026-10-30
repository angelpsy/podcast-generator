import type {TurboModule} from 'react-native';
import {TurboModuleRegistry} from 'react-native';

export interface Spec extends TurboModule {
  getTopics(): Promise<string>;
  getTopic(id: string, nodeId: string): Promise<string>;
  mutate(request: string): Promise<string>;
  moveTargets(topicId: string, nodeId: string): Promise<string>;
  searchPodcasts(query: string, offset: number): Promise<string>;
  requestOutline(request: string): Promise<string>;
  getOutlines(topicId: string, nodeId: string): Promise<string>;
  previewOutline(id: string): Promise<string>;
  reviewOutline(id: string, revision: number): Promise<string>;
  applyOutline(id: string, revision: number): Promise<string>;
  discardOutline(id: string): Promise<void>;
  cancelOutline(id: string): void;
  play(topicId: string, nodeId: string): Promise<void>;
}
export default TurboModuleRegistry.getEnforcing<Spec>('Topics');
