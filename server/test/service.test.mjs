import test from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes, createHmac } from 'node:crypto';
import { mkdtempSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Store } from '../src/store.mjs';
import { Service } from '../src/service.mjs';
import { ProviderApi, ServiceError, sanitizeYouTube, sanitizePage, youtubeTarget } from '../src/provider.mjs';
import { httpServer } from '../src/http.mjs';

const id = 'UC' + 'a'.repeat(22);
const youtube = (count = 12300) => ({ items: [{ id, snippet: { title: 'Fixture channel' },
  statistics: { subscriberCount: String(count), hiddenSubscriberCount: false } }] });
const page = (id = '99', count = 42) => ({ id, name: 'Fixture Page ' + id, followers_count: count });
const pages = [{ id: '99', name: 'Fixture Page', token: 'fixture-page-token-99' },
  { id: '100', name: 'Other Page', token: 'fixture-page-token-100' }];
const config = { origin: 'https://service.example', youtubeKey: 'fixture-google-key',
  metaAppId: '123', metaSecret: 'fixture-meta-secret', youtubeDailyBudget: 9000 };
function fixture(t, overrides = {}) {
  const store = new Store(':memory:', randomBytes(32)); t.after(() => store.close());
  let now = 1792700000000;
  const calls = [];
  const provider = { youtube: async target => { calls.push(['youtube', target]); return youtube(); },
    facebookPages: async code => { calls.push(['login', code]); return { userId: '42', pages }; },
    page: async (id, token) => { calls.push(['page', id, token]); return page(id); }, ...overrides };
  const service = new Service(store, config, { provider, now: () => now });
  const token = service.register('fixture-ip').installationToken;
  const device = service.authorize('Bearer ' + token);
  return { service, store, provider, calls, token, device, advance: ms => { now += ms; }, now: () => now };
}
async function ready(f, expectedId = null) {
  const login = f.service.startFacebook(f.device, expectedId);
  const browser = f.service.browserStart(login.flowId);
  const oauth = new URL(browser.url);
  const params = new URLSearchParams({ state: oauth.searchParams.get('state'), code: 'fixture-code' });
  await f.service.callback(params, browser.cookie);
  return { ...login, params, browser };
}

test('encrypted durable storage keeps tokens out of SQLite and binds ciphertext to its row', () => {
  const path = join(mkdtempSync(join(tmpdir(), 'follower-service-')), 'store.sqlite'), key = randomBytes(32);
  let store = new Store(path, key);
  store.set('grant', 'one', { token: 'synthetic-sensitive-token' });
  const encoded = store.db.prepare('SELECT value FROM records').get().value;
  store.set('grant', 'two', { token: 'other' });
  store.db.prepare('UPDATE records SET value=? WHERE id=?').run(encoded, 'two');
  assert.throws(() => store.get('grant', 'two'));
  store.close();
  assert.equal(readFileSync(path).includes('synthetic-sensitive-token'), false);
  store = new Store(path, key);
  assert.equal(store.get('grant', 'one').token, 'synthetic-sensitive-token'); store.close();
});

test('YouTube link resolves through one shared cache and preserves the observation time', async t => {
  const f = fixture(t);
  const [a, b] = await Promise.all([f.service.youtube('youtube.com/@fixture'), f.service.youtube('youtube.com/@fixture')]);
  assert.deepEqual(a, b); assert.equal(f.calls.length, 1);
  f.advance(59000); assert.equal((await f.service.youtube('youtube.com/@fixture')).observedAt, a.observedAt);
  f.advance(2000); assert.ok((await f.service.youtube('youtube.com/@fixture')).observedAt > a.observedAt);
  assert.equal(f.calls.length, 2);
  await assert.rejects(f.service.youtube('https://evil.example/@fixture'), { code: 'CHECK_REQUIRED' });
});

