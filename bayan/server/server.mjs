import http from 'node:http';
import { timingSafeEqual } from 'node:crypto';
import { pathToFileURL } from 'node:url';

export function createTokenServer({ key, region, accessToken, fetchImpl = fetch, clock = Date.now }) {
  if (!key || !/^[a-z0-9]+$/.test(region || '') || (accessToken || '').length < 32)
    throw new Error('Set SPEECH_KEY, SPEECH_REGION, and APP_ACCESS_TOKEN (32+ characters).');
  const expected = Buffer.from(`Bearer ${accessToken}`);
  let cached = null, pending = null;
  const attempts = new Map();
  return http.createServer(async (req, res) => {
    res.setHeader('Content-Type', 'application/json; charset=utf-8');
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('X-Content-Type-Options', 'nosniff');
    const reply = (status, payload) => { res.writeHead(status); res.end(JSON.stringify(payload)); };
    if (req.url !== '/token') return reply(404, { error: 'Not found' });
    if (req.method !== 'POST') return reply(405, { error: 'POST required' });
    const now = clock(), ip = req.socket.remoteAddress;
    for (const [id, record] of attempts) if (record.until < now) attempts.delete(id);
    if (!attempts.has(ip) && attempts.size >= 10000) return reply(503, { error: 'Busy' });
    const entry = attempts.get(ip) || { count: 0, until: now + 60000 };
    attempts.set(ip, entry);
    if (++entry.count > 30) return reply(429, { error: 'Too many requests' });
    const received = Buffer.from(req.headers.authorization || '');
    if (received.length !== expected.length || !timingSafeEqual(received, expected)) return reply(401, { error: 'Unauthorized' });
    try {
      if (!cached || cached.until < now) {
        if (!pending) pending = (async () => {
          const response = await fetchImpl(`https://${region}.api.cognitive.microsoft.com/sts/v1.0/issueToken`, {
            method: 'POST', headers: { 'Ocp-Apim-Subscription-Key': key }, signal: AbortSignal.timeout(10000)
          });
          if (!response.ok) throw new Error('Upstream failed');
          const token = await response.text();
          if (!token || token.length > 30000) throw new Error('Invalid upstream response');
          // Cache only 30 seconds: each issued token still has >9 minutes remaining.
          cached = { token, until: clock() + 30000 };
        })().finally(() => { pending = null; });
        await pending;
      }
      return reply(200, { token: cached.token, region });
    } catch {
      return reply(502, { error: 'Speech token service unavailable' });
    }
  });
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const server = createTokenServer({ key: process.env.SPEECH_KEY, region: process.env.SPEECH_REGION, accessToken: process.env.APP_ACCESS_TOKEN });
  server.requestTimeout = 15000;
  server.headersTimeout = 10000;
  server.listen(Number(process.env.PORT || 8080), '127.0.0.1', () => console.log('Bayan token service listening on loopback. Expose through authenticated HTTPS infrastructure.'));
}
