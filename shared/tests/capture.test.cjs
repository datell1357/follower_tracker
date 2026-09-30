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