test('YouTube operator budget and upstream quota failure stop subsequent remote requests', async t => {
  const f = fixture(t); f.service.config = { ...config, youtubeDailyBudget: 1 };
  await f.service.youtube('youtube.com/@one');
  await assert.rejects(f.service.youtube('youtube.com/@two'), { code: 'RATE_LIMITED' });
  assert.equal(f.calls.length, 1);
  const g = fixture(t, { youtube: async () => { throw new ServiceError('RATE_LIMITED', 429, 300); } });
  await assert.rejects(g.service.youtube('youtube.com/@one'));
  g.provider.youtube = () => assert.fail('quota cooldown must prevent the request');
  await assert.rejects(g.service.youtube('youtube.com/@two'), { code: 'RATE_LIMITED', retryAfterSeconds: 300 });
});

test('OAuth requires state, browser binding, owner device, and a one-use callback', async t => {
  const f = fixture(t), login = f.service.startFacebook(f.device), browser = f.service.browserStart(login.flowId);
  const url = new URL(browser.url), params = new URLSearchParams({ state: url.searchParams.get('state'), code: 'fixture-code' });
  assert.equal(url.origin, 'https://www.facebook.com');
  assert.equal(url.searchParams.get('scope'), 'pages_show_list,pages_read_engagement');
  assert.equal(url.searchParams.get('redirect_uri'), config.origin + '/oauth/facebook/callback');
  assert.equal(url.href.includes(config.metaSecret), false);
  await assert.rejects(f.service.callback(params, 'x'.repeat(43)), { code: 'INVALID_LOGIN' });
  const bad = new URLSearchParams(params); bad.set('state', login.flowId + '.' + 'x'.repeat(43));
  await assert.rejects(f.service.callback(bad, browser.cookie), { code: 'INVALID_LOGIN' });
  assert.throws(() => f.service.status('other-device', login.flowId), { code: 'LOGIN_EXPIRED' });
  await f.service.callback(params, browser.cookie);
  await assert.rejects(f.service.callback(params, browser.cookie), { code: 'INVALID_LOGIN' });
  assert.equal(f.calls.filter(call => call[0] === 'login').length, 1);
  const response = f.service.status(f.device, login.flowId);
  assert.equal(response.status, 'ready'); assert.equal(response.pages.length, 2);
  assert.equal(JSON.stringify(response).includes('token'), false);
});

test('selection verifies identity, retains only the selected Page token and replays after a lost response', async t => {
  const f = fixture(t), login = await ready(f, '99');
  assert.deepEqual(f.service.status(f.device, login.flowId).pages.map(x => x.id), ['99']);
  await assert.rejects(f.service.select(f.device, login.flowId, '100'), { code: 'PAGE_NOT_ALLOWED' });
  const observation = await f.service.select(f.device, login.flowId, '99');
  assert.equal(observation.payload.followers_count, 42);
  assert.deepEqual(await f.service.select(f.device, login.flowId, '99'), observation);
  assert.equal(f.service.status(f.device, login.flowId).selectedId, '99');
  assert.equal(JSON.stringify(f.store.get('flow', login.flowId)).includes('token'), false);
  assert.equal(f.store.get('grant', f.device + ':99').token, pages[0].token);
  assert.equal(f.store.get('grant', f.device + ':100'), null);
  assert.equal(f.calls.filter(call => call[0] === 'page').length, 1);
  assert.deepEqual(await f.service.facebook(f.device, '99'), observation);
  await assert.rejects(f.service.facebook('other-device', '99'), { code: 'REAUTH_REQUIRED' });
});

