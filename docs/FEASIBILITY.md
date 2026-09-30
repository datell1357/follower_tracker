# 팔로워 트래커 구현 가능성 조사

조사일: 2026-10-01, Asia/Seoul

대상 저장소: <https://github.com/datell1357/follower_tracker>

범위: 모바일 로그인 세션을 이용하는 무료 앱의 기술 가능성, 데이터 정확성, 위젯, 배포 제약

## 1. 결론

**휴대폰에서 SNS에 로그인하고, 앱 소유 WebView의 세션으로 데이터를 읽어 로컬 기록과 홈 화면 위젯에 전달하는 기반은 구현할 수 있다.** 유료 API나 사용자 쿠키를 보관하는 서버는 이 구조의 필수 요소가 아니다.

그러나 **Facebook·Instagram·TikTok·X·Reddit 모두에서 전체 팔로워 명단과 지속적인 백그라운드 수집이 작동한다고 확인한 상태는 아니다.** 쿠키 접근 SDK가 있다는 사실과 각 SNS가 그 세션을 네이티브 요청에서도 받아준다는 사실은 별개다. 현재 판단은 실기기 기술 검증 진행 가능이며, 전 플랫폼 완성 제품의 출시 가능성은 조건부다.

가장 먼저 확인할 사항은 화면 디자인보다 다음 세 가지다.

1. 로그인한 자기 계정의 정확한 팔로워 수와 계정 식별자를 읽을 수 있는가?
2. WebView를 닫은 뒤에도 기기 내부의 네이티브 요청으로 수집할 수 있는가?
3. 팔로워·팔로잉을 마지막 페이지까지 읽고, 비교 가능한 명단을 만들 수 있는가?

이 세 조건을 SNS별로 통과시킨 후 지원 기능을 확정한다. 앱스토어 배포에는 서비스의 허용 범위도 따로 확인해야 한다. 기기 내부 처리와 사용자 동의만으로 서비스의 자동 수집 허가까지 얻는 것은 아니다.

## 2. 요구사항과 이번 조사의 증거 범위

사용자 요구사항은 다음과 같다.

- 유료 기능 없이 팔로워 수 추적, 위젯, 언팔로우 확인, 맞팔 상태 확인을 제공한다.
- 휴대폰에서 WebView 등의 방법으로 직접 로그인하고, 그 로그인 세션을 데이터 수집에 사용한다.
- 여러 SNS의 기록을 한 앱에서 확인한다. 유료 API 계약이 필요한 경로를 필수 기능에 넣지 않는다.
- 위젯에서는 앱을 열지 않고 마지막으로 수집한 수와 상태를 확인할 수 있어야 한다.

조사에서는 공식 SDK·API·운영체제 문서와 실제 App Store/Google Play 공개 페이지를 읽었다. Codex 앱 브라우저로 TikTok 개발 문서, Meta의 Instagram/Graph API 문서, Apple WidgetKit 문서, 팔로워 위젯 앱의 스토어 페이지를 직접 확인했다. 요청에 포함된 `aside`는 현재 세션의 도구·실행 명령에서 찾지 못해 Codex 앱 브라우저와 웹 검색을 대체 수단으로 사용했다.

**하지 않은 검증:** 사용자 계정 로그인, 로그인 쿠키 수집, SNS 비공식 요청 실행, 앱 설치·실행, 실제 iOS/Android 기기에서의 백그라운드 측정. 따라서 이 문서는 문서와 공개 페이지에 근거한 가능성 조사이며, 성공한 실기기 PoC 보고서는 아니다. 경쟁 앱 설명도 개발자의 주장으로 취급한다.

## 3. 기능별 판단

