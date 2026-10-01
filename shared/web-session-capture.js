/* Read-only capture of the signed-in user's profile. No form fields or passwords are read. */
(function () {
  "use strict";
  const MAX_JSON_BYTES = 4 * 1024 * 1024;

  function exactCount(value) {
    if (typeof value === "number") return Number.isSafeInteger(value) && value >= 0 ? value : null;
    if (typeof value !== "string" || !/^[0-9][0-9,\s\u00a0\u202f]*$/.test(value.trim())) return null;
    const parsed = Number(value.replace(/[,\s\u00a0\u202f]/g, ""));
    return Number.isSafeInteger(parsed) && parsed >= 0 ? parsed : null;
  }
  function textID(value) {
    if (typeof value === "string" && value.trim()) return value;
    if (typeof value === "number" && Number.isSafeInteger(value)) return String(value);
    return null;
  }
  function jsonRoots(doc) {
    let remaining = MAX_JSON_BYTES;
    const roots = [];
    for (const node of doc.querySelectorAll('script[type="application/json"],script#__UNIVERSAL_DATA_FOR_REHYDRATION__,script#SIGI_STATE')) {
      const text = node.textContent || "";
      remaining -= text.length;
      if (remaining < 0) break;
      try { roots.push(JSON.parse(text)); } catch (_) { /* Non-JSON scripts are ignored. */ }
    }
    return roots;
  }
  function findRecord(roots, predicate) {
    const queue = roots.slice();
    let index = 0;
    while (index < queue.length && index < 100000) {
      const value = queue[index++];
      if (!value || typeof value !== "object") continue;
      if (!Array.isArray(value) && predicate(value)) return value;
      for (const child of Object.values(value)) if (child && typeof child === "object") queue.push(child);
    }
    return null;
  }
  function result(provider, id, username, displayName, profileURL, followers, following, source) {
    if (!id || !username || exactCount(followers) === null) return { error: "exact_count_missing" };
    return { provider, stableId: id, username, displayName: displayName || username, profileURL,
      followers: exactCount(followers), following: exactCount(following), source, precision: "EXACT" };
  }
  function countInLink(link) {
    const candidates = [link.getAttribute("title")];
    for (const span of link.querySelectorAll("span")) candidates.push(span.getAttribute("title"), span.textContent);
    candidates.push((link.textContent || "").replace(/\s*(followers|following|팔로워|팔로잉)\s*$/i, ""));
    for (const candidate of candidates) {
      const count = exactCount(candidate);
      if (count !== null) return count;
    }
    return null;
  }
  function capture(provider, expectedID, suppliedDocument) {
    const doc = suppliedDocument || document;
    const roots = jsonRoots(doc);
    if (provider === "INSTAGRAM") {
      if (!expectedID || !/^[0-9]+$/.test(expectedID)) return { error: "identity_missing" };
      const isOwner = value => textID(value.pk ?? value.id) === expectedID && value.username;
      const owner = findRecord(roots, value => isOwner(value) && exactCount(value.follower_count ?? value.edge_followed_by?.count) !== null) || findRecord(roots, isOwner);
      if (owner) {
        const profileURL = "https://www.instagram.com/" + encodeURIComponent(owner.username) + "/";
        const captured = result(provider, expectedID, owner.username, owner.full_name, profileURL,
          owner.follower_count ?? owner.edge_followed_by?.count,
          owner.following_count ?? owner.edge_follow?.count, "instagram-webview-profile");
        if (!captured.error) return captured;
        const currentURL = new URL(doc.location.href), ownURL = new URL(profileURL);
        if (currentURL.pathname.replace(/\/$/, "") !== ownURL.pathname.replace(/\/$/, ""))
          return { error: "own_profile_required", profileURL };
        let followers = null, following = null;
        for (const link of doc.querySelectorAll("a[href]")) {
          const target = new URL(link.getAttribute("href"), currentURL);
          if (target.origin !== currentURL.origin) continue;
          const path = target.pathname.replace(/\/$/, "");
          const ownPath = ownURL.pathname.replace(/\/$/, "");
          if (path === ownPath + "/followers") followers ??= countInLink(link);
          if (path === ownPath + "/following") following ??= countInLink(link);
        }
        if (followers !== null) return result(provider, expectedID, owner.username, owner.full_name,
          profileURL, followers, following, "instagram-webview-dom");
      }
      return { error: "exact_count_missing" };
    }
    if (provider === "TIKTOK") {
      for (const root of roots) {
        const scope = root.__DEFAULT_SCOPE__;
        if (!scope) continue;
        const context = scope["webapp.app-context"] || {};
        const signedIn = context.user || context.userInfo?.user;
        const ownerID = textID(signedIn?.id || signedIn?.uid);
        const info = scope["webapp.user-detail"]?.userInfo;
        if (!ownerID) continue;
        if (!info?.user || !info.stats) {
          if (signedIn.uniqueId) return { error: "own_profile_required", profileURL: "https://www.tiktok.com/@" + encodeURIComponent(signedIn.uniqueId) };
          continue;
        }
        const id = textID(info.user.id);
        if (id !== ownerID || (expectedID && id !== expectedID)) return { error: "own_profile_required" };
        return result(provider, id, info.user.uniqueId, info.user.nickname,
          "https://www.tiktok.com/@" + encodeURIComponent(info.user.uniqueId),
          info.stats.followerCount, info.stats.followingCount, "tiktok-webview-profile");
      }
      return { error: "identity_missing" };
    }
    if (!expectedID) return { error: "identity_missing" };
    if (provider === "X") {
      const record = findRecord(roots, value => textID(value.rest_id) === expectedID && value.legacy?.screen_name);
      if (record) {
        const user = record.legacy;
        return result(provider, expectedID, user.screen_name, user.name,
          "https://x.com/" + encodeURIComponent(user.screen_name), user.followers_count, user.friends_count, "x-webview-profile");
      }
      const profileLink = doc.querySelector('[data-testid="AppTabBar_Profile_Link"]');
      if (!profileLink) return { error: "identity_missing" };
      const ownURL = new URL(profileLink.getAttribute("href"), doc.location.href);
      const currentURL = new URL(doc.location.href);
      if (ownURL.pathname.replace(/\/$/, "") !== currentURL.pathname.replace(/\/$/, "")) {
        return { error: "own_profile_required", profileURL: ownURL.href };
      }
      let followers = null, following = null;
      for (const link of doc.querySelectorAll("a[href]")) {
        const path = new URL(link.getAttribute("href"), ownURL).pathname;
        if (path === ownURL.pathname + "/followers" || path === ownURL.pathname + "/verified_followers") followers ??= countInLink(link);
        if (path === ownURL.pathname + "/following") following ??= countInLink(link);
      }
      const name = ownURL.pathname.split("/").filter(Boolean)[0];
      return result(provider, expectedID, name, name, ownURL.href, followers, following, "x-webview-dom");
    }
    if (provider === "FACEBOOK") {
      const record = findRecord(roots, value => textID(value.id) === expectedID && value.name &&
        (value.followers_count !== undefined || value.follower_count !== undefined || value.followers?.count !== undefined));
      if (!record) return { error: "exact_count_missing" };
      return result(provider, expectedID, record.username || expectedID, record.name,
        "https://www.facebook.com/profile.php?id=" + encodeURIComponent(expectedID),
        record.followers_count ?? record.follower_count ?? record.followers?.count,
        record.following_count ?? record.following?.count, "facebook-webview-profile");
    }
    return { error: "native_collection_required" };
  }
  async function captureAsync(provider, expectedID, suppliedDocument, suppliedFetch) {
    const doc = suppliedDocument || document;
    if (!['INSTAGRAM', 'REDDIT'].includes(provider)) return capture(provider, expectedID, doc);
    const page = new URL(doc.location.href);
    const domain = provider === 'INSTAGRAM' ? 'instagram.com' : 'reddit.com';
    if (page.protocol !== 'https:' || page.username || page.password || (page.port && page.port !== '443') ||
        !(page.hostname === domain || page.hostname.endsWith('.' + domain))) return { error: 'own_profile_required' };
    if (provider === 'INSTAGRAM') {
      const local = capture(provider, expectedID, doc);
      if (!local.error || local.profileURL || local.error === 'identity_missing') return local;
    }
    const path = provider === 'INSTAGRAM' ? '/api/v1/users/' + expectedID + '/info/' : '/api/me.json';
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 15000);
    try {
      const headers = { Accept: 'application/json' };
      if (provider === 'INSTAGRAM') headers['X-IG-App-ID'] = '936619743392459';
      const response = await (suppliedFetch || fetch)(page.origin + path,
        { method: 'GET', credentials: 'include', redirect: 'error', headers, signal: controller.signal });
      if (!response.ok) {
        const retry = response.headers.get('Retry-After');
        return { error: 'http', status: response.status,
          retryAfterSeconds: /^[0-9]+$/.test(retry || '') ? Math.min(86400, Math.max(60, Number(retry))) : null };
      }
      const declared = Number(response.headers.get('Content-Length'));
      if (declared > MAX_JSON_BYTES) return { error: 'format_changed' };
      const reader = response.body.getReader();
      const chunks = []; let size = 0;
      while (true) {
        const chunk = await reader.read();
        if (chunk.done) break;
        size += chunk.value.byteLength;
        if (size > MAX_JSON_BYTES) { await reader.cancel(); return { error: 'format_changed' }; }
        chunks.push(chunk.value);
      }
      const bytes = new Uint8Array(size); let offset = 0;
      for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
      const root = JSON.parse(new TextDecoder().decode(bytes));
      if (provider === 'INSTAGRAM') {
        if (root.status === 'fail') {
          const message = String(root.message || '');
          return { error: /login/i.test(message) ? 'reauth_required' : root.challenge || /challenge/i.test(message) ? 'check_required' :
            /wait|rate/i.test(message) ? 'rate_limited' : 'format_changed' };
        }
        const user = root.user;
        if (!user || textID(user.pk ?? user.id) !== expectedID) return { error: 'own_profile_required' };
        return result(provider, expectedID, user.username, user.full_name,
          page.origin + '/' + encodeURIComponent(user.username) + '/', user.follower_count, user.following_count, 'instagram-webview-session');
      }
      const user = root.data || root, id = textID(user.id);
      if (!id || !user.name) return { error: 'reauth_required' };
      if (expectedID && id !== expectedID) return { error: 'own_profile_required' };
      return result(provider, id, user.name, user.subreddit?.title,
        page.origin + '/user/' + encodeURIComponent(user.name) + '/', user.subreddit?.subscribers, null, 'reddit-webview-session');
    } catch (error) {
      return { error: error instanceof SyntaxError ? 'format_changed' : 'offline' };
    } finally { clearTimeout(timer); }
  }
  const api = { capture, captureAsync, exactCount };
  globalThis.FollowerTrackerCapture = api;
  if (typeof module !== "undefined" && module.exports) module.exports = api;
})();
