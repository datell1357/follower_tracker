const { test } = require("node:test");
const assert = require("node:assert/strict");
const { capture, captureFacebookPage, exactCount } = require("../web-session-capture.js");

const studioID = 'UC' + 'a'.repeat(22);
function studioDocument(values = ['12,345'], options = {}) {
  const card = { innerText: options.label || '', querySelectorAll: selector => selector.startsWith('#subscriber-count') ? values.map(textContent => ({ textContent })) : [] };
  const root = { querySelectorAll: selector => selector === 'ytcp-app' ? [{}] : selector.startsWith('ytcp-channel-analytics,') ? [card] :
    selector.startsWith('ytcp-navigation-drawer ') ? [{ textContent: 'Fixture Channel' }] : [] };
  const doc = { location: { href: options.url || 'https://studio.youtube.com/channel/' + studioID },
    querySelectorAll: selector => options.shadow ? selector === '*' ? [{ shadowRoot: root }] : [] : root.querySelectorAll(selector) };
  if (options.noApp) root.querySelectorAll = () => [];
  return doc;
}
test('YouTube captures only an owned Studio total, including zero and open shadow roots', () => {
  for (const shadow of [false, true]) for (const [values, expected] of [[['0'], 0], [['12,345'], 12345]]) {
    const result = capture('YOUTUBE', null, studioDocument(values, { shadow }));
    assert.equal(result.followers, expected); assert.equal(result.stableId, studioID);
    assert.equal(result.studioChannelId, studioID); assert.equal(result.following, null);
    assert.equal(result.source, 'youtube-studio-dom'); assert.equal(result.precision, 'EXACT');
  }
});
test('YouTube public pages, foreign hosts, another channel, and non-Studio documents never connect', () => {
  for (const url of ['https://www.youtube.com/channel/' + studioID, 'https://studio.youtube.com.example.test/channel/' + studioID,
    'http://studio.youtube.com/channel/' + studioID, 'https://private@studio.youtube.com/channel/' + studioID,
    'https://studio.youtube.com/channel/' + studioID + '/videos', 'https://studio.youtube.com/channel/not-a-channel'])
    assert.ok(capture('YOUTUBE', null, studioDocument(['12345'], { url })).error);
  assert.equal(capture('YOUTUBE', 'UC' + 'b'.repeat(22), studioDocument()).error, 'check_required');
  assert.ok(capture('YOUTUBE', null, studioDocument(['12345'], { noApp: true })).error);
});
test('YouTube rounded, conflicting, missing and subscriber-gain metrics cannot invent an exact total', () => {
  for (const values of [['1.2K'], ['1.23만'], ['-1'], ['1.5'], ['12,345', '12,346'], []])
    assert.equal(capture('YOUTUBE', null, studioDocument(values)).error, 'exact_count_missing');
  assert.equal(capture('YOUTUBE', null, studioDocument([], { label: 'Subscribers gained\n12345' })).error, 'exact_count_missing');
  assert.equal(capture('YOUTUBE', null, studioDocument([], { label: '현재 구독자 수\n12,345\n지난 28일' })).followers, 12345);
});

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
test("a connected TikTok profile keeps exact counts when the signed-in context is omitted", () => {
  for (const followerCount of [0, 7, "12,345"]) {
    const data = { __DEFAULT_SCOPE__: {
      "webapp.app-context": { user: null },
      "webapp.user-detail": { statusCode: 0, userInfo: {
        user: { id: "42", uniqueId: "self", nickname: "Self" }, stats: { followerCount, followingCount: 4 }
      } }
    } };
    const result = capture("TIKTOK", "42", documentWith(data));
    assert.equal(result.stableId, "42");
    assert.equal(result.followers, exactCount(followerCount));
    assert.equal(result.following, 4);
    assert.equal(result.precision, "EXACT");
  }
});
test("TikTok stored identity cannot connect public profiles or override mismatched owners and rounded counts", () => {
  const data = { __DEFAULT_SCOPE__: { "webapp.user-detail": { userInfo: {
    user: { id: "42", uniqueId: "self" }, stats: { followerCount: 7, followingCount: 4 }
  } } } };
  assert.equal(capture("TIKTOK", null, documentWith(data)).error, "identity_missing");
  assert.equal(capture("TIKTOK", "99", documentWith(data)).error, "own_profile_required");
  data.__DEFAULT_SCOPE__["webapp.app-context"] = { user: { id: "99" } };
  assert.equal(capture("TIKTOK", "42", documentWith(data)).error, "own_profile_required");
  delete data.__DEFAULT_SCOPE__["webapp.app-context"];
  data.__DEFAULT_SCOPE__["webapp.user-detail"].userInfo.stats.followerCount = "1.2K";
  assert.equal(capture("TIKTOK", "42", documentWith(data)).error, "exact_count_missing");
});
function facebookDocument(data, path = "/profile.php?id=42", links = []) {
  return { location: { href: "https://www.facebook.com" + path },
    querySelectorAll: selector => selector === "a[href]" ? links : [{ textContent: JSON.stringify(data) }],
    querySelector: () => null };
}
function facebookDOMDocument(texts, path = "/profile.php?id=42", outsideTexts = []) {
  const heading = { tagName: "H1", textContent: "Self" };
  const button = text => ({ tagName: "DIV", textContent: text, innerText: text, closest: () => null });
  const buttons = texts.map(button);
  const header = { tagName: "DIV", parentElement: { tagName: "BODY" }, querySelector: () => null,
    querySelectorAll: selector => selector === "h1" ? [heading] : selector.includes("button") ? buttons : [] };
  heading.parentElement = header;
  buttons.forEach(node => { node.parentElement = header; });
  const doc = facebookDocument({}, path);
  const scripts = doc.querySelectorAll;
  doc.querySelectorAll = selector => selector === "h1" ? [heading] : selector.includes("button") ? [...buttons, ...outsideTexts.map(button)] : scripts(selector);
  return doc;
}
test("Facebook's own profile header reads its exact mobile follower button without JSON", () => {
  const result = capture("FACEBOOK", "42", facebookDOMDocument(["5 팔로워", "친구 800명", "2 팔로잉"], undefined, ["999 팔로워"]));
  assert.equal(result.followers, 5);
  assert.equal(result.following, 2);
  assert.equal(result.displayName, "Self");
  assert.equal(result.stableId, "42");
  assert.equal(result.source, "facebook-webview-profile");
});
test("Facebook's own profile DOM preserves exact zero and supports grouped counts", () => {
  assert.equal(capture("FACEBOOK", "42", facebookDOMDocument(["0 팔로워"])).followers, 0);
  assert.equal(capture("FACEBOOK", "42", facebookDOMDocument(["12,345 followers", "Following 0"])).followers, 12345);
  assert.equal(capture("FACEBOOK", "42", facebookDOMDocument(["팔로워 5명"])).followers, 5);
});
test("Facebook profile section headings do not obscure its own header", () => {
  const doc = facebookDOMDocument(["5 팔로워"]);
  const query = doc.querySelectorAll;
  doc.querySelectorAll = selector => selector === "h1" ? [...query(selector),
    { tagName: "H1", textContent: "Friends" }, { tagName: "H1", textContent: "Posts" }] : query(selector);
  assert.equal(capture("FACEBOOK", "42", doc).followers, 5);
});
test("Facebook's navigation heading before the profile is not mistaken for its account header", () => {
  const doc = facebookDOMDocument(["5 팔로워"]);
  const navigation = { tagName: "H1", textContent: "Facebook" };
  navigation.parentElement = { tagName: "DIV", parentElement: { tagName: "BODY" }, querySelector: () => null,
    querySelectorAll: selector => selector === "h1" ? [navigation] : [] };
  const query = doc.querySelectorAll;
  doc.querySelectorAll = selector => selector === "h1" ? [navigation, ...query(selector)] : query(selector);
  assert.equal(capture("FACEBOOK", "42", doc).followers, 5);
  assert.equal(capture("FACEBOOK", "42", doc).displayName, "Self");
});
test("Facebook does not guess between different heading and follower count regions", () => {
  const doc = facebookDOMDocument(["5 팔로워"]);
  const query = doc.querySelectorAll;
  const otherHeading = facebookDOMDocument(["999 팔로워"]).querySelectorAll("h1")[0];
  doc.querySelectorAll = selector => selector === "h1" ? [...query(selector), otherHeading] : query(selector);
  assert.equal(capture("FACEBOOK", "42", doc).error, "exact_count_missing");
});
test("Facebook DOM counts require the cookie owner's profile and its heading", () => {
  assert.equal(capture("FACEBOOK", "42", facebookDOMDocument(["5 팔로워"], "/profile.php?id=99")).error, "own_profile_required");
  assert.equal(capture("FACEBOOK", "42", facebookDOMDocument(["5 팔로워"], "/")).error, "own_profile_required");
  const doc = facebookDOMDocument(["5 팔로워"]);
  const query = doc.querySelectorAll;
  doc.querySelectorAll = selector => selector === "h1" ? [] : query(selector);
  assert.equal(capture("FACEBOOK", "42", doc).error, "exact_count_missing");
  assert.equal(capture("FACEBOOK", "42", facebookDOMDocument(["5 팔로워"], "/profile.php?id=42&id=99")).error, "own_profile_required");
});
test("Facebook does not expand the profile header into a feed or another heading", () => {
  for (const boundary of ["feed", "otherHeading"]) {
    const doc = facebookDOMDocument(["5 팔로워"]);
    const heading = doc.querySelectorAll("h1")[0], header = heading.parentElement;
    if (boundary === "feed") header.querySelector = () => ({ tagName: "ARTICLE" });
    else {
      const query = header.querySelectorAll;
      header.querySelectorAll = selector => selector === "h1" ? [heading, { tagName: "H1", textContent: "Other" }] : query(selector);
    }
    assert.equal(capture("FACEBOOK", "42", doc).error, "exact_count_missing");
  }
});
test("Facebook ignores rounded, conflicting and unrelated profile DOM counters", () => {
  for (const texts of [["1.2K followers"], ["팔로워 1.2만명"], ["5 팔로워", "6 팔로워"], ["친구 500명"], ["팔로워"], ["1.2K followers", "5 followers"]])
    assert.equal(capture("FACEBOOK", "42", facebookDOMDocument(texts, undefined, ["999 followers"])).error, "exact_count_missing");
});
test("Facebook signed-in home leads to the session owner's profile without requiring home counts", () => {
  assert.deepEqual(capture("FACEBOOK", "42", facebookDocument({}, "/")),
    { error: "own_profile_required", profileURL: "https://www.facebook.com/profile.php?id=42" });
});
test("Facebook record is tied to owner ID and does not use friend count", () => {
  const doc = facebookDocument({ records: [{ id: "other", name: "Other", followers_count: 999 }, { id: "42", name: "Self", friends: { count: 800 }, followers: { count: 5 } }] });
  const result = capture("FACEBOOK", "42", doc);
  assert.equal(result.followers, 5);
  assert.equal(result.following, null);
  assert.equal(capture("FACEBOOK", null, doc).error, "identity_missing");
});
test("missing exact count produces an error rather than zero", () => {
  const result = capture("FACEBOOK", "42", facebookDocument({ id: "42", name: "Self", follower_count: "1.2K" }));
  assert.equal(result.error, "exact_count_missing");
});
test("Facebook prefers the exact owner record and preserves a confirmed zero", () => {
  const doc = facebookDocument({ records: [
    { id: "42", name: "Self", follower_count: "1.2K" },
    { id: "99", name: "Other", followers_count: 999 },
    { id: "42", name: "Self", followers_count: 0, following_count: 4 }
  ] });
  const result = capture("FACEBOOK", "42", doc);
  assert.equal(result.followers, 0);
  assert.equal(result.following, 4);
});
test("Facebook friend counts and other users never substitute for a missing owner follower count", () => {
  const doc = facebookDocument({ records: [
    { id: "42", name: "Self", friends: { count: 500 } },
    { id: "99", name: "Other", followers_count: 999 }
  ] });
  assert.deepEqual(capture("FACEBOOK", "42", doc), { error: "exact_count_missing" });
});
test("Facebook recognizes an owner username profile but does not repeatedly navigate without exact counts", () => {
  assert.deepEqual(capture("FACEBOOK", "42", facebookDocument({ id: "42", name: "Self", username: "self" }, "/self/")),
    { error: "exact_count_missing" });
});
test("Facebook rejects foreign or insecure pages and invalid cookie identity", () => {
  for (const url of ["http://www.facebook.com/profile.php?id=42", "https://facebook.com.example.test/profile.php?id=42",
    "https://user@www.facebook.com/profile.php?id=42", "https://www.facebook.com:8443/profile.php?id=42"]) {
    const doc = facebookDocument({ id: "42", name: "Self", followers_count: 5 });
    doc.location.href = url;
    assert.equal(capture("FACEBOOK", "42", doc).error, "own_profile_required");
  }
  assert.equal(capture("FACEBOOK", "not-a-cookie-id", facebookDocument({})).error, "identity_missing");
});

