const { test } = require("node:test");
const assert = require("node:assert/strict");
const { capture, exactCount } = require("../web-session-capture.js");

function documentWith(data) {
  return { location: { href: "https://www.tiktok.com/@self" },
    querySelectorAll: () => [{ textContent: JSON.stringify(data) }], querySelector: () => null };
}

test("shortened and unsafe counts are never exact", () => {
  assert.equal(exactCount("12,345"), 12345);
  assert.equal(exactCount(0), 0);
  for (const value of ["1.2K", "1.2만", -1, null, true, 1.5, Number.MAX_SAFE_INTEGER + 1]) assert.equal(exactCount(value), null);
});
test("TikTok capture requires matching signed-in identity and profile", () => {
  const data = { __DEFAULT_SCOPE__: {
    "webapp.app-context": { user: { id: "42" } },
    "webapp.user-detail": { userInfo: { user: { id: "42", uniqueId: "self", nickname: "Self" }, stats: { followerCount: 0, followingCount: 4 } } }
  }};
  assert.equal(capture("TIKTOK", null, documentWith(data)).followers, 0);
  assert.equal(capture("TIKTOK", "other", documentWith(data)).error, "own_profile_required");
  data.__DEFAULT_SCOPE__["webapp.app-context"].user.id = "other";
  assert.equal(capture("TIKTOK", null, documentWith(data)).error, "own_profile_required");
});
test("no logged-in TikTok context does not connect an arbitrary public profile", () => {
  const data = { __DEFAULT_SCOPE__: { "webapp.user-detail": { userInfo: {
    user: { id: "42", uniqueId: "someone" }, stats: { followerCount: 999, followingCount: 0 }
  } } } };
  assert.equal(capture("TIKTOK", null, documentWith(data)).error, "identity_missing");
});
test("Facebook record is tied to owner ID and does not use friend count", () => {
  const doc = documentWith({ records: [{ id: "other", name: "Other", followers_count: 999 }, { id: "42", name: "Self", friends: { count: 800 }, followers: { count: 5 } }] });
  const result = capture("FACEBOOK", "42", doc);
  assert.equal(result.followers, 5);
  assert.equal(result.following, null);
  assert.equal(capture("FACEBOOK", null, doc).error, "identity_missing");
});
test("missing exact count produces an error rather than zero", () => {
  const result = capture("FACEBOOK", "42", documentWith({ id: "42", name: "Self", follower_count: "1.2K" }));
  assert.equal(result.error, "exact_count_missing");
});