test('cancel, denial, missing permissions and expiry never create a Page grant', async t => {
  const f = fixture(t), login = f.service.startFacebook(f.device), browser = f.service.browserStart(login.flowId);
  const params = new URLSearchParams({ state: new URL(browser.url).searchParams.get('state'), error: 'access_denied' });
  await f.service.callback(params, browser.cookie);
  assert.equal(f.service.status(f.device, login.flowId).status, 'cancelled'); assert.equal(f.calls.length, 0);
  f.service.cancelFacebook(f.device); assert.throws(() => f.service.status(f.device, login.flowId));
  const second = await ready(f);
  f.advance(600001);
  await assert.rejects(f.service.select(f.device, second.flowId, '99'), { code: 'LOGIN_EXPIRED' });
  f.service.sweep(); assert.equal(f.store.ids('flow').length, 0);
  assert.equal(f.store.ids('grant').length, 0);
  const g = fixture(t, { facebookPages: async () => { throw new ServiceError('PERMISSION_REQUIRED', 403); } });
  const denied = await ready(g);
  assert.equal(g.service.status(g.device, denied.flowId).error, 'PERMISSION_REQUIRED');
});

test('logout while a callback or selected-page request is in flight cannot restore a connection', async t => {
  let release;
  const f = fixture(t, { facebookPages: () => new Promise(resolve => { release = resolve; }) });
  const login = f.service.startFacebook(f.device), browser = f.service.browserStart(login.flowId);
  const task = f.service.callback(new URLSearchParams({ state: new URL(browser.url).searchParams.get('state'), code: 'code' }), browser.cookie);
  f.service.disconnect(f.device); release({ userId: '42', pages }); await task;
  assert.equal(f.store.ids('flow').length, 0);
  const g = fixture(t), readyLogin = await ready(g);
  g.provider.page = () => new Promise(resolve => { release = resolve; });
  const selection = g.service.select(g.device, readyLogin.flowId, '99');
  g.service.disconnect(g.device); release(page());
  await assert.rejects(selection); assert.equal(g.store.ids('grant').length, 0);
});

test('late count after disconnect is rejected and one Page removal preserves another', async t => {
  const f = fixture(t);
  for (const id of ['99', '100']) { const login = await ready(f); await f.service.select(f.device, login.flowId, id); }
  f.advance(61000);
  let release;
  f.provider.page = () => new Promise(resolve => { release = resolve; });
  const request = f.service.facebook(f.device, '99');
  f.service.disconnect(f.device, '99'); release(page());
  await assert.rejects(request, { code: 'REAUTH_REQUIRED' });
  assert.equal(f.store.get('cache', 'facebook:' + f.device + ':99'), null);
  assert.ok(f.store.get('grant', f.device + ':100'));
});

test('Page token expiry is explicit and rate limiting never serves expired cached data as new', async t => {
  const f = fixture(t), login = await ready(f); await f.service.select(f.device, login.flowId, '99'); f.advance(61000);
  let calls = 0;
  f.provider.page = async () => { calls++; throw new ServiceError('RATE_LIMITED', 429, 300); };
  await assert.rejects(f.service.facebook(f.device, '99'), { code: 'RATE_LIMITED' });
  await assert.rejects(f.service.facebook(f.device, '99'), { code: 'RATE_LIMITED' }); assert.equal(calls, 1);
  f.advance(300000);
  f.provider.page = async () => { throw new ServiceError('REAUTH_REQUIRED', 401); };
  await assert.rejects(f.service.facebook(f.device, '99'), { code: 'REAUTH_REQUIRED' });
  assert.equal(f.store.get('grant', f.device + ':99'), null);
});

test('signed Meta deauthorization deletes only that Facebook user and rejects forged requests', async t => {
  const f = fixture(t), login = await ready(f); await f.service.select(f.device, login.flowId, '99');
  const encoded = Buffer.from(JSON.stringify({ algorithm: 'HMAC-SHA256', user_id: '42', issued_at: f.now() / 1000 })).toString('base64url');
  const signature = createHmac('sha256', config.metaSecret).update(encoded).digest('base64url');
  assert.throws(() => f.service.metaDeletion('forged.' + encoded), { code: 'INVALID_LOGIN' });
  const result = f.service.metaDeletion(signature + '.' + encoded);
  assert.match(result.confirmation_code, /^[A-Za-z0-9_-]{43}$/);
  assert.equal(f.store.ids('grant').length, 0); assert.ok(f.store.get('device', f.device));
});

