/* Observe exact profile totals already requested by Instagram. Never requests data or reads forms/request headers. */
(function () {
  'use strict';
  const LIMIT = 4 * 1024 * 1024;
  function install(provider, suppliedWindow) {
    const win = suppliedWindow || globalThis;
    if (provider !== 'INSTAGRAM' || win.top && win.top !== win) return false;
    const page = new URL(win.location.href);
    if (page.protocol !== 'https:' || page.username || page.password || page.port && page.port !== '443' ||
        !(page.hostname === 'instagram.com' || page.hostname.endsWith('.instagram.com'))) return false;
    if (win.__followerTrackerObservedProfiles) return true;
    const entries = new Map();
    const now = () => Date.now();
    const trusted = value => {
      try {
        const url = new URL(value, win.location.href);
        return url.protocol === 'https:' && !url.username && !url.password && (!url.port || url.port === '443') &&
          (url.hostname === 'instagram.com' || url.hostname.endsWith('.instagram.com')) &&
          (/^\/(?:api\/graphql|graphql\/query)\/?$/.test(url.pathname) ||
            /^\/api\/v1\/users\/(?:web_profile_info|[0-9]+\/info)\/?$/.test(url.pathname));
      } catch (_) { return false; }
    };
    const exact = value => Number.isSafeInteger(value) && value >= 0;
    const ingest = root => {
      const queue = [root];
      for (let i = 0; i < queue.length && i < 100000; i++) {
        const value = queue[i];
        if (!value || typeof value !== 'object') continue;
        const rawID = value.pk ?? value.id ?? value.pk_id;
        const id = typeof rawID === 'string' ? rawID : Number.isSafeInteger(rawID) ? String(rawID) : '';
        const followers = value.follower_count ?? value.edge_followed_by?.count;
        if (/^[0-9]{1,32}$/.test(id) && typeof value.username === 'string' && /^[A-Za-z0-9._]{1,30}$/.test(value.username) && exact(followers)) {
          const following = value.following_count ?? value.edge_follow?.count;
          const profile = Object.freeze({ id, username: value.username,
            full_name: typeof value.full_name === 'string' && value.full_name.length <= 256 ? value.full_name : value.username,
            follower_count: followers, following_count: exact(following) ? following : null });
          entries.delete(id);
          entries.set(id, { profile, href: win.location.href, at: now() });
          while (entries.size > 16) entries.delete(entries.keys().next().value);
        }
        for (const key in value) {
          if (queue.length >= 100000) break;
          const child = value[key];
          if (Object.hasOwn(value, key) && child && typeof child === 'object') queue.push(child);
        }
      }
    };
    const parse = text => {
      if (typeof text !== 'string' || text.length > LIMIT) return;
      const body = text.replace(/^for\s*\(;;\);\s*/, '');
      try { ingest(JSON.parse(body)); }
      catch (_) {
        // Some GraphQL streams contain one complete JSON object per line. Never evaluate script content.
        for (const line of body.split('\n').slice(0, 64)) {
          try { ingest(JSON.parse(line)); } catch (_) { }
        }
      }
    };
    Object.defineProperty(win, '__followerTrackerObservedProfiles', { value: Object.freeze({
      provider, get profiles() {
        for (const [id, entry] of entries) if (now() - entry.at > 15000 || entry.href !== win.location.href) entries.delete(id);
        return Array.from(entries.values(), entry => entry.profile);
      }
    }) });
    const readClone = async response => {
      if (!response.ok || response.url && !trusted(response.url)) return;
      const type = response.headers.get('Content-Type') || '';
      if (type && !/json|javascript|text\/plain/i.test(type)) return;
      const declared = Number(response.headers.get('Content-Length'));
      if (declared > LIMIT) return;
      const copy = response.clone();
      if (!copy.body?.getReader) return;
      const reader = copy.body.getReader();
      const chunks = []; let size = 0, expired = false;
      const timer = setTimeout(() => { expired = true; reader.cancel().catch(() => {}); }, 5000);
      try {
        while (!expired) {
          const item = await reader.read();
          if (item.done) break;
          size += item.value.byteLength;
          // A cloned branch's cancellation may wait for the site's consumer. Do not retain our reader/timer.
          if (size > LIMIT) { reader.cancel().catch(() => {}); return; }
          chunks.push(item.value);
        }
        if (expired) return;
        const bytes = new Uint8Array(size); let offset = 0;
        for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
        parse(new TextDecoder().decode(bytes));
      } catch (_) { } finally { clearTimeout(timer); reader.releaseLock(); }
    };
    if (typeof win.fetch === 'function') {
      const original = win.fetch;
      win.fetch = function (...args) {
        const requested = typeof args[0] === 'string' || args[0] instanceof URL ? String(args[0]) : args[0]?.url;
        const result = original.apply(this, args);
        if (trusted(requested)) result.then(response => { readClone(response).catch(() => {}); }, () => {});
        return result;
      };
    }
    if (win.XMLHttpRequest?.prototype) {
      const proto = win.XMLHttpRequest.prototype, open = proto.open, send = proto.send, targets = new WeakMap();
      proto.open = function (...args) {
        const result = open.apply(this, args);
        targets.set(this, trusted(args[1]));
        return result;
      };
      proto.send = function (...args) {
        if (targets.get(this)) this.addEventListener('load', () => {
          try {
            if (this.status < 200 || this.status >= 300 || this.responseURL && !trusted(this.responseURL)) return;
            if (this.responseType === 'json') ingest(this.response);
            else if (!this.responseType || this.responseType === 'text') parse(this.responseText);
          } catch (_) { }
        }, { once: true });
        return send.apply(this, args);
      };
    }
    return true;
  }
  globalThis.FollowerTrackerObserver = { install };
  if (typeof module !== 'undefined' && module.exports) module.exports = { install };
})();
