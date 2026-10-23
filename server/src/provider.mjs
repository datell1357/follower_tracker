import { createHmac } from 'node:crypto';

export class ServiceError extends Error {
  constructor(code, status = 400, retryAfterSeconds = null) {
    super(code); this.code = code; this.status = status; this.retryAfterSeconds = retryAfterSeconds;
  }
}
export const fail = (code, status = 400, retry = null) => { throw new ServiceError(code, status, retry); };
export const pageId = value => typeof value === 'string' && /^[0-9]{1,32}$/.test(value);
export const channelId = value => typeof value === 'string' && /^UC[A-Za-z0-9_-]{22}$/.test(value);
const validCount = value => /^(0|[1-9][0-9]*)$/.test(String(value)) && Number.isSafeInteger(Number(value));
const validName = value => typeof value === 'string' && value.trim().length > 0 && value.length <= 256;

export function youtubeTarget(address) {
  if (typeof address !== 'string' || address.length > 512) fail('CHECK_REQUIRED');
  let url;
  try { url = new URL(address.startsWith('https://') ? address : 'https://' + address); } catch { fail('CHECK_REQUIRED'); }
  if (url.protocol !== 'https:' || url.username || url.password || url.port ||
      !['youtube.com', 'www.youtube.com', 'm.youtube.com'].includes(url.hostname)) fail('CHECK_REQUIRED');
  const path = url.pathname.replace(/\/$/, '');
  const id = path.match(/^\/channel\/(UC[A-Za-z0-9_-]{22})$/)?.[1];
  if (id) return { id };
  let handle;
  try { handle = decodeURIComponent(path.slice(2)); } catch { fail('CHECK_REQUIRED'); }
  if (!path.startsWith('/@') || !handle || handle.length > 100 || /[\s/?#\\\u0000-\u001f]/u.test(handle)) fail('CHECK_REQUIRED');
  return { handle };
}
export function sanitizeYouTube(root, target) {
  if (!Array.isArray(root?.items) || root.items.length !== 1) fail('CHECK_REQUIRED', 422);
  const item = root.items[0], stats = item.statistics;
  if (!channelId(item.id) || target.id && item.id !== target.id || !validName(item.snippet?.title) ||
      stats?.hiddenSubscriberCount !== false || !validCount(stats.subscriberCount)) fail('FORMAT_CHANGED', 502);
  return { items: [{ id: item.id, snippet: { title: item.snippet.title }, statistics: {
    subscriberCount: String(stats.subscriberCount), hiddenSubscriberCount: false } }] };
}
export function sanitizePage(root, expectedId) {
  if (!pageId(root?.id) || root.id !== expectedId || !validName(root.name) || !validCount(root.followers_count))
    fail('FORMAT_CHANGED', 502);
  return { id: root.id, name: root.name, followers_count: Number(root.followers_count) };
}

export class ProviderApi {
  constructor(config, fetcher = fetch, now = Date.now) { this.config = config; this.fetcher = fetcher; this.now = now; }
  async read(url, token, limit = 65536) {
    let response;
    try { response = await this.fetcher(url, { redirect: 'error', signal: AbortSignal.timeout(20000),
      headers: { Accept: 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) } }); }
    catch { fail('OFFLINE', 503); }
    const headerRetry = response.headers.get('retry-after');
    const seconds = Number(headerRetry);
    const date = Date.parse(headerRetry);
    const retry = Math.max(1, Number.isFinite(seconds) && seconds > 0 ? Math.ceil(seconds) :
      Number.isFinite(date) ? Math.ceil((date - this.now()) / 1000) : 900);
    if (response.status === 429) { await response.body?.cancel(); fail('RATE_LIMITED', 429, retry); }
    if (response.status >= 500) { await response.body?.cancel(); fail('OFFLINE', 503); }
    let bytes = 0, chunks = [];
    try {
      for await (const chunk of response.body ?? []) {
        bytes += chunk.length;
        if (bytes > limit) fail('FORMAT_CHANGED', 502);
        chunks.push(chunk);
      }
    } catch (error) { if (error instanceof ServiceError) throw error; fail('OFFLINE', 503); }
    let root;
    try { root = JSON.parse(Buffer.concat(chunks).toString()); } catch { fail('FORMAT_CHANGED', 502); }
    const error = root?.error;
    if ([101, 191].includes(error?.code) || error?.errors?.some(item =>
      ['keyInvalid', 'accessNotConfigured', 'ipRefererBlocked', 'apiKeyNotValid'].includes(item.reason)))
      fail('SERVICE_NOT_CONFIGURED', 503);
    if ([102, 190].includes(error?.code) || response.status === 401) fail('REAUTH_REQUIRED', 401);
    if ([4, 17, 32, 613].includes(error?.code)) fail('RATE_LIMITED', 429, retry);
    if (error?.is_transient) fail('OFFLINE', 503);
    if ([10, 200].includes(error?.code)) fail('PERMISSION_REQUIRED', 403);
    if (error?.errors?.some(item => ['quotaExceeded', 'dailyLimitExceeded'].includes(item.reason)))
      fail('RATE_LIMITED', 429, Math.max(retry, 86400));
    if (!response.ok || error) fail('CHECK_REQUIRED', 403);
    return root;
  }
  graph(path, params = {}, token) {
    const url = new URL('https://graph.facebook.com/v26.0/' + path);
    for (const [key, value] of Object.entries(params)) url.searchParams.set(key, value);
    if (token) url.searchParams.set('appsecret_proof', createHmac('sha256', this.config.metaSecret).update(token).digest('hex'));
    return this.read(url, token, path === 'me/accounts' ? 1024 * 1024 : 65536);
  }
  async youtube(target) {
    const url = new URL('https://www.googleapis.com/youtube/v3/channels');
    url.searchParams.set('key', this.config.youtubeKey);
    url.searchParams.set('part', 'snippet,statistics');
    url.searchParams.set('fields', 'items(id,snippet/title,statistics(subscriberCount,hiddenSubscriberCount))');
    url.searchParams.set(target.id ? 'id' : 'forHandle', target.id ?? target.handle);
    return sanitizeYouTube(await this.read(url), target);
  }
  async facebookPages(code) {
    // The app secret and both user tokens stay on the server. No SDK/embedded browser is needed.
    const short = await this.graph('oauth/access_token', { client_id: this.config.metaAppId,
      client_secret: this.config.metaSecret, redirect_uri: this.config.origin + '/oauth/facebook/callback', code });
    if (typeof short.access_token !== 'string') fail('REAUTH_REQUIRED', 401);
    const long = await this.graph('oauth/access_token', { client_id: this.config.metaAppId,
      client_secret: this.config.metaSecret, grant_type: 'fb_exchange_token', fb_exchange_token: short.access_token });
    if (typeof long.access_token !== 'string') fail('REAUTH_REQUIRED', 401);
    const permissions = await this.graph('me/permissions', {}, long.access_token);
    if (!['pages_show_list', 'pages_read_engagement'].every(name => permissions.data?.some(
      item => item.permission === name && item.status === 'granted'))) fail('PERMISSION_REQUIRED', 403);
    const identity = await this.graph('me', { fields: 'id' }, long.access_token);
    if (!pageId(identity.id)) fail('FORMAT_CHANGED', 502);
    const pages = []; let after;
    for (let batch = 0; batch < 10; batch++) {
      const result = await this.graph('me/accounts', { fields: 'id,name,access_token', limit: '100', ...(after ? { after } : {}) }, long.access_token);
      if (!Array.isArray(result.data)) fail('FORMAT_CHANGED', 502);
      for (const item of result.data) {
        if (!pageId(item.id) || !validName(item.name) || typeof item.access_token !== 'string' || item.access_token.length > 8192)
          fail('FORMAT_CHANGED', 502);
        if (!pages.some(page => page.id === item.id)) pages.push({ id: item.id, name: item.name, token: item.access_token });
      }
      if (!result.paging?.next) return { pages, userId: identity.id };
      const cursor = result.paging?.cursors?.after;
      if (typeof cursor !== 'string' || !cursor || cursor.length > 4096 || cursor === after) fail('FORMAT_CHANGED', 502);
      after = cursor; // Never follow an upstream paging URL that can carry credentials or another host.
    }
    fail('TOO_MANY_PAGES', 422);
  }
  async page(id, token) { return sanitizePage(await this.graph(id, { fields: 'id,name,followers_count' }, token), id); }
}
