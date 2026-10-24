import {initialSnapshot, parseSnapshot, safeDiagnostics} from '../src/state';

test('diagnostics exclude identity, response text and unexpected credential fields', () => {
  const state = parseSnapshot(JSON.stringify({
    ...initialSnapshot,
    email: 'private@example.test',
    answer: 'private answer',
    access_token: 'secret-token',
    stage: 'Failed',
    error: 'subscription_sharing_user_not_eligible',
    requestId: 'req-123',
  }));
  const diagnostics = safeDiagnostics(state, '36');
  expect(diagnostics).toContain('subscription_sharing_user_not_eligible');
  expect(diagnostics).toContain('req-123');
  for (const secret of ['private@example.test', 'private answer', 'secret-token']) {
    expect(diagnostics).not.toContain(secret);
  }
});