test('HTTP boundary rejects unauthenticated access and keeps all provider credentials out of responses', async t => {
  const f = fixture(t), server = httpServer(f.service);
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve)); t.after(() => new Promise(resolve => server.close(resolve)));
  const origin = 'http://127.0.0.1:' + server.address().port;
  const call = (path, input, token = f.token) => fetch(origin + path, { method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token }, body: JSON.stringify(input) });
  assert.equal((await call('/v1/youtube', { address: 'youtube.com/@test' }, 'invalid')).status, 401);
  const response = await call('/v1/youtube', { address: 'youtube.com/@test' });
  assert.equal(response.status, 200); assert.equal(response.headers.get('cache-control'), 'no-store');
  assert.equal((await response.text()).includes(config.youtubeKey), false);
  const login = await (await call('/v1/facebook/login', {})).json();
  const start = await fetch(origin + '/oauth/facebook/start?flow=' + login.flowId, { redirect: 'manual' });
  const cookie = start.headers.get('set-cookie').split(';')[0];
  const state = new URL(start.headers.get('location')).searchParams.get('state');
  const completed = await fetch(origin + '/oauth/facebook/callback?' + new URLSearchParams({ state, code: 'fixture' }), { headers: { cookie } });
  assert.equal(completed.status, 200); assert.match(await completed.text(), /followertracker:\/\/facebook\/complete/);
  const status = await (await call('/v1/facebook/status', { flowId: login.flowId })).text();
  assert.equal(status.includes('fixture-page-token'), false);
  assert.equal(status.includes('access_token'), false);
  assert.equal((await call('/v1/facebook/select', { flowId: login.flowId, pageId: '99' })).status, 200);
  await call('/v1/facebook/disconnect', { pageId: '99' });
  assert.equal((await call('/v1/facebook/count', { pageId: '99' })).status, 401);
});

test('unconfigured providers fail clearly and registration throttling is enforced', async t => {
  const f = fixture(t); f.service.config = { origin: config.origin };
  await assert.rejects(f.service.youtube('youtube.com/@test'), { code: 'SERVICE_NOT_CONFIGURED' });
  assert.throws(() => f.service.startFacebook(f.device), { code: 'SERVICE_NOT_CONFIGURED' });
  for (let i = 0; i < 9; i++) f.service.register('fixture-ip');
  assert.throws(() => f.service.register('fixture-ip'), { code: 'RATE_LIMITED' });
});

test('provider client requests only official fields and handles long-lived token exchange and pagination', async () => {
  const requests = [];
  const provider = new ProviderApi(config, async (url, options) => {
    requests.push([url, options]);
    let payload;
    if (url.hostname === 'www.googleapis.com') payload = youtube();
    else if (url.pathname.endsWith('/oauth/access_token')) payload = { access_token: url.searchParams.has('grant_type') ? 'long-fixture' : 'short-fixture' };
    else if (url.pathname.endsWith('/me/permissions')) payload = { data: ['pages_show_list', 'pages_read_engagement'].map(permission => ({ permission, status: 'granted' })) };
    else if (url.pathname.endsWith('/me')) payload = { id: '42' };
    else if (url.pathname.endsWith('/me/accounts')) payload = url.searchParams.has('after')
      ? { data: [{ id: '100', name: 'Other', access_token: pages[1].token }] }
      : { data: [{ id: '99', name: 'First', access_token: pages[0].token }],
        paging: { next: 'https://evil.example/steal?access_token=ignored', cursors: { after: 'next-fixture' } } };
    else payload = page();
    return new Response(JSON.stringify(payload));
  });
  await provider.youtube({ handle: 'test' });
  assert.equal(requests[0][0].searchParams.get('fields'), 'items(id,snippet/title,statistics(subscriberCount,hiddenSubscriberCount))');
  const result = await provider.facebookPages('fixture-code'); assert.equal(result.pages.length, 2);
  assert.equal(result.userId, '42');
  await provider.page('99', pages[0].token);
  for (const [url, options] of requests) {
    assert.ok(['www.googleapis.com', 'graph.facebook.com'].includes(url.hostname));
    assert.equal(options.redirect, 'error'); assert.equal(options.headers.Cookie, undefined);
    assert.equal(url.searchParams.has('access_token'), false);
    if (options.headers.Authorization) assert.ok(url.searchParams.get('appsecret_proof'));
  }
  assert.equal(requests.at(-1)[1].headers.Authorization, 'Bearer ' + pages[0].token);
});