| 기능 | 판단 | 성립 조건과 한계 |
| --- | --- | --- |
| 앱 내부 WebView 로그인 세션 접근 | SDK 수준에서 가능 | 앱이 만든 WebView의 저장소에 한정한다. SNS 공식 앱이나 Safari/Chrome의 쿠키를 가져오는 구조가 아니다. |
| 팔로워 수 수집 | SNS별 조건부 | 로그인 후 자기 계정의 정확한 수를 읽는 경로를 검증해야 한다. 화면의 `1.2만` 같은 축약 수치는 정확한 수로 저장하지 않는다. |
| 수의 이력·증감·그래프 | 구현 가능 | 성공한 관측값과 수집 시각을 로컬 저장한다. 누락 기간의 변화를 추측해 채우지 않는다. |
| iOS·Android 홈 화면 위젯 | 구현 가능 | 마지막 성공값을 표시한다. 새 데이터를 받는 시각은 OS 스케줄과 세션 상태에 영향을 받는다. |
| 앱을 열지 않은 상태의 자동 수집 | SNS·OS별 조건부 | WebView 없이 네이티브 요청이 가능해야 한다. 로그인 확인 화면이나 브라우저 실행이 필수인 경로는 전경 수집만 가능할 수 있다. |
| 누가 언팔로우했는지 | 명단 접근 시 조건부 | 과거와 현재의 완전한 팔로워 명단이 필요하다. 첫 연결 이전의 언팔로우는 알아낼 수 없다. 명단에서 사라진 이유가 실제 언팔로우인지는 단정하지 못할 수 있다. |
| 나를 맞팔하지 않는 사람 | 두 명단 접근 시 조건부 | 현재 팔로잉 명단에서 현재 팔로워 명단을 뺀다. 한쪽 명단이 불완전하면 판정하지 않는다. |
| 모든 SNS의 초 단위 실시간 수집 | 보장 불가 | 위젯 예산, 배터리 제한, 서비스 응답 지연과 로그인 세션 만료가 있다. |

## 4. 쿠키를 이용하는 구조가 가능한 이유

### 4.1 운영체제에 공식 접근 수단이 있다

