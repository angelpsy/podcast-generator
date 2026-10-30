import {playbackProgress} from '../src/playbackMath';
test('progress follows elapsed playback and reaches both ends', () => {
  expect(playbackProgress(0, 60000)).toBe(0);
  expect(playbackProgress(15000, 60000)).toBe(0.25);
  expect(playbackProgress(60000, 60000)).toBe(1);
});
test('progress handles missing duration and positions outside the current recording', () => {
  expect(playbackProgress(1234, 0)).toBe(0);
  expect(playbackProgress(-1000, 60000)).toBe(0);
  expect(playbackProgress(90000, 60000)).toBe(1);
  expect(playbackProgress(NaN, 60000)).toBe(0);
  expect(playbackProgress(1000, Infinity)).toBe(0);
});