function instagramDocument(data, path = "/self/") {
  const doc = documentWith(data);
  doc.location.href = "https://www.instagram.com" + path;
  doc.querySelectorAll = selector => selector === "a[href]" ? [] : [{ textContent: JSON.stringify(data) }];
  return doc;
}
function countLink(href, title, text) {
  return { getAttribute: name => ({ href, title })[name] ?? null, textContent: text, querySelectorAll: () => [] };
}
test("Instagram own profile exact links connect without an API request", async () => {
  const { captureAsync } = require("../web-session-capture.js");
  const doc = instagramDocument({ viewer: { id: "42", username: "self" } });
  const scripts = doc.querySelectorAll;
  doc.querySelectorAll = selector => selector === "a[href]" ? [
    countLink("/other/followers/", "999", "999 followers"),
    countLink("https://example.test/self/followers/", "888", "888 followers"),
    countLink("/self/followers/", "12,345", "12.3K followers"),
    countLink("/self/following/", null, "0 following")
  ] : scripts(selector);
  const result = await captureAsync("INSTAGRAM", "42", doc, () => { throw new Error("No request expected"); });
  assert.equal(result.followers, 12345);
  assert.equal(result.following, 0);
  assert.equal(result.source, "instagram-webview-dom");
  assert.equal(capture("INSTAGRAM", "99", doc).error, "exact_count_missing");
  doc.location.href = "https://www.instagram.com/other/";
  assert.equal(capture("INSTAGRAM", "42", doc).error, "own_profile_required");
});
test("Instagram rounded link text never becomes an exact count", () => {
  const doc = instagramDocument({ viewer: { id: "42", username: "self" } });
  const scripts = doc.querySelectorAll;
  doc.querySelectorAll = selector => selector === "a[href]" ? [countLink("/self/followers/", null, "12.3K followers")] : scripts(selector);
  assert.equal(capture("INSTAGRAM", "42", doc).error, "exact_count_missing");
});
test("Instagram embedded counts belong to the session identity, including exact zero", () => {
  const doc = instagramDocument({ users: [{ id: "99", username: "other", follower_count: 900 },
    { id: "42", username: "self" }, { id: "42", username: "self", edge_followed_by: { count: 0 }, edge_follow: { count: 8 } }] });
  assert.equal(capture("INSTAGRAM", "42", doc).followers, 0);
  assert.equal(capture("INSTAGRAM", null, doc).error, "identity_missing");
  assert.equal(capture("INSTAGRAM", "123", doc).error, "exact_count_missing");
});
test("Instagram owner context finds the profile without reading any input values", () => {
  const result = capture("INSTAGRAM", "42", instagramDocument({ viewer: { id: "42", username: "self" } }, "/"));
  assert.equal(result.error, "own_profile_required");
  assert.equal(result.profileURL, "https://www.instagram.com/self/");
});
test("browser session read uses credentials on the current official origin", async () => {
  const { captureAsync } = require("../web-session-capture.js");
  let calls = 0;
  const result = await captureAsync("INSTAGRAM", "42", instagramDocument({}), async (url, options) => {
    calls++;
    assert.equal(url, "https://www.instagram.com/api/v1/users/42/info/");
    assert.equal(options.credentials, "include");
    assert.equal(options.redirect, "error");
    assert.equal(options.method, "GET");
    return new Response(JSON.stringify({ status: "ok", user: { pk: "42", username: "self", follower_count: 3, following_count: 4 } }));
  });
  assert.equal(calls, 1);
  assert.equal(result.followers, 3);
  assert.equal(result.source, "instagram-webview-session");
});
test("429 is returned once with Retry-After and never retried through another path", async () => {
  const { captureAsync } = require("../web-session-capture.js");
  let calls = 0;
  const result = await captureAsync("INSTAGRAM", "42", instagramDocument({}), async () => {
    calls++;
    return new Response("", { status: 429, headers: { "Retry-After": "120" } });
  });
  assert.deepEqual(result, { error: "http", status: 429, retryAfterSeconds: 120 });
  assert.equal(calls, 1);
});
test("foreign hosts and missing owner identity do not send browser requests", async () => {
  const { captureAsync } = require("../web-session-capture.js");
  let calls = 0;
  const fetch = async () => { calls++; throw new Error("unexpected transport"); };
  const doc = instagramDocument({});
  doc.location.href = "https://instagram.com.example.test/";
  assert.equal((await captureAsync("INSTAGRAM", "42", doc, fetch)).error, "own_profile_required");
  assert.equal((await captureAsync("INSTAGRAM", null, instagramDocument({}), fetch)).error, "identity_missing");
  assert.equal(calls, 0);
});
test("wrong account, challenge, and rounded counts never produce a successful capture", async () => {
  const { captureAsync } = require("../web-session-capture.js");
  for (const [body, error] of [
    [{ user: { pk: "99", username: "other", follower_count: 100 } }, "own_profile_required"],
    [{ status: "fail", message: "challenge_required" }, "check_required"],
    [{ status: "fail", message: "Please wait a few minutes" }, "rate_limited"],
    [{ user: { pk: "42", username: "self", follower_count: "1.2K" } }, "exact_count_missing"]
  ]) assert.equal((await captureAsync("INSTAGRAM", "42", instagramDocument({}), async () => new Response(JSON.stringify(body)))).error, error);
});
test("anonymous Reddit session is not an authenticated account", async () => {
  const { captureAsync } = require("../web-session-capture.js");
  const doc = documentWith({}); doc.location.href = "https://www.reddit.com/";
  assert.equal((await captureAsync("REDDIT", null, doc, async () => new Response("{}"))).error, "reauth_required");
  const response = await captureAsync("REDDIT", null, doc, async () => new Response(JSON.stringify({ data: { id: "42", name: "self", subreddit: { subscribers: 0 } } })));
  assert.equal(response.followers, 0);
  assert.equal(response.stableId, "42");
});
test("oversized browser response is discarded rather than partially parsed", async () => {
  const { captureAsync } = require("../web-session-capture.js");
  const result = await captureAsync("INSTAGRAM", "42", instagramDocument({}), async () => new Response("x".repeat(4 * 1024 * 1024 + 1)));
  assert.equal(result.error, "format_changed");
});
