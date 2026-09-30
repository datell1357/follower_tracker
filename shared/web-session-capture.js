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
    if (provider === "TIKTOK") {
      for (const root of roots) {
        const scope = root.__DEFAULT_SCOPE__;
        if (!scope) continue;
        const context = scope["webapp.app-context"] || {};
        const signedIn = context.user || context.userInfo?.user;
        const ownerID = textID(signedIn?.id || signedIn?.uid);
        const info = scope["webapp.user-detail"]?.userInfo;
        if (!ownerID || !info?.user || !info.stats) continue;
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
  const api = { capture, exactCount };
  globalThis.FollowerTrackerCapture = api;
  if (typeof module !== "undefined" && module.exports) module.exports = api;
})();
