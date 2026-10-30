import {useEffect, useRef, useState} from 'react';
import Podcast from '../specs/NativePodcast';

export type QueueTask = {id: string; title: string; status: string; phase: string; profile: string; error: string; hasScript: boolean};
type QueueState = {revision: number; busy: boolean; storageReady: boolean; stage: string; chunk: number; chunks: number; error: string; generationQueue: QueueTask[]};
const initial: QueueState = {revision: -1, busy: false, storageReady: false, stage: 'Loading', chunk: 0, chunks: 0, error: '', generationQueue: []};
const initialView = {state: initial, loaded: false, failed: false, storageFailed: false, recovering: false};

/** All snapshot flags share the same revision gate, including storage errors. */
export function useGenerationQueue() {
  const [view, setView] = useState(initialView);
  const [attempt, retry] = useState(0);
  const revision = useRef(-1);
  const recovery = useRef<'storage' | 'snapshot' | null>(null);
  const recoveryRevision = useRef(-1);
  useEffect(() => {
    let active = true; let received = false;
    const failed = () => {
      if (!active || received) { return; }
      recovery.current = null;
      setView(old => ({...old, failed: true, recovering: false}));
    };
    const apply = (raw: string) => {
      if (!active) { return; }
      try {
        const rawState = JSON.parse(raw);
        const next = {...initial, ...rawState} as QueueState;
        if (!Number.isFinite(next.revision) || next.revision < revision.current) { return; }
        revision.current = next.revision; received = true;
        const storageFailed = next.stage === 'StorageError' || (!next.storageReady && next.error === 'storage_recovery_failed');
        if (recovery.current === 'snapshot' || (recovery.current === 'storage' && next.revision > recoveryRevision.current && !next.busy)) { recovery.current = null; }
        const hasQueue = Array.isArray(rawState.generationQueue);
        const recovering = recovery.current !== null;
        setView(old => ({state: {...next, generationQueue: hasQueue ? next.generationQueue : old.state.generationQueue},
          loaded: hasQueue || old.loaded, failed: storageFailed, storageFailed, recovering}));
      } catch { failed(); }
    };
    const sub = Podcast.onSnapshot(apply);
    Podcast.getSnapshot().then(apply).catch(failed);
    return () => { active = false; sub.remove(); };
  }, [attempt]);
  useEffect(() => {
    if (!view.recovering) { return; }
    const timer = setTimeout(() => {
      recovery.current = null;
      setView(old => ({...old, failed: true, recovering: false}));
    }, 30000);
    return () => clearTimeout(timer);
  }, [view.recovering]);
  const reload = () => {
    if (recovery.current) { return; }
    recovery.current = view.storageFailed ? 'storage' : 'snapshot'; recoveryRevision.current = revision.current;
    setView(old => ({...old, recovering: true}));
    if (view.storageFailed) {
      try { Podcast.recoverLibrary(); } catch {
        recovery.current = null; setView(old => ({...old, failed: true, recovering: false}));
      }
    } else { retry(old => old + 1); }
  };
  return {...view, reload, canControl: view.loaded && view.state.storageReady && !view.failed && !view.recovering};
}
