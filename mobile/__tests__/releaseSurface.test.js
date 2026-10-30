/* eslint-env node, jest */
const fs = require('fs');
const path = require('path');
test('release routes and screens contain no diagnostic actions or voice sample', () => {
  const app = fs.readFileSync(path.join(__dirname, '../App.tsx'), 'utf8');
  expect(fs.readFileSync(path.join(__dirname, '../android/app/src/main/res/values/strings.xml'), 'utf8')).not.toContain('SIWC Spike');
  expect(app).toContain('PlaylistsNavigator'); expect(app).toContain('SettingsScreen'); expect(app).not.toContain('SiwcScreen');
  const sources = ['PodcastScreen', 'SettingsScreen'].map(name => fs.readFileSync(path.join(__dirname, `../src/${name}.tsx`), 'utf8')).join('\n');
  for (const action of ['runRussianSample(', 'sendPrompt(', 'refreshSession(', 'safeDiagnostics(', 'SIWC SPIKE', 'Тест голоса', 'Тестовый запрос', 'Поделиться диагностикой']) { expect(sources).not.toContain(action); }
});
