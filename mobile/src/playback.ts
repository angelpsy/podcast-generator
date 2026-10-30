import {useEffect, useState} from 'react';
import Playback from '../specs/NativePlayback';

export type PlaybackState = {
  revision: number; connected: boolean; playing: boolean; id: string; title: string;
  queue: {id: string; title: string}[]; index: number; positionMs: number; durationMs: number;
  speed: number; repeat: number; error: string;
};
const initial: PlaybackState = {revision: 0, connected: false, playing: false, id: '', title: '',
  queue: [], index: 0, positionMs: 0, durationMs: 0, speed: 1, repeat: 0, error: ''};
export function usePlayback() {
  const [state, setState] = useState(initial);
  useEffect(() => {
    let active = true;
    const apply = (raw: string) => {
      try {
        const next = {...initial, ...JSON.parse(raw)} as PlaybackState;
        if (active) { setState(old => next.revision >= old.revision ? next : old); }
      } catch { /* Keep the last confirmed snapshot. */ }
    };
    const sub = Playback.onSnapshot(apply);
    Playback.getSnapshot().then(apply).catch(() => {
      if (active) { setState(old => ({...old, error: 'playback_connection_failed'})); }
    });
    return () => { active = false; sub.remove(); };
  }, []);
  return state;
}
export const playbackTime = (ms: number) => `${Math.floor(ms / 60000)}:${String(Math.floor(ms / 1000) % 60).padStart(2, '0')}`;
