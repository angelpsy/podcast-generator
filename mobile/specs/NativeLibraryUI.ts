import type {TurboModule} from 'react-native';
import {TurboModuleRegistry} from 'react-native';
export interface Spec extends TurboModule {
  getPlaylists(): Promise<string>;
  getPlaylist(id: string): Promise<string>;
  mutatePlaylist(request: string): Promise<string>;
  updatePlaylistQueue(id: string): Promise<void>;
  updateTopicQueue(topic: string, node: string, allProfiles: boolean): Promise<void>;
  playPlaylist(id: string): Promise<void>;
  getTopicPlaylist(topic: string, node: string, allProfiles: boolean): Promise<string>;
  playTopicPlaylist(topic: string, node: string, allProfiles: boolean): Promise<void>;
  getPreferences(): Promise<string>;
  savePreferences(request: string): Promise<string>;
}
export default TurboModuleRegistry.getEnforcing<Spec>('LibraryUI');