function facebookSpanDocument(texts, path = '/profile.php?id=42', boundary = null) {
  const doc = facebookDOMDocument([], path);
  const heading = doc.querySelectorAll('h1')[0], header = heading.parentElement;
  const spans = texts.map(text => ({ tagName: 'SPAN', textContent: text, innerText: text, closest: () => null }));
  header.querySelectorAll = selector => selector === 'h1' ? [heading] : selector.includes('span') ? spans : [];
  if (boundary === 'feed') header.querySelector = () => ({ tagName: 'ARTICLE' });
  if (boundary === 'otherHeading') header.querySelectorAll = selector => selector === 'h1' ? [heading, { tagName: 'H1' }] : spans;
  return doc;
}
test('Facebook text counters survive transitions between singular and plural followers', () => {
  for (const [text, count] of [['1 follower', 1], ['2 followers', 2], ['Follower 1', 1], ['0 followers', 0]]) {
    const result = capture('FACEBOOK', '42', facebookSpanDocument(['follower', text, 'Following 3']));
    assert.equal(result.followers, count);
    assert.equal(result.following, 3);
    assert.equal(result.precision, 'EXACT');
  }
});
test('Facebook singular labels retain identity, rounding, ambiguity and header boundaries', () => {
  for (const texts of [['1.2K follower'], ['1 follower', '2 followers'], ['1 follower', '1.2K followers']])
    assert.equal(capture('FACEBOOK', '42', facebookSpanDocument(texts)).error, 'exact_count_missing');
  for (const boundary of ['feed', 'otherHeading'])
    assert.equal(capture('FACEBOOK', '42', facebookSpanDocument(['1 follower'], undefined, boundary)).error, 'exact_count_missing');
  assert.equal(capture('FACEBOOK', '42', facebookSpanDocument(['1 follower'], '/profile.php?id=99')).error, 'own_profile_required');
});
test('Facebook inline counters do not require whitespace between numbers and labels', () => {
  for (const [text, count] of [['1follower', 1], ['Follower1', 1], ['12,345followers', 12345], ['팔로워0명', 0], ['0팔로워', 0]]) {
    const result = capture('FACEBOOK', '42', facebookSpanDocument([text, 'Following3']));
    assert.equal(result.followers, count);
    assert.equal(result.following, 3);
  }
});
test('Facebook compact counters still reject rounded and conflicting numbers', () => {
  for (const texts of [['1.2Kfollower'], ['1follower', '2followers'], ['1follower', '1.2Kfollowers'], ['Follower1e3']])
    assert.equal(capture('FACEBOOK', '42', facebookSpanDocument(texts)).error, 'exact_count_missing');
});
test('Facebook personal profiles read exact text spans after the header is hydrated', () => {
  for (const [text, count] of [['0 followers', 0], ['12,345 팔로워', 12345], ['팔로워 5명', 5]]) {
    const result = capture('FACEBOOK', '42', facebookSpanDocument(['팔로워', text, 'Following', 'Following 2']));
    assert.equal(result.followers, count);
    assert.equal(result.following, 2);
    assert.equal(result.stableId, '42');
  }
});
test('Facebook text spans cannot cross profile ownership, feeds, rounded or conflicting counts', () => {
  for (const texts of [['1.2K followers'], ['5 followers', '6 followers'], ['Friends 500'], ['followers']])
    assert.equal(capture('FACEBOOK', '42', facebookSpanDocument(texts)).error, 'exact_count_missing');
  for (const boundary of ['feed', 'otherHeading'])
    assert.equal(capture('FACEBOOK', '42', facebookSpanDocument(['5 followers'], undefined, boundary)).error, 'exact_count_missing');
  assert.equal(capture('FACEBOOK', '42', facebookSpanDocument(['5 followers'], '/profile.php?id=99')).error, 'own_profile_required');
});
function facebookSjsDocument(records, path = '/profile.php?id=42') {
  const doc = facebookDOMDocument([], path), previous = doc.querySelectorAll;
  doc.querySelectorAll = selector => selector.startsWith('script') ?
    (selector.includes('[data-sjs]') ? [{ textContent: JSON.stringify({ require: records }) }] : []) : previous(selector);
  return doc;
}
test('Facebook personal profiles read bounded data-sjs JSON tied to the authenticated owner', () => {
  const result = capture('FACEBOOK', '42', facebookSjsDocument([
    { id: '99', name: 'Other', followers_count: 999 }, { id: '42', name: 'Self', followers_count: 0, following_count: 2 }
  ]));
  assert.equal(result.followers, 0);
  assert.equal(result.following, 2);
  assert.equal(result.stableId, '42');
});
test('Facebook data-sjs cannot substitute another owner, a friend count or executable content', () => {
  for (const records of [[{ id: '99', name: 'Other', followers_count: 999 }],
    [{ id: '42', name: 'Self', friends: { count: 500 } }], [{ id: '42', name: 'Self', followers_count: '1.2K' }]])
    assert.equal(capture('FACEBOOK', '42', facebookSjsDocument(records)).error, 'exact_count_missing');
  for (const text of ['window.data={"id":"42","name":"Self","followers_count":5}', ' '.repeat(4 * 1024 * 1024) + '{}']) {
    const doc = facebookSjsDocument([]), previous = doc.querySelectorAll;
    doc.querySelectorAll = selector => selector.startsWith('script') ? [{ textContent: text }] : previous(selector);
    assert.equal(capture('FACEBOOK', '42', doc).error, 'exact_count_missing');
  }
});

