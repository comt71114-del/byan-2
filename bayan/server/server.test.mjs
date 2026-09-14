import test from 'node:test';
import assert from 'node:assert/strict';
import { createTokenServer } from './server.mjs';
const accessToken = 'test-access-token-at-least-32-characters';
test('token broker enforces authentication, restricts methods, caches and refreshes', async () => {
  let calls = 0, now = 100000;
  const server = createTokenServer({ key: 'private-key', region: 'westeurope', accessToken, clock: () => now,
    fetchImpl: async () => { calls++; return { ok: true, text: async () => 'cloud-token' }; } });
  await new Promise(r => server.listen(0, '127.0.0.1', r));
  const url = `http://127.0.0.1:${server.address().port}/token`;
  try {
    assert.equal((await fetch(url)).status, 405);
    assert.equal((await fetch(url, { method: 'POST' })).status, 401);
    assert.equal(calls, 0);
    const options = { method: 'POST', headers: { Authorization: `Bearer ${accessToken}` } };
    const response = await fetch(url, options);
    assert.equal(response.headers.get('cache-control'), 'no-store');
    assert.deepEqual(await response.json(), { token: 'cloud-token', region: 'westeurope' });
    await fetch(url, options); assert.equal(calls, 1);
    now += 31000; await fetch(url, options); assert.equal(calls, 2);
    for (let i = 0; i < 30; i++) await fetch(url, options);
    assert.equal((await fetch(url, options)).status, 429);
  } finally { await new Promise(r => server.close(r)); }
});
test('broker masks cloud failures and never returns secrets', async () => {
  const server = createTokenServer({ key: 'private-key', region: 'westeurope', accessToken,
    fetchImpl: async () => { throw Error('private-key'); } });
  await new Promise(r => server.listen(0, '127.0.0.1', r));
  try {
    const response = await fetch(`http://127.0.0.1:${server.address().port}/token`, { method: 'POST', headers: { Authorization: `Bearer ${accessToken}` } });
    assert.equal(response.status, 502); assert.equal((await response.text()).includes('private-key'), false);
  } finally { await new Promise(r => server.close(r)); }
});
