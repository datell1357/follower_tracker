/* Read-only capture of the signed-in profile or an explicitly confirmed Facebook Page. No form fields or passwords are read. */
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
  function jsonRoots(doc, facebookPage = false) {
    let remaining = MAX_JSON_BYTES;
    const roots = [];
    const selector = 'script[type="application/json"],script#__UNIVERSAL_DATA_FOR_REHYDRATION__,script#SIGI_STATE' + (facebookPage ? ',script[data-sjs]' : '');
    for (const node of doc.querySelectorAll(selector)) {
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
  function facebookProfileCounts(doc, includeTextNodes = false) {
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
        const nodes = scope.querySelectorAll('a[href],button,[role="button"]' + (includeTextNodes ? ',span' : ''));
        if (nodes.length > 1000) return null;
        const followers = new Set(), following = new Set();
        let followerSeen = false, invalidFollower = false, invalidFollowing = false;
        for (const node of nodes) {
          if (node.closest('article,[role="article"],[role="feed"]')) continue;
          const text = (node.innerText || node.textContent || "").trim();
          if (text.length > 140) continue;
          if (includeTextNodes && /^(팔로워|followers|팔로잉|following)$/i.test(text)) continue;
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
          captured = { name, scope, followers: followers.values().next().value,
            following: !invalidFollowing && following.size === 1 ? following.values().next().value : null };
          break;
        }
      }
    }
    return captured;
  }

  function facebookPageAddress(value, base) {
    if (typeof value !== 'string' || !value.trim()) return null;
    let url;
    try { url = new URL(value, base); } catch (_) { return null; }
    if (url.protocol !== 'https:' || url.username || url.password || (url.port && url.port !== '443') ||
        !(url.hostname === 'facebook.com' || url.hostname.endsWith('.facebook.com'))) return null;
    const path = url.pathname.replace(/\/$/, '');
    if (path === '/profile.php') {
      const ids = url.searchParams.getAll('id');
      return ids.length === 1 && /^[0-9]+$/.test(ids[0]) ? { id: ids[0], path } : null;
    }
    const segments = path.split('/').filter(Boolean);
    if (segments.length === 1 && /^[0-9]+$/.test(segments[0])) return { id: segments[0], path };
    if (['pages', 'people'].includes(segments[0]) && segments.length === 3 && /^[0-9]+$/.test(segments[2]))
      return { id: segments[2], path };
    const namedID = segments[0] === 'p' && segments.length === 2 && /-([0-9]+)$/.exec(segments[1]);
    if (namedID) return { id: namedID[1], path };
    const blocked = /^(login|login\.php|home\.php|checkpoint|challenge|two_factor|two-factor|twofactor|reel|reels|watch|groups|events|marketplace|search|pages|settings|notifications|friends|bookmarks|gaming|help|privacy|policies|photo\.php|photos|story\.php|stories|permalink\.php)$/i;
    if (segments.length !== 1 || blocked.test(segments[0]) || !/^[A-Za-z0-9.]{1,255}$/.test(segments[0])) return null;
    return { username: segments[0], path: '/' + segments[0].toLowerCase() };
  }

  function facebookPageSocialFollowers(record, doc, current, id) {
    const counts = { exact: [], rounded: false };
    if (!Array.isArray(record.profile_social_context?.content)) return counts;
    const recordAddress = facebookPageAddress(record.url || record.profile_url, doc.location.href);
    for (const item of record.profile_social_context.content.slice(0, 32)) {
      const text = typeof item.text === 'string' ? item.text : item.text?.text;
      if (typeof text !== 'string' || text.length > 140 || typeof item.uri !== 'string') continue;
      let url;
      try { url = new URL(item.uri, doc.location.href); } catch (_) { continue; }
      if (url.username || url.password) continue;
      const path = url.pathname.replace(/\/$/, '');
      const base = path.endsWith('/followers') ? url.origin + path.slice(0, -10) :
        path === '/profile.php' && url.searchParams.get('sk') === 'followers' ? url.href : null;
      const target = base && facebookPageAddress(base, doc.location.href);
      if (!target || target.id !== id && (!current.username || target.path !== current.path) &&
          (!recordAddress?.username || target.path !== recordAddress.path)) continue;
      const match = /^(?:([0-9][0-9,\s\u00a0\u202f]*)\s*(?:명의?\s*)?(?:팔로워|followers)|(?:팔로워|followers)\s*([0-9][0-9,\s\u00a0\u202f]*)(?:명)?)$/i.exec(text.trim());
      const value = match ? exactCount(match[1] ?? match[2]) : null;
      if (value !== null) counts.exact.push(value);
      else counts.rounded ||= /^(?:[0-9]+(?:\.[0-9]+)?\s*(?:[kmb]|천|만|억)\s*(?:명의?\s*)?(?:팔로워|followers)|(?:팔로워|followers)\s*[0-9]+(?:\.[0-9]+)?\s*(?:[kmb]|천|만|억)(?:명)?)$/i.test(text.trim());
    }
    return counts;
  }

  /** The user explicitly confirms this target. Its identity is separate from the authenticated person. */
  function captureFacebookPage(expectedID, expectedPageID, suppliedDocument) {
    if (typeof expectedID !== 'string' || !/^[0-9]+$/.test(expectedID)) return { error: 'identity_missing' };
    const doc = suppliedDocument || document, current = facebookPageAddress(doc.location.href);
    if (!current) return { error: 'page_required' };
    const meta = key => doc.querySelector('meta[property="' + key + '"]')?.getAttribute('content');
    const canonicalText = doc.querySelector('link[rel="canonical"]')?.getAttribute('href') || meta('og:url');
    const canonical = canonicalText ? facebookPageAddress(canonicalText, doc.location.href) : null;
    if (canonicalText && !canonical) return { error: 'page_identity_missing' };
    const native = /^fb:\/\/(page|profile)\/([0-9]+)(?:[/?#]|$)/.exec(meta('al:android:url') || '');
    const pageMeta = meta('fb:page_id'), pageMetaID = /^[0-9]+$/.test(pageMeta || '') ? pageMeta : null;
    const ids = new Set([current.id, canonical?.id, native?.[2], pageMetaID].filter(Boolean));
    if (ids.size > 1 || canonical?.username && current.username && canonical.path !== current.path)
      return { error: 'page_identity_missing' };
    const roots = jsonRoots(doc, true), queue = roots.slice(), records = [], users = [];
    const delegatedPage = record => record.__typename === 'User' && /^[0-9]+$/.test(textID(record.delegate_page?.id) || '');
    for (let i = 0; i < queue.length && i < 100000; i++) {
      const record = queue[i];
      if (!record || typeof record !== 'object') continue;
      if (!Array.isArray(record) && record.__typename === 'User') users.push(record);
      if (!Array.isArray(record) && (record.__typename === 'Page' || record.__isPage === 'Page' || delegatedPage(record))) {
        const id = textID(record.id), address = facebookPageAddress(record.url || record.profile_url, doc.location.href);
        const matches = id && /^[0-9]+$/.test(id) && (ids.has(id) ||
          current.username && (String(record.username || '').toLowerCase() === current.username.toLowerCase() || address?.path === current.path));
        if (matches) { records.push(record); ids.add(id); }
      }
      for (const child of Object.values(record)) if (child && typeof child === 'object') queue.push(child);
    }
    if (ids.size !== 1) return { error: 'page_identity_missing' };
    const id = ids.values().next().value;
    if (id === expectedID) return { error: 'page_required' };
    if (expectedPageID && id !== expectedPageID) return { error: 'page_mismatch' };
    const delegated = records.some(delegatedPage);
    // New Pages expose a public User profile backed by a delegate Page. Other User fragments
    // for that same profile do not repeat the delegate, so the bound profile supplies its proof.
    if (!delegated && users.some(r => textID(r.id) === id))
      return { error: 'page_required' };
    if (delegated) {
      const known = new Set(records);
      for (const user of users) if (textID(user.id) === id && !known.has(user)) { records.push(user); known.add(user); }
    }
    const header = facebookProfileCounts(doc, true);
    const pageLabel = Array.from(doc.querySelectorAll('h1')).slice(0,32).some(heading => {
      for (let scope = heading.parentElement, depth = 0; scope && depth < 5; scope = scope.parentElement, depth++) {
        if (scope.tagName === 'BODY' || scope.tagName === 'HTML' || scope.querySelector('article,[role="article"],[role="feed"]') ||
            scope.querySelectorAll('h1').length !== 1) break;
        const nodes = scope.querySelectorAll('span,div');
        if (nodes.length > 1000) break;
        if (Array.from(nodes).some(node => {
          const text = (node.innerText || node.textContent || '').trim();
          return text.length <= 256 && /^(페이지|Page)\s*[·•]\s*\S/i.test(text) && !node.closest('article,[role="article"],[role="feed"]');
        })) return true;
      }
      return false;
    });
    if (!records.length && !pageMetaID && native?.[1] !== 'page' && !pageLabel) return { error: 'page_required' };
    const social = records.map(r => facebookPageSocialFollowers(r, doc, current, id));
    const exact = new Set([...records.map(r => exactCount(r.followers_count ?? r.follower_count ?? r.followers?.count)),
      ...social.flatMap(s => s.exact)].filter(n => n !== null));
    if (exact.size > 1 || exact.size === 1 && header && !exact.has(header.followers)) return { error: 'exact_count_missing' };
    const followers = exact.size === 1 ? exact.values().next().value : header?.followers;
    const record = records.find(r => r.name);
    const name = record?.name || header?.name;
    if (!name || typeof name !== 'string' || !name.trim() || name.length > 256)
      return { error: 'exact_count_missing' };
    if (exactCount(followers) === null) return { error: social.some(s => s.rounded) ? 'rounded_count_only' : 'exact_count_missing' };
    const captured = result('FACEBOOK', id, record?.username || current.username || id, name,
      'https://www.facebook.com/profile.php?id=' + encodeURIComponent(id), followers, null, 'facebook-webview-page');
    return { ...captured, accountType: 'PAGE', sessionOwnerId: expectedID };
  }
  function capture(provider, expectedID, suppliedDocument) {
    const doc = suppliedDocument || document;
    const roots = jsonRoots(doc, provider === "FACEBOOK");
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
      const counts = facebookProfileCounts(doc, true);
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
  const api = { capture, captureAsync, captureFacebookPage, exactCount };
  globalThis.FollowerTrackerCapture = api;
  if (typeof module !== "undefined" && module.exports) module.exports = api;
})();