function facebookPageDocument(data = {}, texts = ["12,345 팔로워"], path = "/fixture.page", pageLabel = true) {
  const doc = facebookDOMDocument(texts, path);
  const header = doc.querySelectorAll('h1')[0].parentElement, headerQuery = header.querySelectorAll;
  const labels = pageLabel ? [{ textContent: "페이지 · 브랜드", closest: () => null }] : [];
  header.querySelectorAll = selector => selector === 'span,div' ? labels : headerQuery(selector);
  const previous = doc.querySelectorAll;
  doc.querySelectorAll = selector => selector.startsWith("script") ? [{ textContent: JSON.stringify(data) }] :
    selector === "span,div" ? labels : previous(selector);
  const metadata = { 'link[rel="canonical"]': ["href", "https://www.facebook.com/profile.php?id=99"] };
  doc.querySelector = selector => metadata[selector] ? { getAttribute: key => key === metadata[selector][0] ? metadata[selector][1] : null } : null;
  doc.metadata = metadata;
  return doc;
}
test("explicit Facebook Page capture separates the selected target from the login owner", () => {
  const result = captureFacebookPage("42", null, facebookPageDocument());
  assert.equal(result.accountType, "PAGE");
  assert.equal(result.provider, "FACEBOOK");
  assert.equal(result.stableId, "99");
  assert.equal(result.sessionOwnerId, "42");
  assert.equal(result.followers, 12345);
  assert.equal(result.following, null);
  assert.equal(result.source, "facebook-webview-page");
  assert.equal(result.profileURL, "https://www.facebook.com/profile.php?id=99");
});
test("Page capture requires a Page context and cannot turn a personal profile into a Page", () => {
  assert.equal(captureFacebookPage("42", null, facebookPageDocument({}, ["5 팔로워"], "/profile.php?id=99", false)).error, "page_required");
  assert.equal(captureFacebookPage("42", null, facebookPageDocument({}, ["5 팔로워"], "/profile.php?id=42")).error, "page_identity_missing");
  const self = facebookPageDocument();
  self.metadata['link[rel="canonical"]'][1] = "https://www.facebook.com/profile.php?id=42";
  assert.equal(captureFacebookPage("42", null, self).error, "page_required");
  assert.equal(captureFacebookPage(null, null, facebookPageDocument()).error, "identity_missing");
});
test("Page capture rejects feeds, posts, arbitrary Page records and foreign origins", () => {
  const other = { __typename: "Page", id: "88", username: "other", name: "Other", followers_count: 999 };
  for (const path of ["/", "/login/", "/home.php", "/search/", "/groups/99", "/fixture.page/posts/9", "/profile.php?id=99&id=88"])
    assert.equal(captureFacebookPage("42", null, facebookPageDocument(other, [], path)).error, "page_required");
  const unrelated = facebookPageDocument(other, [], "/fixture.page", false);
  assert.equal(captureFacebookPage("42", null, unrelated).error, "page_required");
  const foreign = facebookPageDocument(); foreign.location.href = "https://facebook.com.example.test/fixture.page";
  assert.equal(captureFacebookPage("42", null, foreign).error, "page_required");
});
test("Page JSON must match the visible target and preserves exact zero without using likes", () => {
  const target = { __typename: "Page", id: "99", username: "fixture.page", name: "Fixture Page", followers_count: 0, fan_count: 800 };
  assert.equal(captureFacebookPage("42", null, facebookPageDocument({ records: [
    { ...target, id: "88", username: "other", followers_count: 999 }, target
  ] }, [], undefined, false)).followers, 0);
  assert.equal(captureFacebookPage("42", null, facebookPageDocument({ ...target, followers_count: undefined }, ["친구 500명", "좋아요 800개"])).error, "exact_count_missing");
});
test("Page IDs and existing connections never follow conflicting or substituted targets", () => {
  assert.equal(captureFacebookPage("42", "88", facebookPageDocument()).error, "page_mismatch");
  const conflict = facebookPageDocument({}, ["5 팔로워"], "/profile.php?id=88");
  assert.equal(captureFacebookPage("42", null, conflict).error, "page_identity_missing");
  const missing = facebookPageDocument(); missing.querySelector = () => null;
  assert.equal(captureFacebookPage("42", null, missing).error, "page_identity_missing");
  const foreignCanonical = facebookPageDocument(); foreignCanonical.metadata['link[rel="canonical"]'][1] = 'https://example.test/fixture.page';
  assert.equal(captureFacebookPage("42", null, foreignCanonical).error, "page_identity_missing");
  assert.equal(captureFacebookPage("42", null, facebookPageDocument({}, ['5 followers'], '/99')).stableId, '99');
  assert.equal(captureFacebookPage("42", null, facebookPageDocument({ __typename: 'User', id: '99', name: 'Fixture User' })).error, 'page_required');
});
test("Page capture does not invent precision for rounded and conflicting follower totals", () => {
  for (const texts of [["1.2K followers"], ["팔로워 1.2만명"], ["5 followers", "6 followers"], []])
    assert.equal(captureFacebookPage("42", null, facebookPageDocument({}, texts)).error, "exact_count_missing");
  const record = { __typename: "Page", id: "99", name: "Fixture Page", followers_count: 5 };
  assert.equal(captureFacebookPage("42", null, facebookPageDocument({ records: [record, { ...record, followers_count: 6 }] })).error, "exact_count_missing");
});
test("Page-specific metadata can identify an exact Page header while owner capture remains unchanged", () => {
  const page = facebookPageDocument({}, ["0 followers"], "/fixture.page", false);
  page.metadata['meta[property="fb:page_id"]'] = ["content", "99"];
  assert.equal(captureFacebookPage("42", null, page).followers, 0);
  assert.equal(capture("FACEBOOK", "42", page).error, "own_profile_required");
});
test("unrelated Page labels and malformed Page metadata cannot classify a personal header", () => {
  const unrelated = facebookPageDocument({}, ['5 followers'], '/profile.php?id=99', false);
  const query = unrelated.querySelectorAll;
  unrelated.querySelectorAll = selector => selector === 'span,div' ? [{ textContent: 'Page · Brand', closest: () => null }] : query(selector);
  assert.equal(captureFacebookPage('42', null, unrelated).error, 'page_required');
  const malformed = facebookPageDocument({}, ['5 followers'], '/profile.php?id=99', false);
  malformed.metadata['meta[property="fb:page_id"]'] = ['content', 'not-a-page-id'];
  assert.equal(captureFacebookPage('42', null, malformed).error, 'page_required');
});
test("conflicting exact JSON and Page header totals require another observation", () => {
  const record = { __typename: 'Page', id: '99', name: 'Fixture Page', followers_count: 5 };
  assert.equal(captureFacebookPage('42', null, facebookPageDocument(record, ['6 followers'])).error, 'exact_count_missing');
  assert.equal(captureFacebookPage('42', null, facebookPageDocument(record, ['5 followers'])).followers, 5);
});
function facebookPageDescriptionDocument(description, options = {}) {
  const record = { __typename: 'Page', id: '99', username: 'fixture.page', name: 'Fixture Page', ...options.record };
  const doc = facebookPageDocument(record, options.header || ['팔로워 1.2만명']);
  doc.metadata['meta[property="og:title"]'] = ['content', options.title || 'Fixture Page'];
  doc.metadata['meta[property="og:description"]'] = ['content', description];
  if (options.description != null) doc.metadata['meta[name="description"]'] = ['content', options.description];
  return doc;
}
test('Page descriptions recover an exact total only for the already identified Page', () => {
  for (const [description, expected] of [
    ['Fixture Page. 팔로워 12,345명 · 이야기하고 있는 사람들 90명.', 12345],
    ['Fixture Page. 12,345 followers · 90 talking about this.', 12345],
    ['Fixture Page. Followers 0 · 0 talking about this.', 0]
  ]) {
    const doc = facebookPageDescriptionDocument(description);
    const result = captureFacebookPage('42', '99', doc, 'https://www.facebook.com/fixture.page');
    assert.equal(result.followers, expected); assert.equal(result.stableId, '99');
    assert.equal(result.sessionOwnerId, '42'); assert.equal(result.precision, 'EXACT');
  }
});
test('Page description totals reject rounded values, bio text, another name and another Page', () => {
  for (const description of ['Fixture Page. 팔로워 1.2만명 · Public page.',
    'Fixture Page. Followers 1.2K · Public page.', 'Fixture Page. 12,345 likes · 90 talking about this.',
    'Fixture Page. Public page. 팔로워 12,345명', 'Fixture Page. Followers 12345 · Public page.',
    'Other Page. 팔로워 12,345명 · 이야기하고 있는 사람들 90명.'])
    assert.equal(captureFacebookPage('42', null, facebookPageDescriptionDocument(description)).error, 'exact_count_missing');
  const title = facebookPageDescriptionDocument('Fixture Page. 팔로워 12,345명 · 이야기하고 있는 사람들 90명.', { title: 'Other Page' });
  assert.equal(captureFacebookPage('42', null, title).error, 'exact_count_missing');
  const personal = facebookPageDescriptionDocument('Fixture Page. 팔로워 12,345명 · 이야기하고 있는 사람들 90명.', { record: { __typename: 'User' } });
  assert.equal(captureFacebookPage('42', null, personal).error, 'page_required');
  assert.equal(captureFacebookPage('42', '88', facebookPageDescriptionDocument('Fixture Page. 팔로워 12,345명 · 이야기하고 있는 사람들 90명.')).error, 'page_mismatch');
});
test('Page description totals cannot override conflicting JSON, headers or other metadata', () => {
  const description = 'Fixture Page. 팔로워 12,345명 · 이야기하고 있는 사람들 90명.';
  for (const options of [{ record: { followers_count: 12346 } }, { header: ['12,346 followers'] },
    { description: 'Fixture Page. 팔로워 12,346명 · 이야기하고 있는 사람들 90명.' }]) {
    const result = captureFacebookPage('42', null, facebookPageDescriptionDocument(description, options));
    assert.equal(result.error, 'exact_count_missing'); assert.equal(result.confirmedPage, undefined);
  }
});
test('anonymous Page metadata requires a previously confirmed Page ID and matching name', () => {
  const doc = facebookPageDescriptionDocument('Fixture Page. 팔로워 12,345명 · 이야기하고 있는 사람들 90명.',
    { record: { __typename: 'User' } });
  assert.equal(captureFacebookPage('42', null, doc).error, 'page_required');
  assert.equal(captureFacebookPage('42', '99', doc).error, 'page_required');
  assert.equal(captureFacebookPage('42', '99', doc, null, 'Fixture Page').followers, 12345);
  assert.equal(captureFacebookPage('42', '88', doc, null, 'Fixture Page').error, 'page_mismatch');
  assert.equal(captureFacebookPage('42', '99', doc, null, 'Other Page').error, 'page_required');
  const headOnly = { location: doc.location, querySelector: doc.querySelector, querySelectorAll: () => [] };
  assert.equal(captureFacebookPage('42', '99', headOnly, 'https://www.facebook.com/fixture.page', 'Fixture Page').followers, 12345);
  assert.equal(captureFacebookPage('42', null, headOnly).error, 'page_required');
  const rounded = captureFacebookPage('42', null, facebookPageDescriptionDocument('Fixture Page. 팔로워 1.2만명'));
  assert.deepEqual(rounded.confirmedPage, { id: '99', name: 'Fixture Page' });
});
test("new Facebook Pages bind their User profile to its delegate Page and canonical profile ID", () => {
  const user = { __typename: 'User', id: '99', username: 'fixture.page', name: 'Fixture Page',
    url: 'https://www.facebook.com/fixture.page/', delegate_page: { id: '88' }, followers_count: 0 };
  for (const path of ['/people/Fixture-Page/99/', '/p/Fixture-Page-99/']) {
    const doc = facebookPageDocument({ records: [user, { __typename: 'User', id: '99', name: 'Fixture Page' }] }, [], undefined, false);
    doc.metadata['link[rel="canonical"]'][1] = 'https://www.facebook.com' + path;
    const captured = captureFacebookPage('42', null, doc);
    assert.equal(captured.stableId, '99');
    assert.equal(captured.sessionOwnerId, '42');
    assert.equal(captured.accountType, 'PAGE');
    assert.equal(captured.followers, 0);
  }
});
test("new Page canonical addresses alone never classify personal or recommended User records as Pages", () => {
  for (const record of [
    { __typename: 'User', id: '99', name: 'Personal', username: 'fixture.page', followers_count: 5 },
    { __typename: 'User', id: '99', name: 'Personal', username: 'fixture.page', delegate_page: { id: 'invalid' }, followers_count: 5 },
    { __typename: 'User', id: '77', name: 'Other Page', username: 'other', delegate_page: { id: '66' }, followers_count: 999 }
  ]) {
    const doc = facebookPageDocument(record, ['5 followers'], undefined, false);
    doc.metadata['link[rel="canonical"]'][1] = 'https://www.facebook.com/people/Fixture-Page/99/';
    assert.equal(captureFacebookPage('42', null, doc).error, 'page_required');
  }
});
test("new Page social context reads only an exact label linked to that Page's followers", () => {
  const user = { __typename: 'User', id: '99', username: 'fixture.page', name: 'Fixture Page', delegate_page: { id: '88' } };
  const item = (text, uri = 'https://www.facebook.com/fixture.page/followers/') => ({ text: { text }, uri });
  const doc = items => facebookPageDocument({ ...user, profile_social_context: { content: items } }, [], undefined, false);
  for (const [text, count] of [['팔로워 0명', 0], ['12,345 followers', 12345]])
    assert.equal(captureFacebookPage('42', null, doc([item(text)])).followers, count);
  assert.equal(captureFacebookPage('42', null, doc([item('5 followers', 'https://www.facebook.com/profile.php?id=99&sk=followers')])).followers, 5);
  const numeric = facebookPageDocument({ ...user, url: 'https://www.facebook.com/fixture.page/',
    profile_social_context: { content: [item('5 followers')] } }, [], '/profile.php?id=99', false);
  assert.equal(captureFacebookPage('42', null, numeric).followers, 5);
  for (const items of [[item('팔로워 1.2천명')], [item('1.2K followers')]])
    assert.equal(captureFacebookPage('42', null, doc(items)).error, 'rounded_count_only');
  for (const items of [[item('5 friends')],
    [item('5 followers', 'https://example.test/fixture.page/followers')], [item('5 followers', 'https://www.facebook.com/other/followers')],
    [item('5 followers', 'http://www.facebook.com/fixture.page/followers')],
    [item('5 followers', 'https://private@www.facebook.com/fixture.page/followers')],
    [item('5 followers', 'https://www.facebook.com/profile.php?id=99&id=77&sk=followers')],
    [item('5 followers', 'https://www.facebook.com/fixture.page/following')], [item('5 followers'), item('6 followers')]])
    assert.equal(captureFacebookPage('42', null, doc(items)).error, 'exact_count_missing');
});
test("new Page counts in another User fragment require a delegate proof for the same public profile ID", () => {
  const page = { __typename: 'User', id: '99', username: 'fixture.page', name: 'Fixture Page', delegate_page: { id: '88' } };
  const count = { __typename: 'User', id: '99', followers_count: 0 };
  const doc = data => facebookPageDocument(data, [], undefined, false);
  assert.equal(captureFacebookPage('42', null, doc({ records: [page, count, { ...count, id: '77', followers_count: 999 }] })).followers, 0);
  assert.equal(captureFacebookPage('42', null, doc(count)).error, 'page_required');
  assert.equal(captureFacebookPage('42', null, doc({ records: [page, count, { ...count, followers_count: 1 }] })).error, 'exact_count_missing');
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
function xDocument(users, path = "/home", links = []) {
  return {
    location: { href: "https://x.com" + path },
    defaultView: { __INITIAL_STATE__: { entities: { users: { entities: users } } } },
    querySelectorAll: selector => selector === "a[href]" ? links : [],
    querySelector: () => null
  };
}
test('Page fragments join by a proved identity regardless of script order', () => {
  const proof = { __typename: 'Page', id: '99', username: 'fixture.page', name: 'Fixture Page' };
  for (const fragment of [{ __typename: 'Page', id: '99', follower_count: 0 }, { id: '99', followers: { count: 0 } }]) {
    for (const records of [[fragment, proof], [proof, fragment]]) {
      const doc = facebookPageDocument({ records }, [], '/fixture.page', false);
      doc.metadata['link[rel="canonical"]'][1] = 'https://www.facebook.com/fixture.page/';
      const query = doc.querySelectorAll;
      doc.querySelectorAll = selector => selector.startsWith('script') ? records.map(record => ({ textContent: JSON.stringify(record) })) : query(selector);
      assert.equal(captureFacebookPage('42', null, doc).followers, 0);
    }
  }
  const sparse = facebookPageDocument({ id: '99', followers_count: 999 }, [], '/profile.php?id=99', false);
  assert.equal(captureFacebookPage('42', null, sparse).error, 'page_required');
});
test('Page fragments never supply another target or hide conflicting exact fields', () => {
  const proof = { __typename: 'Page', id: '99', username: 'fixture.page', name: 'Fixture Page' };
  const doc = records => facebookPageDocument({ records }, [], undefined, false);
  assert.equal(captureFacebookPage('42', null, doc([proof, { id: '88', followers_count: 999 }])).error, 'exact_count_missing');
  for (const fragment of [{ id: '99', followers_count: 5, follower_count: 6 },
    { id: '99', followers_count: 5, followers: { count: 6 } },
    { id: '99', username: 'other.page', followers_count: 5 },
    { id: '99', url: 'https://example.test/fixture.page', followers_count: 5 },
    { id: '99', url: 'https://www.facebook.com/profile.php?id=88', followers_count: 5 }])
    assert.ok(captureFacebookPage('42', null, doc([proof, fragment])).error);
  assert.equal(captureFacebookPage('42', null, doc([{ ...proof, followers_count: 5, follower_count: 6 }])).error, 'exact_count_missing');
});
test('a named Page redirect to a numeric route must prove the submitted name', () => {
  const proof = { __typename: 'Page', id: '99', username: 'fixture.page', name: 'Fixture Page', followers_count: 0 };
  const doc = facebookPageDocument(proof, [], '/profile.php?id=99', false);
  assert.equal(captureFacebookPage('42', null, doc, 'https://m.facebook.com/fixture.page/').followers, 0);
  assert.equal(captureFacebookPage('42', null, doc, 'https://www.facebook.com/other.page/').error, 'page_mismatch');
  assert.equal(captureFacebookPage('42', null, doc, 'https://example.test/fixture.page').error, 'page_mismatch');
  assert.equal(captureFacebookPage('42', null, doc, 'https://facebook.com/profile.php?id=88').error, 'page_mismatch');
  const noName = facebookPageDocument({}, ['0 followers'], '/profile.php?id=99');
  assert.equal(captureFacebookPage('42', null, noName, 'https://facebook.com/fixture.page').error, 'page_identity_missing');
});
test('a Page name that hydrates later is locally retryable until the submitted target is proved', () => {
  const proof = { __typename: 'Page', id: '99', name: 'Fixture Page', followers_count: 0 };
  const doc = facebookPageDocument({}, [], '/profile.php?id=99', false), query = doc.querySelectorAll;
  doc.querySelectorAll = selector => selector.startsWith('script') ? [{ textContent: JSON.stringify(proof) }] : query(selector);
  const requested = 'https://www.facebook.com/fixture.page/';
  assert.equal(captureFacebookPage('42', null, doc, requested).error, 'page_identity_missing');
  proof.url = requested;
  assert.equal(captureFacebookPage('42', null, doc, requested).followers, 0);
});
test('a numeric Page redirect to a named route preserves its selected ID', () => {
  const proof = { __typename: 'Page', id: '99', username: 'fixture.page', name: 'Fixture Page', followers_count: 0 };
  const doc = facebookPageDocument(proof, [], undefined, false);
  for (const address of ['/99', '/profile.php?id=99', '/people/Fixture/99/', '/p/Fixture-99/'])
    assert.equal(captureFacebookPage('42', null, doc, 'https://www.facebook.com' + address).followers, 0);
  assert.equal(captureFacebookPage('42', null, doc, 'https://www.facebook.com/88').error, 'page_mismatch');
});

test("X mobile home connects the cookie owner from bootstrap state without a desktop profile link", () => {
  const doc = xDocument({
    "99": { id_str: "99", screen_name: "other", followers_count: 999 },
    "42": { id_str: "42", screen_name: "self", name: "Self", followers_count: 0, friends_count: 4 }
  });
  const result = capture("X", "42", doc);
  assert.equal(result.stableId, "42");
  assert.equal(result.followers, 0);
  assert.equal(result.following, 4);
  assert.equal(result.profileURL, "https://x.com/self");
  assert.equal(capture("X", null, doc).error, "identity_missing");
});
test("X bootstrap records for other accounts cannot identify the signed-in owner", () => {
  const doc = xDocument({ "42": { id_str: "99", screen_name: "other", followers_count: 999 } });
  const result = capture("X", "42", doc);
  assert.equal(result.error, "owner_context_missing");
  assert.equal(result.stableId, undefined);
});
test("X owner metadata leads to the own profile when home lacks exact counts", () => {
  const result = capture("X", "42", xDocument({ "42": { id_str: "42", screen_name: "self" } }));
  assert.deepEqual(result, { error: "own_profile_required", profileURL: "https://x.com/self" });
});
test("X mobile own profile reads exact links without the desktop navigation and rejects foreign origins", () => {
  const doc = xDocument({ "42": { id_str: "42", screen_name: "self" } }, "/self", [
    countLink("https://example.test/self/followers", "999", "999 followers"),
    countLink("/other/followers", "888", "888 followers"),
    countLink("/self/verified_followers", "12,345", "12.3K Followers"),
    countLink("/self/following", null, "0 Following")
  ]);
  const result = capture("X", "42", doc);
  assert.equal(result.followers, 12345);
  assert.equal(result.following, 0);
  assert.equal(result.source, "x-webview-dom");
});
test("X rounded counts and foreign profile metadata are never captured as exact owner counts", () => {
  const doc = xDocument({ "42": { id_str: "42", screen_name: "self", followers_count: "1.2K" } }, "/self", [
    countLink("/self/followers", null, "1.2K Followers")
  ]);
  assert.equal(capture("X", "42", doc).error, "exact_count_missing");
  doc.location.href = "https://x.com.example.test/self";
  assert.equal(capture("X", "42", doc).error, "own_profile_required");
});
test("X retains owner capture when hydration removes the global bootstrap object", () => {
  const doc = xDocument({});
  doc.defaultView = {};
  const state = { entities: { users: { entities: { "42": {
    id_str: "42", screen_name: "self", name: 'Self } with "quotes"', followers_count: 7, friends_count: 0
  } } } } };
  const scripts = [{ textContent: "window.__INITIAL_STATE__ = " + JSON.stringify(state) + ";throw new Error('Never execute bootstrap scripts');" }];
  doc.querySelectorAll = selector => selector === 'script:not([src])' ? scripts : [];
  const result = capture("X", "42", doc);
  assert.equal(result.followers, 7);
  assert.equal(result.following, 0);
  assert.equal(result.displayName, 'Self } with "quotes"');
});
test("X malformed, executable, and oversized bootstrap assignments cannot connect an account", () => {
  for (const text of [
    'window.__INITIAL_STATE__={broken JSON};',
    'window.__INITIAL_STATE__=(function(){throw new Error("Do not execute")})();',
    'window.__INITIAL_STATE__={"unterminated":"value}',
    'window.__INITIAL_STATE__=' + ' '.repeat(4 * 1024 * 1024) + '{}'
  ]) {
    const doc = xDocument({}); doc.defaultView = {};
    doc.querySelectorAll = selector => selector === 'script:not([src])' ? [{ textContent: text }] : [];
    assert.equal(capture("X", "42", doc).error, "owner_context_missing");
  }
});
test("X GraphQL owner records still prefer the record with an exact count", () => {
  const doc = xDocument({});
  const records = [{ rest_id: "99", legacy: { screen_name: "other", followers_count: 999 } },
    { rest_id: "42", legacy: { screen_name: "self" } },
    { rest_id: "42", legacy: { screen_name: "self", followers_count: 0, friends_count: 3 } }];
  doc.querySelectorAll = selector => selector === 'script:not([src])' || selector === 'a[href]' ? [] : [{ textContent: JSON.stringify(records) }];
  assert.equal(capture("X", "42", doc).followers, 0);
});
test("X desktop profile links retain the own-profile fallback and reject foreign profile URLs", () => {
  const doc = xDocument({}, "/self");
  let href = "/self/";
  doc.querySelector = () => countLink(href, null, "Profile");
  doc.querySelectorAll = selector => selector === 'a[href]' ? [countLink('/self/followers', null, '8 Followers')] : [];
  assert.equal(capture("X", "42", doc).followers, 8);
  href = "https://example.test/self";
  assert.equal(capture("X", "42", doc).error, "owner_context_missing");
});
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
test("Instagram hydrated data-sjs owner counts avoid the API that may be rate limited", async () => {
  const { captureAsync } = require("../web-session-capture.js");
  for (const followers of [0, 12345]) {
    const doc = instagramDocument({});
    doc.querySelectorAll = selector => selector.includes('script[data-sjs]') ? [{ textContent: JSON.stringify({
      data: { user: { id: "42", username: "self", full_name: "Self", edge_followed_by: { count: followers }, edge_follow: { count: 4 } } }
    }) }] : [];
    let calls = 0;
    const result = await captureAsync("INSTAGRAM", "42", doc, async () => { calls++; return new Response('', { status: 429 }); });
    assert.equal(result.followers, followers);
    assert.equal(result.following, 4);
    assert.equal(calls, 0);
  }
});
test("Instagram's verified owner uses the existing web profile endpoint once without a fallback after 429", async () => {
  const { captureAsync } = require("../web-session-capture.js");
  const doc = instagramDocument({ viewer: { id: "42", username: "self" } });
  const calls = [];
  const result = await captureAsync("INSTAGRAM", "42", doc, async (url, options) => {
    calls.push(url);
    assert.equal(options.credentials, 'include');
    return new Response(JSON.stringify({ data: { user: { id: "42", username: "self", edge_followed_by: { count: 7 }, edge_follow: { count: 0 } } } }));
  });
  assert.deepEqual(calls, ["https://www.instagram.com/api/v1/users/web_profile_info/?username=self"]);
  assert.equal(result.followers, 7); assert.equal(result.following, 0);
  calls.length = 0;
  const limited = await captureAsync("INSTAGRAM", "42", doc, async url => {
    calls.push(url); return new Response('', { status: 429, headers: { 'Retry-After': '120' } });
  });
  assert.equal(limited.status, 429); assert.equal(limited.retryAfterSeconds, 120); assert.equal(calls.length, 1);
});
test("Instagram data-sjs never executes code or connects another owner's or a rounded record", () => {
  const doc = instagramDocument({});
  for (const text of [
    JSON.stringify({ data: { user: { id: "99", username: "other", follower_count: 999 } } }),
    JSON.stringify({ data: { user: { id: "42", username: "self", follower_count: "1.2K" } } }),
    '{"user":(function(){throw new Error("Never execute");})()}',
    ' '.repeat(4 * 1024 * 1024) + '{}'
  ]) {
    doc.querySelectorAll = selector => selector.includes('script[data-sjs]') ? [{ textContent: text }] : [];
    assert.equal(capture("INSTAGRAM", "42", doc).error, "exact_count_missing");
  }
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
