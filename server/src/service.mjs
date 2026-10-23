import { createHash, createHmac, randomBytes, timingSafeEqual } from 'node:crypto';
import { ServiceError, ProviderApi, fail, pageId, youtubeTarget } from './provider.mjs';

const random = () => randomBytes(32).toString('base64url');
const hash = value => createHash('sha256').update(value).digest('hex');
const opaque = value => typeof value === 'string' && /^[A-Za-z0-9_-]{43}$/.test(value);
const FLOW_MS = 10 * 60 * 1000;

export class Service {
  constructor(store, config, { provider, now = Date.now } = {}) {
    this.store = store; this.config = config; this.now = now;
    this.provider = provider ?? new ProviderApi(config, fetch, now);
    this.flights = new Map(); this.limits = new Map();
  }
  limit(key, count, windowMs) {
    const now = this.now();
    if (this.limits.size > 10000) for (const [id, bucket] of this.limits) if (bucket.until <= now) this.limits.delete(id);
    const bucket = this.limits.get(key);
    if (!bucket || bucket.until <= now) {
      if (this.limits.size >= 20000) fail('RATE_LIMITED', 429, 60);
      this.limits.set(key, { count: 1, until: now + windowMs }); return;
    }
    if (bucket.count >= count) fail('RATE_LIMITED', 429, Math.ceil((bucket.until - now) / 1000));
    bucket.count++;
  }
  register(ip) {
    this.limit('register:' + ip, 10, 3600000);
    if (this.store.ids('device').length >= (this.config.maxDevices ?? 10000)) fail('SERVICE_UNAVAILABLE', 503);
    const token = random();
    this.store.set('device', hash(token), { createdAt: this.now() });
    return { installationToken: token };
  }
  authorize(authorization) {
    const token = authorization?.startsWith('Bearer ') ? authorization.slice(7) : '';
    if (!opaque(token) || !this.store.get('device', hash(token))) fail('INSTALLATION_REQUIRED', 401);
    const id = hash(token);
    this.limit('device:' + id, 120, 60000);
    return id;
  }
  configured(provider) {
    if (provider === 'youtube' ? !this.config.youtubeKey : !(this.config.metaAppId && this.config.metaSecret))
      fail('SERVICE_NOT_CONFIGURED', 503);
  }
  async cached(key, load, guard = () => true) {
    const now = this.now(), cached = this.store.get('cache', key);
    if (cached && cached.observedAt + 60000 > now) return cached;
    if (this.flights.has(key)) return this.flights.get(key);
    const flight = (async () => {
      const payload = await load();
      if (!guard()) fail('REAUTH_REQUIRED', 401);
      const value = { payload, observedAt: this.now() };
      this.store.set('cache', key, value);
      return value;
    })();
    this.flights.set(key, flight);
    try { return await flight; } finally { this.flights.delete(key); }
  }
  async youtube(address) {
    this.configured('youtube');
    const target = youtubeTarget(address);
    const key = 'youtube:' + (target.id ?? '@' + target.handle);
    return this.cached(key, async () => {
      // One operator project is shared by all installations. Reserve before the network call.
      const day = new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Los_Angeles' }).format(this.now());
      const usage = this.store.get('quota', 'youtube') ?? { day, used: 0, blockedUntil: 0 };
      if (usage.blockedUntil > this.now()) fail('RATE_LIMITED', 429, Math.ceil((usage.blockedUntil - this.now()) / 1000));
      if (usage.day !== day) { usage.day = day; usage.used = 0; }
      if (usage.used >= (this.config.youtubeDailyBudget ?? 9000)) fail('RATE_LIMITED', 429, 3600);
      usage.used++; this.store.set('quota', 'youtube', usage);
      try { return await this.provider.youtube(target); }
      catch (error) {
        if (error instanceof ServiceError && error.code === 'RATE_LIMITED') {
          const latest = this.store.get('quota', 'youtube');
          latest.blockedUntil = Math.max(latest.blockedUntil, this.now() + (error.retryAfterSeconds ?? 900) * 1000);
          this.store.set('quota', 'youtube', latest);
        }
        throw error;
      }
    });
  }
  startFacebook(device, expectedId = null) {
    this.configured('facebook');
    if (expectedId !== null && !pageId(expectedId)) fail('CHECK_REQUIRED');
    this.limit('login:' + device, 10, 3600000);
    this.cancelFacebook(device);
    const id = random();
    this.store.set('flow', id, { device, expectedId, state: random(), status: 'waiting', expiresAt: this.now() + FLOW_MS });
    return { flowId: id, loginUrl: this.config.origin + '/oauth/facebook/start?flow=' + id };
  }
  flow(id, device = null) {
    const flow = opaque(id) && this.store.get('flow', id);
    if (!flow || flow.expiresAt <= this.now() || device && flow.device !== device) fail('LOGIN_EXPIRED', 410);
    return flow;
  }
  browserStart(id) {
    const flow = this.flow(id);
    if (flow.status !== 'waiting') fail('LOGIN_EXPIRED', 410);
    const cookie = random();
    flow.browserHash = hash(cookie); this.store.set('flow', id, flow);
    const url = new URL('https://www.facebook.com/v26.0/dialog/oauth');
    for (const [key, value] of Object.entries({ client_id: this.config.metaAppId,
      redirect_uri: this.config.origin + '/oauth/facebook/callback', state: id + '.' + flow.state,
      response_type: 'code', scope: 'pages_show_list,pages_read_engagement', auth_type: 'rerequest' })) url.searchParams.set(key, value);
    if (this.config.metaLoginConfigId) {
      url.searchParams.delete('scope');
      url.searchParams.set('config_id', this.config.metaLoginConfigId);
      url.searchParams.set('override_default_response_type', 'true');
    }
    return { url: url.href, cookie };
  }
  async callback(params, cookie) {
    const state = params.get('state') ?? '', [id, proof, extra] = state.split('.');
    const flow = this.flow(id);
    if (extra || !opaque(proof) || proof !== flow.state || !opaque(cookie) || hash(cookie) !== flow.browserHash ||
        flow.status !== 'waiting') fail('INVALID_LOGIN', 400);
    // Consume state before any asynchronous exchange; duplicate callbacks cannot redeem it twice.
    flow.status = 'processing'; this.store.set('flow', id, flow);
    if (params.has('error')) { flow.status = 'cancelled'; this.store.set('flow', id, flow); return; }
    const code = params.get('code');
    if (!code || code.length > 4096) fail('INVALID_LOGIN');
    try {
      const result = await this.provider.facebookPages(code);
      const current = this.flow(id);
      if (current.status !== 'processing') fail('LOGIN_EXPIRED', 410);
      flow.pages = result.pages; flow.userId = result.userId; flow.status = 'ready';
    } catch (error) {
      // Logout/cancel while Meta is responding must not restore a pending login.
      const current = this.store.get('flow', id);
      if (!current || current.status !== 'processing' || current.expiresAt <= this.now()) return;
      flow.status = 'failed'; flow.error = error instanceof ServiceError ? error.code : 'OFFLINE';
    }
    this.store.set('flow', id, flow);
  }
  status(device, id) {
    const flow = this.flow(id, device);
    return { status: flow.status, error: flow.error,
      selectedId: flow.status === 'selected' ? flow.selectedId : undefined,
      pages: flow.status === 'ready' ? flow.pages.filter(page => !flow.expectedId || page.id === flow.expectedId)
        .map(({ id, name }) => ({ id, name })) : [] };
  }
  async select(device, id, selectedId) {
    const flow = this.flow(id, device);
    if (flow.status === 'selected' && flow.selectedId === selectedId &&
        this.store.get('grant', device + ':' + selectedId)?.revision === flow.revision) return flow.observation;
    const page = flow.status === 'ready' && flow.pages.find(item => item.id === selectedId);
    if (!page || flow.expectedId && flow.expectedId !== selectedId) fail('PAGE_NOT_ALLOWED', 403);
    const grantKey = device + ':' + page.id;
    if (!this.store.get('grant', grantKey) && this.store.ids('grant').filter(key => key.startsWith(device + ':')).length >= 20)
      fail('TOO_MANY_PAGES', 422);
    flow.status = 'selecting'; this.store.set('flow', id, flow);
    try {
      const payload = await this.provider.page(page.id, page.token);
      const current = this.flow(id, device);
      if (current.status !== 'selecting') fail('LOGIN_EXPIRED', 410);
      const observation = { payload, observedAt: this.now() };
      const revision = random();
      this.store.transaction(() => {
        this.store.set('grant', grantKey, { token: page.token, userId: flow.userId, revision });
        this.store.set('cache', 'facebook:' + grantKey, observation);
        // Only the selected Page token persists. Discard all other Page and user credentials.
        this.store.set('flow', id, { device, expectedId: flow.expectedId, expiresAt: flow.expiresAt,
          status: 'selected', selectedId, userId: flow.userId, revision, observation });
      });
      return observation;
    } catch (error) {
      const current = this.store.get('flow', id);
      if (current?.status === 'selecting') { flow.status = 'ready'; this.store.set('flow', id, flow); }
      throw error;
    }
  }
  async facebook(device, id) {
    if (!pageId(id)) fail('CHECK_REQUIRED');
    const key = device + ':' + id, grant = this.store.get('grant', key);
    if (!grant) fail('REAUTH_REQUIRED', 401);
    if (grant.blockedUntil > this.now()) fail('RATE_LIMITED', 429, Math.ceil((grant.blockedUntil - this.now()) / 1000));
    try {
      return await this.cached('facebook:' + key, () => this.provider.page(id, grant.token),
        () => this.store.get('grant', key)?.revision === grant.revision);
    } catch (error) {
      if (this.store.get('grant', key)?.revision === grant.revision) {
        if (error.code === 'REAUTH_REQUIRED') this.store.remove('grant', key);
        if (error.code === 'RATE_LIMITED') {
          grant.blockedUntil = this.now() + (error.retryAfterSeconds ?? 900) * 1000;
          this.store.set('grant', key, grant);
        }
      }
      throw error;
    }
  }
  cancelFacebook(device, id = null) {
    for (const key of this.store.ids('flow')) {
      if ((!id || id === key) && this.store.get('flow', key)?.device === device) this.store.remove('flow', key);
    }
  }
  disconnect(device, id = null) {
    if (id !== null && !pageId(id)) fail('CHECK_REQUIRED');
    this.cancelFacebook(device);
    for (const key of this.store.ids('grant')) if (key.startsWith(device + ':') && (!id || key === device + ':' + id)) {
      this.store.remove('grant', key); this.store.remove('cache', 'facebook:' + key);
    }
  }
  metaDeletion(signed) {
    const [signature, encoded, extra] = (signed ?? '').split('.');
    if (extra || !signature || !encoded || signed.length > 8192) fail('INVALID_LOGIN');
    const given = Buffer.from(signature, 'base64url');
    const expected = createHmac('sha256', this.config.metaSecret).update(encoded).digest();
    if (given.length !== expected.length || !timingSafeEqual(given, expected)) fail('INVALID_LOGIN');
    let data;
    try { data = JSON.parse(Buffer.from(encoded, 'base64url').toString()); } catch { fail('INVALID_LOGIN'); }
    if (data.algorithm !== 'HMAC-SHA256' || !pageId(data.user_id) || !Number.isFinite(data.issued_at) ||
        Math.abs(this.now() / 1000 - data.issued_at) > 86400) fail('INVALID_LOGIN');
    for (const key of this.store.ids('grant')) if (this.store.get('grant', key)?.userId === data.user_id) {
      this.store.remove('grant', key); this.store.remove('cache', 'facebook:' + key);
    }
    for (const key of this.store.ids('flow')) if (this.store.get('flow', key)?.userId === data.user_id) this.store.remove('flow', key);
    const confirmation = random();
    return { url: this.config.origin + '/deletion/complete?confirmation=' + confirmation, confirmation_code: confirmation };
  }
  sweep() {
    for (const id of this.store.ids('flow')) if (this.store.get('flow', id).expiresAt <= this.now()) this.store.remove('flow', id);
    for (const id of this.store.ids('cache')) if (this.store.get('cache', id).observedAt + 86400000 <= this.now()) this.store.remove('cache', id);
  }
}