test('malformed numbers, hidden subscribers and missing follower fields never become zero', () => {
  for (const value of [null, -1, 1.5, '1e3', '9007199254740992']) {
    const root = youtube(); root.items[0].statistics.subscriberCount = value;
    assert.throws(() => sanitizeYouTube(root, { id }));
  }
  const hidden = youtube(); hidden.items[0].statistics.hiddenSubscriberCount = true;
  assert.throws(() => sanitizeYouTube(hidden, { id }));
  assert.throws(() => sanitizePage({ id: '99', name: 'Fixture', fan_count: 8 }, '99'));
  assert.throws(() => sanitizePage(page('100'), '99'));
  assert.deepEqual(youtubeTarget('https://www.youtube.com/@한글채널'), { handle: '한글채널' });
  for (const url of ['http://youtube.com/@test', 'https://youtube.com.evil/@test', 'https://x@youtube.com/@test', 'https://youtube.com/@../other'])
    assert.throws(() => youtubeTarget(url));
});

test('upstream errors are sanitized, bounded, and do not follow redirects', async () => {
  const fetcher = async () => new Response(JSON.stringify({ error: { code: 190, message: 'synthetic-secret' } }), { status: 400 });
  await assert.rejects(new ProviderApi(config, fetcher).page('99', 'fixture'), { code: 'REAUTH_REQUIRED', message: 'REAUTH_REQUIRED' });
  await assert.rejects(new ProviderApi(config, async () => new Response('x'.repeat(65537))).page('99', 'fixture'), { code: 'FORMAT_CHANGED' });
  await assert.rejects(new ProviderApi(config, async () => new Response('not-json', { status: 429, headers: { 'Retry-After': '301' } })).page('99', 'fixture'),
    { code: 'RATE_LIMITED', retryAfterSeconds: 301 });
  await assert.rejects(new ProviderApi(config, async () => new Response(JSON.stringify({ error: { code: 403,
    errors: [{ reason: 'accessNotConfigured' }] } }), { status: 403 })).youtube({ id }), { code: 'SERVICE_NOT_CONFIGURED' });
});

test('Business Login configuration replaces scope and requests an authorization code', t => {
  const f = fixture(t); f.service.config = { ...config, metaLoginConfigId: '456' };
  const login = f.service.startFacebook(f.device);
  const url = new URL(f.service.browserStart(login.flowId).url);
  assert.equal(url.searchParams.get('config_id'), '456');
  assert.equal(url.searchParams.has('scope'), false);
  assert.equal(url.searchParams.get('response_type'), 'code');
  assert.equal(url.searchParams.get('override_default_response_type'), 'true');
});

test('failed durable selection transaction leaves no partial grant and permits retry', async t => {
  const f = fixture(t), login = await ready(f);
  const original = f.store.set.bind(f.store);
  f.store.set = (kind, id, value) => {
    if (kind === 'cache') throw new Error('synthetic storage failure');
    return original(kind, id, value);
  };
  await assert.rejects(f.service.select(f.device, login.flowId, '99'));
  assert.equal(f.store.ids('grant').length, 0);
  assert.equal(f.service.status(f.device, login.flowId).status, 'ready');
  f.store.set = original;
  assert.equal((await f.service.select(f.device, login.flowId, '99')).payload.id, '99');
});
