/** Unknown duration has no determined progress; stale positions stay inside the track. */
export function playbackProgress(positionMs: number, durationMs: number): number {
  if (!Number.isFinite(positionMs) || !Number.isFinite(durationMs) || durationMs <= 0) { return 0; }
  return Math.max(0, Math.min(1, positionMs / durationMs));
}