iOS의 `WKHTTPCookieStore`는 앱의 WebView에 저장된 쿠키를 비동기로 조회하는 API를 제공한다. Android의 `CookieManager.getCookie(url)`는 해당 URL에 적용되는 쿠키를 HTTP 요청에 사용할 수 있는 형태로 반환한다. Android 문서는 파티션 쿠키의 적용 조건도 설명한다. [Apple 쿠키 조회 문서](https://developer.apple.com/documentation/webkit/wkhttpcookiestore/getallcookies%28_%3A%29), [Android CookieManager](https://developer.android.com/reference/android/webkit/CookieManager)

따라서 기본 흐름은 `앱 내부 로그인 → 앱 소유 세션 확인 → 해당 SNS 요청 → 로컬 저장 → 위젯 표시`로 설계할 수 있다. JavaScript의 `document.cookie`만 사용하는 접근은 HttpOnly 쿠키와 저장소 차이를 처리하기에 충분하지 않다. 쿠키의 도메인·경로·만료·보안 속성을 유지하는 네이티브 저장소 접근을 검증한다.

### 4.2 쿠키만 복사하면 항상 되는 것은 아니다

SNS의 로그인된 웹 페이지는 쿠키 외에 CSRF 값, 요청 헤더, 페이지에서 만들어지는 값, 기기나 세션에 연결된 상태를 요구할 수 있다. 이는 플랫폼별로 확인해야 할 구현 가설이며, 이번 조사에서 특정 요청 규격을 검증한 것은 아니다. 앱은 실제 응답으로 다음을 구분해야 한다.

- 네이티브 요청만으로 읽을 수 있는 경로
- 로그인된 WebView가 열려 있을 때만 읽을 수 있는 경로
- 재로그인·추가 인증·서비스 확인이 필요한 경로
- 자동 수집 허용 여부나 기술 접근이 해결되지 않은 경로

서비스의 확인 화면이나 접근 제한이 발생하면 수집을 중단하고 사용자가 공식 페이지에서 해결하도록 한다. 로그인 정보 위조나 제한 우회를 안정성 대책으로 삼지 않는다.

### 4.3 외부 브라우저 로그인과 쿠키 수집은 다르다

OAuth는 외부 브라우저를 사용하는 방식이 권장되며, 외부 브라우저의 세션 저장소는 앱에 노출되지 않는다. 앱 내부 WebView는 앱이 세션에 접근할 수 있는 다른 보안 구조다. 따라서 `ASWebAuthenticationSession`이나 Android Custom Tabs로 로그인했다고 해서 앱에 브라우저 쿠키가 전달되는 것은 아니다. WebView에서 SNS 자체 로그인이 가능한지와 Google/Apple 등의 연동 로그인이 가능한지도 별도로 시험해야 한다. [RFC 8252: Native Apps OAuth](https://www.rfc-editor.org/rfc/rfc8252)

제품 설명에서 “WebView라 앱은 로그인 정보에 접근할 수 없다”고 주장하면 안 된다. 설계 원칙은 비밀번호 필드를 읽거나 기록하지 않고, 로그인 후 필요한 세션만 제한적으로 취급하는 것이다.

## 5. SNS별 데이터 접근 판단

아래 표의 공식 API 정보는 비교 근거다. 공식 API가 제공하지 않는 기능이 WebView에서도 불가능하다는 뜻은 아니다. 반대로 공식 API에 수치가 있다고 해서 로그인 쿠키로 그 API를 호출할 수 있다는 뜻도 아니다.

| SNS | 공식 문서에서 확인한 범위 | 무료 로그인 세션 방식에서 남은 확인 | 권장 검증 우선순위 |
| --- | --- | --- | --- |
| Instagram | 프로페셔널 계정의 `followers_count`, `follows_count`가 있다. 확인한 IG User 참조에는 전체 팔로워·팔로잉 명단 조회 기능이 제시되지 않는다. | 일반 계정과 프로페셔널 계정 각각의 정확한 수, 전체 명단 페이지 처리, WebView 종료 후 세션 재사용을 실측해야 한다. | 1: 동일한 방식과 기능을 설명하는 Android 앱 사례가 있어 첫 후보로 적합하다. |
| TikTok | User Info에서 `user.info.stats` 권한으로 팔로워·팔로잉 수를 제공한다. 명단을 제공하는 Data Portability는 현재 EEA·영국 사용자 대상이다. | 쿠키 기반 수집은 공식 OAuth와 별도다. 한국 계정의 전체 명단과 네이티브 요청 재사용을 검증해야 한다. | 2: 수·위젯 검증 후 명단을 별도 평가한다. |
| X | 공식 API에 사용자 수치와 팔로워·팔로잉 조회 기능이 있으나 현재 종량 과금이다. | 유료 API를 사용하지 않는 세션 경로의 실제 작동과 자동 수집 허용 범위를 확인해야 한다. 공식 API 요금 문제를 쿠키로 해결했다고 서비스 허용까지 확보되는 것은 아니다. | 3: 기술 검증과 서비스 허용 검토를 함께 진행한다. |
| Facebook | Page 객체에는 `followers_count`가 있다. Page와 개인 프로필은 다른 대상이며 친구 수와 팔로워 수도 다르다. | 개인 프로필·프로페셔널 모드·페이지를 구분해 수와 명단을 확인해야 한다. 친구 명단을 팔로워 명단으로 대체하면 안 된다. | 4: 먼저 지원 계정 종류를 명확히 한다. |
| Reddit | 공식 도움말은 자기 프로필에서 팔로워 수를 눌러 팔로워를 확인하는 기능을 설명한다. 이번 조사로 범용 모바일 앱의 전체 관계 조회 API를 확인하지는 못했다. | 로그인한 자기 프로필의 수·명단과 사용자 팔로잉을 읽을 수 있는지 검증해야 한다. 커뮤니티 구독자나 karma를 개인 팔로워 수로 취급하면 안 된다. | 5: 프로필 팔로워 기능과 수집 허용 범위를 먼저 확인한다. |

근거:

- Instagram: [플랫폼 개요](https://developers.facebook.com/documentation/instagram-platform/overview?locale=en_US), [IG User 참조](https://developers.facebook.com/documentation/instagram-platform/instagram-graph-api/reference/ig-user?locale=en_US)
- TikTok: [User Info](https://developers.tiktok.com/docs/en/tiktok-api-v2-get-user-info), [Data Portability](https://developers.tiktok.com/products/data-portability-api/). Research API는 자격을 갖춘 비상업 연구용이므로 일반 소비자 앱의 대체 수집 수단으로 계획하지 않는다. [Research API](https://developers.tiktok.com/products/research-api/)
- X: [사용자 조회](https://docs.x.com/x-api/users/lookup/introduction), [관계 조회](https://docs.x.com/x-api/users/follows/introduction), [현재 가격 체계](https://docs.x.com/x-api/getting-started/pricing)
- Facebook: [Graph API Page 참조](https://developers.facebook.com/docs/graph-api/reference/page/?locale=en_US)
- Reddit: [팔로워 기능 도움말](https://support.reddithelp.com/hc/en-us/articles/4406644781204-How-do-followers-work-and-how-can-I-opt-in-or-out)

**이번에 실기기에서 검증된 SNS 세션 수집기는 0개다.** 첫 출시부터 다섯 플랫폼의 모든 기능을 약속할 근거는 없다. 플랫폼별로 통과한 기능을 명시하는 방식이 필요하다.

## 6. 언팔로우·맞팔 정확성

과거 팔로워 집합을 `F_previous`, 현재 팔로워를 `F_current`, 현재 팔로잉을 `G_current`라고 하면 다음 계산이 가능하다.

```text
이전 명단에서 사라진 계정 = F_previous - F_current
나를 맞팔하지 않는 계정 = G_current - F_current
내가 맞팔하지 않는 계정 = F_current - G_current
맞팔 계정 = F_current ∩ G_current
```

팔로워 수가 100명에서 99명으로 줄었다는 정보만으로 사라진 계정을 알 수는 없다. 같은 기간에 여러 명이 추가·이탈했을 수도 있다. 또한 명단에서 사라지는 원인은 계정 비활성화, 차단, 서비스 필터링 등일 수 있다. X의 공식 도움말도 이런 원인을 구분해 설명한다. [X 팔로우 문제 도움말](https://help.x.com/en/using-x/common-following-issues)

앱에서는 첫 스캔을 기준점으로 저장하고, 페이지 처리 완료를 확인한 동일 계정의 명단끼리 비교한다. 중간 실패·반복 커서·제한된 명단을 이전 명단과 비교해 대량 언팔로우로 표시하면 안 된다. 이름 변경에 영향을 덜 받는 서비스 계정 ID를 우선 사용한다. 전체 명단을 읽었더라도 수집 중 관계가 바뀔 수 있으므로, 동시점 스냅샷을 보장하지 않는 경로에서는 이탈 후보를 다음 정상 스캔에서 재확인한다.

사용자에게는 원인을 확인할 수 없는 결과를 **언팔로우 추정**으로 표시하고 비교 시각을 제공한다. 완전한 명단에 접근하지 못하는 SNS에서는 해당 기능을 지원한다고 표시하지 않는다.

## 7. 위젯과 자동 갱신

Android 위젯의 `updatePeriodMillis`는 30분보다 잦은 갱신을 지원하지 않는다. WorkManager로 작업을 분리할 수 있지만 실행 시각은 전원 관리와 작업 조건에 영향을 받는다. iOS의 WidgetKit 역시 위젯을 계속 실행하지 않으며, 자주 보는 위젯의 일반적인 갱신 예산은 24시간에 약 40~70회로 설명된다. 이는 고정 주기나 보장 횟수가 아니다. [Android 위젯 갱신](https://developer.android.com/develop/ui/views/appwidgets/advanced), [WorkManager 주기 작업](https://developer.android.com/reference/androidx/work/PeriodicWorkRequest.Builder), [Apple 위젯 갱신](https://developer.apple.com/documentation/widgetkit/keeping-a-widget-up-to-date)

iOS 위젯 확장에서 `URLSession`으로 데이터를 읽는 구조는 가능하다. 단, 앱과 확장의 세션 공유, 기기 잠금 상태의 접근, 실행 시간 안에 끝나는 수집을 실기기에서 확인해야 한다. 쿠키는 App Group의 평문 설정에 넣지 않고 제한된 Keychain 접근으로 관리한다. [Apple 위젯 갱신의 네트워크 설명](https://developer.apple.com/documentation/widgetkit/keeping-a-widget-up-to-date), [Keychain 공유](https://developer.apple.com/documentation/security/sharing-access-to-keychain-items-among-a-collection-of-apps)

제품 기본값은 수집 요청 간격 30~60분을 검토하되 실제 수집 시간을 항상 표시하는 것이다. 명단 전체 수집은 훨씬 무거우므로 수집기 검증 후 수동 실행 또는 하루 단위 요청부터 평가한다. 이 간격은 설계 제안이며 서비스의 허용 빈도나 OS의 실행 보장이 아니다.

WebView가 열려 있을 때만 데이터를 읽을 수 있는 SNS도 마지막 결과를 위젯에 표시할 수 있다. 하지만 그 경우 앱을 열지 않아도 새 데이터를 수집하는 요구사항은 충족하지 못한다. 이 차이를 지원 상태와 마지막 갱신 시각으로 드러내야 한다.

## 8. 경쟁 앱과 차별화

“추적 + 위젯 앱이 없다”는 가정은 공개 스토어 정보와 맞지 않는다.

| 앱 | 공개 페이지에서 확인한 내용 | 해석 |
| --- | --- | --- |
| [Follower Tracker Social Widget](https://apps.apple.com/us/app/follower-tracker-social-widget/id6742280064) | Instagram·TikTok 팔로워 수와 홈 화면 위젯, 앱을 열지 않는 갱신을 설명한다. 무료 다운로드와 인앱 구매가 표시된다. | 위젯 중심 제품 자체는 이미 존재한다. |
| [Social Stats Widget: Followers](https://apps.apple.com/ca/app/social-stats-widget-followers/id1533776006) | 여러 SNS의 수치를 위젯으로 보여주는 제품이다. 무료 다운로드와 인앱 구매가 있다. | 여러 플랫폼을 묶는 위젯도 경쟁 범위에 포함된다. |
| [Unfollow Scout](https://play.google.com/store/apps/details?id=com.unfollowscout.app) | 개발자가 WebView 로그인, 기기 내부 분석, 언팔로우·맞팔 분석, 성장 기록과 위젯을 설명한다. 광고와 선택적 구매가 있다. | 로컬 세션 방식의 선행 사례다. 실제 구현·전체 명단 정확성·장기 안정성을 검증한 것은 아니다. |

스토어 등록은 서비스의 수집 허가나 구현 안정성을 증명하지 않는다. 이번 조사에서 경쟁 앱 설치 테스트와 한국 스토어의 설치 가능 여부는 확인하지 않았다.

제품 차별화 가설은 **요청한 기능 전체 무료 제공, 여러 SNS 통합, 기기 내부 세션·이력 보관, 갱신 시각과 데이터 품질의 명확한 표시**다. 이것이 경쟁 앱보다 우수하거나 수요가 충분하다는 결론은 아직 내릴 수 없다.

## 9. 배포 가능성과 운영 위험

배포 판단은 기술 판단과 별개다. Apple의 심사 규칙 5.2.2는 외부 서비스 접근·콘텐츠 표시가 해당 서비스의 조건에 따라 허용되어야 하며 요청 시 권한을 제시해야 한다고 정한다. 위젯·이력 같은 네이티브 기능은 단순 웹사이트 포장보다 앱의 효용을 설명하기에 적합하지만 심사 통과를 보장하지 않는다. [App Store 심사 규칙](https://developer.apple.com/app-store/review/guidelines/)

X는 서면 허가 없는 스크래핑과 기술 제한 우회를 제한한다. Meta도 공식 설명에서 허가 없는 자동 데이터 수집이 조건 위반임을 밝힌다. Reddit의 현재 Responsible Builder Policy는 API 접근에 명시적 승인을 요구하며 수집·상업적 이용의 제한을 설명한다. 따라서 “무료 앱” 또는 “내 계정의 데이터”라는 이유만으로 모든 수집 경로가 허용된다고 판단할 수 없다. [X 서비스 조건](https://x.com/en/tos), [Meta 자동 수집 설명](https://about.fb.com/news/2021/04/how-we-combat-scraping/), [Reddit 정책](https://support.reddithelp.com/hc/en-us/articles/42728983564564-Responsible-Builder-Policy)

Google Play의 User Data 정책은 접근·처리·공유 범위를 공개하고 설명한 목적에 맞게 제한하도록 요구한다. 로컬 처리 앱에서도 세션·관계 명단의 실제 취급 범위를 정확히 안내해야 한다. [Google Play 사용자 데이터 정책](https://support.google.com/googleplay/android-developer/answer/10144311?hl=en)

TikTok 조건 페이지는 이번 웹 조사 도구에서 접근 제한으로 본문을 확인하지 못했고, Instagram 조건 페이지도 로그인 화면이 반환됐다. 이를 해당 수집이 허용된다는 근거로 취급하지 않는다. 배포 전에 각 서비스의 최신 조건과 구체적인 수집 경로를 대조해야 한다. 심사 거절이나 계정 제한이 반드시 발생한다고 단정하는 것도 현재 증거를 넘는 주장이다.

운영 측면에서는 웹 응답·페이지 구조 변경, 로그인 추가 확인, 세션 만료가 핵심 유지보수 요인이다. 서버 비용을 없앨 수 있어도 수집기 유지보수 비용이 없어지는 것은 아니다. 제품 기능은 무료로 설계하며, 별도의 개발·스토어 등록 비용까지 0원이라고 약속하지는 않는다.

## 10. 다음 단계의 의사결정

| 조건 | 결정 |
| --- | --- |
| 정확한 수 + WebView 종료 후 네이티브 수집 + 실기기 위젯 갱신 검증 성공 | 해당 SNS의 추적·위젯 MVP에 포함할 기술 근거가 생긴다. |
| 전체 팔로워·팔로잉 명단, 계정 ID, 페이지 종료와 재확인까지 성공 | 해당 SNS의 언팔로우 추정·맞팔 기능을 확장한다. |
| WebView 전경 수집만 성공 | 전경 기능만 표시한다. 자동 추적 완료로 보고하지 않는다. |
| 수집 경로가 유료 API에 의존하거나 서비스 제한 우회가 필수 | 사용자 요구를 충족하는 지원 경로로 채택하지 않는다. |
| 기술 성공이나 서비스 허용 범위 미확인 | 기술 결과는 기록하되 공개 배포 지원 확정은 보류한다. |

구현 순서·설계·완료 조건은 [구현 계획서](IMPLEMENTATION_PLAN.md)에 정리한다.
