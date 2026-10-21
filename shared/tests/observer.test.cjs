const { test, mock } = require('node:test');
const assert = require('node:assert/strict');
const { install } = require('../web-profile-observer.js');
const { capture } = require('../web-session-capture.js');

function page(fetch) {
  const win = { location: { href: 'https://www.instagram.com/self/' }, fetch };
  win.top = win;
  return win;
}
const user = (id = '42', followers = 12345) => ({ id, username: id === '42' ? 'self' : 'other', full_name: 'Fixture',
  follower_count: followers, following_count: 0, private_message: 'must not be retained' });
async function ready(condition) {
  for (let i = 0; i < 100 && !condition(); i++) await new Promise(resolve => setTimeout(resolve, 2));
  assert.ok(condition(), 'Expected cloned response state');
}
function doc(win) {
  return { location: win.location, defaultView: win, querySelectorAll: () => [], querySelector: () => null };
}
test('observing a profile response preserves the original fetch promise/body and adds no request', async () => {
  let calls = 0, suppliedArgs;
  const response = new Response(JSON.stringify({ data: { user: user() } }), { headers: { 'Content-Type': 'application/json' } });
  const promise = Promise.resolve(response);
  const win = page(function (...args) { calls++; suppliedArgs = args; return promise; });
  const options = { method: 'POST', body: { get secret() { throw new Error('Never inspect request bodies'); } } };
  assert.equal(install('INSTAGRAM', win), true);
  const returned = win.fetch('/graphql/query', options);
  assert.equal(returned, promise);
  assert.equal(await returned, response);
  assert.equal(await response.text(), JSON.stringify({ data: { user: user() } }));
  await ready(() => win.__followerTrackerObservedProfiles.profiles.length === 1);
  assert.equal(calls, 1); assert.equal(suppliedArgs[1], options);
  const result = capture('INSTAGRAM', '42', doc(win));
  assert.equal(result.followers, 12345); assert.equal(result.following, 0); assert.equal(result.precision, 'EXACT');
  assert.equal(result.stableId, '42');
  assert.equal(JSON.stringify(win.__followerTrackerObservedProfiles.profiles).includes('private_message'), false);
  assert.equal(capture('INSTAGRAM', '99', doc(win)).error, 'exact_count_missing');
});
test('other providers, external pages, Google login and child frames never install observation', () => {
  for (const provider of ['TIKTOK', 'FACEBOOK', 'X', 'REDDIT']) assert.equal(install(provider, page(() => {})), false);
  for (const url of ['https://accounts.google.com/', 'http://www.instagram.com/self/', 'https://instagram.com.example.test/self/',
    'https://user@www.instagram.com/self/', 'https://www.instagram.com:8443/self/']) {
    const win = page(() => {}); win.location.href = url;
    assert.equal(install('INSTAGRAM', win), false);
    assert.equal(win.__followerTrackerObservedProfiles, undefined);
  }
  const child = page(() => {}); child.top = {};
  assert.equal(install('INSTAGRAM', child), false);
});
test('429, untrusted requests and redirected login responses cannot create a count', async () => {
  for (const [url, code, redirected] of [['/graphql/query', 429, false], ['https://example.test/api/graphql', 200, false],
    ['/direct/inbox/', 200, false], ['/graphql/query', 200, true]]) {
    const response = new Response(JSON.stringify({ data: { user: user() } }), { status: code });
    if (redirected) Object.defineProperty(response, 'url', { value: 'https://www.instagram.com/accounts/login/' });
    const win = page(() => Promise.resolve(response)); install('INSTAGRAM', win);
    await win.fetch(url); await response.text();
    await new Promise(resolve => setTimeout(resolve, 5));
    assert.deepEqual(win.__followerTrackerObservedProfiles.profiles, []);
  }
});
test('only safe exact totals and bound profile fields survive a response', async () => {
  const root = { users: [user('42', 0), user('43', '1.2K'), user('44', -1), user('45', 1.5), user('46', true),
    { ...user('47'), username: '../invalid' }, { ...user('48'), id: 'not-an-id' }] };
  const response = new Response(JSON.stringify(root));
  const win = page(() => Promise.resolve(response)); install('INSTAGRAM', win);
  await win.fetch('/api/v1/users/web_profile_info/?username=self'); await response.text();
  await ready(() => win.__followerTrackerObservedProfiles.profiles.length === 1);
  assert.equal(capture('INSTAGRAM', '42', doc(win)).followers, 0);
  assert.equal(win.__followerTrackerObservedProfiles.profiles[0].private_message, undefined);
});
test('cached totals expire and cannot be carried to another SPA route', async () => {
  let wall = 1_000;
  const clock = mock.method(Date, 'now', () => wall);
  try {
    const response = new Response(JSON.stringify({ user: user() }));
    const win = page(() => Promise.resolve(response)); install('INSTAGRAM', win);
    await win.fetch('/api/graphql'); await response.text();
    await ready(() => win.__followerTrackerObservedProfiles.profiles.length === 1);
    wall += 15_001;
    assert.deepEqual(win.__followerTrackerObservedProfiles.profiles, []);
    const other = page(() => Promise.resolve(new Response(JSON.stringify({ user: user() }))));
    install('INSTAGRAM', other); await (await other.fetch('/api/graphql')).text();
    await ready(() => other.__followerTrackerObservedProfiles.profiles.length === 1);
    other.location.href = 'https://www.instagram.com/other/';
    assert.deepEqual(other.__followerTrackerObservedProfiles.profiles, []);
  } finally { clock.mock.restore(); }
});
test('observation is installed once and retains at most sixteen profiles', async () => {
  let calls = 0;
  const response = new Response(JSON.stringify({ users: Array.from({ length: 30 }, (_, i) => user(String(100 + i))) }));
  const win = page(() => { calls++; return Promise.resolve(response); });
  install('INSTAGRAM', win); const installed = win.fetch;
  install('INSTAGRAM', win); assert.equal(win.fetch, installed);
  await win.fetch('/api/graphql'); await response.text();
  await ready(() => win.__followerTrackerObservedProfiles.profiles.length === 16);
  assert.equal(calls, 1);
});
test('oversized JSON and HTML responses are ignored while the original body stays readable', async () => {
  for (const [body, headers] of [['x'.repeat(4 * 1024 * 1024 + 1), {}],
    [JSON.stringify({ user: user() }), { 'Content-Type': 'text/html' }]]) {
    const response = new Response(body, { headers });
    const win = page(() => Promise.resolve(response)); install('INSTAGRAM', win);
    await win.fetch('/api/graphql'); assert.equal(await response.text(), body);
    await new Promise(resolve => setTimeout(resolve, 10));
    assert.deepEqual(win.__followerTrackerObservedProfiles.profiles, []);
  }
});
test('an oversized cloned stream releases its reader even when cancellation waits for the original consumer', async () => {
  const originalSetTimeout = globalThis.setTimeout, originalClearTimeout = globalThis.clearTimeout;
  const timer = {};
  let released = false, cancelled = 0, timersCleared = 0;
  const reader = {
    read: () => Promise.resolve({ done: false, value: new Uint8Array(4 * 1024 * 1024 + 1) }),
    cancel: () => { cancelled++; return new Promise(() => {}); },
    releaseLock: () => { released = true; }
  };
  const response = { ok: true, url: 'https://www.instagram.com/graphql/query', headers: { get: () => null },
    clone: () => ({ body: { getReader: () => reader } }) };
  const start = mock.method(globalThis, 'setTimeout', (callback, delay, ...args) =>
    delay === 5000 ? timer : originalSetTimeout(callback, delay, ...args));
  const stop = mock.method(globalThis, 'clearTimeout', handle => {
    if (handle === timer) timersCleared++;
    else originalClearTimeout(handle);
  });
  try {
    const win = page(() => Promise.resolve(response)); install('INSTAGRAM', win);
    assert.equal(await win.fetch('/api/graphql'), response);
    await ready(() => released);
    assert.equal(cancelled, 1); assert.equal(timersCleared, 1);
    assert.deepEqual(win.__followerTrackerObservedProfiles.profiles, []);
  } finally { start.mock.restore(); stop.mock.restore(); }
});
test('XHR profile responses preserve request arguments and keep other-account counts isolated', () => {
  class Request {
    listeners = [];
    responseType = 'json'; status = 200; responseURL = 'https://www.instagram.com/graphql/query';
    response = { data: { user: user() } };
    open(...args) { this.opened = args; }
    send(...args) { this.sent = args; }
    addEventListener(type, callback) { if (type === 'load') this.listeners.push(callback); }
    finish() { this.listeners.splice(0).forEach(callback => callback()); }
  }
  const win = page(); win.XMLHttpRequest = Request; install('INSTAGRAM', win);
  const xhr = new win.XMLHttpRequest(), body = { get secret() { throw new Error('Never inspect request body'); } };
  xhr.open('POST', '/graphql/query'); xhr.send(body); xhr.finish();
  assert.deepEqual(xhr.opened, ['POST', '/graphql/query']); assert.equal(xhr.sent[0], body);
  assert.equal(capture('INSTAGRAM', '42', doc(win)).followers, 12345);
  assert.equal(capture('INSTAGRAM', '99', doc(win)).error, 'exact_count_missing');
});
