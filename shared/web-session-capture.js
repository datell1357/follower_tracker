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
  function xBootstrap(doc) {
    const live = doc.defaultView?.__INITIAL_STATE__;
    if (live && typeof live === "object") return live;
    // X deletes the global after hydration. Read the original JSON assignment without executing scripts.
    let remaining = MAX_JSON_BYTES;
    for (const node of doc.querySelectorAll('script:not([src])')) {
      const text = node.textContent || "";
      remaining -= text.length;
      if (remaining < 0) break;
      const match = /(?:^|[;\s])window\.__INITIAL_STATE__\s*=\s*(\{)/.exec(text);
      if (!match) continue;
      const start = match.index + match[0].length - 1;
      let depth = 0, quoted = false, escaped = false;
      for (let i = start; i < text.length; i++) {
        const char = text[i];
        if (quoted) {
          if (escaped) escaped = false;
          else if (char === "\\") escaped = true;
          else if (char === '"') quoted = false;
        } else if (char === '"') quoted = true;
        else if (char === "{") depth++;
        else if (char === "}" && --depth === 0) {
          try { return JSON.parse(text.slice(start, i + 1)); } catch (_) { break; }
        }
      }
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
  function facebookProfileCounts(doc) {
    const headings = Array.from(doc.querySelectorAll("h1")).filter(node => node.tagName === "H1" && node.textContent?.trim());
    if (!headings.length || headings.length > 32) return null;
    let captured = null;
    // Facebook's navigation also has an H1. Only one small heading/count header may identify the profile.
    for (const heading of headings) {
      const name = heading.textContent.trim();
      if (name.length > 256) continue;
      for (let scope = heading.parentElement, depth = 0; scope && depth < 5; scope = scope.parentElement, depth++) {
        if (scope.tagName === "BODY" || scope.tagName === "HTML" || scope.querySelector('article,[role="article"],[role="feed"]')) break;
        if (scope.querySelectorAll("h1").length !== 1) break;
        const nodes = scope.querySelectorAll('a[href],button,[role="button"]');
        if (nodes.length > 1000) return null;
        const followers = new Set(), following = new Set();
        let followerSeen = false, invalidFollower = false, invalidFollowing = false;
        for (const node of nodes) {
          if (node.closest('article,[role="article"],[role="feed"]')) continue;
          const text = (node.innerText || node.textContent || "").trim();
          if (text.length > 140) continue;
          const followerLabel = /(?:^(?:팔로워|followers)(?:\s|$)|(?:\s|^)(?:팔로워|followers)$)/i.test(text);
          const followingLabel = /(?:^(?:팔로잉|following)(?:\s|$)|(?:\s|^)(?:팔로잉|following)$)/i.test(text);
          if (!followerLabel && !followingLabel) continue;
          followerSeen ||= followerLabel;
          const match = /^(?:([0-9][0-9,\s\u00a0\u202f]*)\s*(?:명의?\s*)?(팔로워|followers|팔로잉|following)|(팔로워|followers|팔로잉|following)\s*([0-9][0-9,\s\u00a0\u202f]*)(?:명)?)$/i.exec(text);
          const count = match ? exactCount(match[1] ?? match[4]) : null;
          if (count === null) {
            invalidFollower ||= followerLabel;
            invalidFollowing ||= followingLabel;
          } else if (followerLabel) followers.add(count);
          else following.add(count);
        }
        // Do not expand a header with rounded or conflicting values into other parts of the page.
        if (followerSeen) {
          if (captured || invalidFollower || followers.size !== 1) return null;
          captured = { name, followers: followers.values().next().value,
            following: !invalidFollowing && following.size === 1 ? following.values().next().value : null };
          break;
        }
      }
    }
    return captured;
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
      const currentURL = new URL(doc.location.href);
      if (!/^[0-9]+$/.test(expectedID)) return { error: "identity_missing" };
      if (currentURL.protocol !== "https:" || currentURL.username || currentURL.password ||
          (currentURL.port && currentURL.port !== "443") ||
          !(currentURL.hostname === "x.com" || currentURL.hostname.endsWith(".x.com"))) return { error: "own_profile_required" };
      const bootstrap = xBootstrap(doc);
      const xRoots = bootstrap ? [bootstrap, ...roots] : roots;
      const ownRecord = value => textID(value.rest_id ?? value.id_str ?? value.id) === expectedID &&
        /^[A-Za-z0-9_]{1,15}$/.test((value.legacy || value).screen_name || "");
      const record = findRecord(xRoots, value => ownRecord(value) && exactCount((value.legacy || value).followers_count) !== null) ||
        findRecord(xRoots, ownRecord);
      let ownURL;
      if (record) {
        const user = record.legacy || record;
        ownURL = new URL("https://x.com/" + user.screen_name);
        const captured = result(provider, expectedID, user.screen_name, user.name,
          ownURL.href, user.followers_count, user.friends_count, "x-webview-profile");
        if (!captured.error) return captured;
      } else {
        const profileLink = doc.querySelector('[data-testid="AppTabBar_Profile_Link"]');
        if (!profileLink) return { error: "owner_context_missing" };
        ownURL = new URL(profileLink.getAttribute("href"), currentURL);
        if (ownURL.origin !== currentURL.origin || !/^\/[A-Za-z0-9_]{1,15}\/?$/.test(ownURL.pathname))
          return { error: "owner_context_missing" };
      }
      const ownPath = ownURL.pathname.replace(/\/$/, "");
      if (ownPath !== currentURL.pathname.replace(/\/$/, "")) {
        return { error: "own_profile_required", profileURL: ownURL.href };
      }
      let followers = null, following = null;
      for (const link of doc.querySelectorAll("a[href]")) {
        const target = new URL(link.getAttribute("href"), currentURL);
        if (target.origin !== currentURL.origin) continue;
        const path = target.pathname.replace(/\/$/, "");
        if (path === ownPath + "/followers" || path === ownPath + "/verified_followers") followers ??= countInLink(link);
        if (path === ownPath + "/following") following ??= countInLink(link);
      }
      const name = ownURL.pathname.split("/").filter(Boolean)[0];
      return result(provider, expectedID, name, name, ownURL.href, followers, following, "x-webview-dom");
    }
    if (provider === "FACEBOOK") {
      if (typeof expectedID !== "string" || !/^[0-9]+$/.test(expectedID)) return { error: "identity_missing" };
      let currentURL;
      try { currentURL = new URL(doc.location.href); } catch (_) { return { error: "own_profile_required" }; }
      if (currentURL.protocol !== "https:" || currentURL.username || currentURL.password ||
          (currentURL.port && currentURL.port !== "443") ||
          !(currentURL.hostname === "facebook.com" || currentURL.hostname.endsWith(".facebook.com")))
        return { error: "own_profile_required" };
      const profileURL = "https://www.facebook.com/profile.php?id=" + encodeURIComponent(expectedID);
      const isOwner = value => textID(value.id) === expectedID && value.name;
      const followerCount = value => value.followers_count ?? value.follower_count ?? value.followers?.count;
      const record = findRecord(roots, value => isOwner(value) && exactCount(followerCount(value)) !== null);
      if (record) return result(provider, expectedID, record.username || expectedID, record.name, profileURL,
        followerCount(record), record.following_count ?? record.following?.count, "facebook-webview-profile");
      const owner = findRecord(roots, isOwner);
      const profileIDs = currentURL.searchParams.getAll("id");
      const ownProfile = currentURL.pathname === "/profile.php" && profileIDs.length === 1 && profileIDs[0] === expectedID ||
        owner?.username && currentURL.pathname.replace(/\/$/, "") === "/" + encodeURIComponent(owner.username);
      if (!ownProfile) return { error: "own_profile_required", profileURL };
      const counts = facebookProfileCounts(doc);
      if (counts) return result(provider, expectedID, owner?.username || expectedID, owner?.name || counts.name,
        profileURL, counts.followers, counts.following, "facebook-webview-profile");
      return { error: "exact_count_missing" };
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
