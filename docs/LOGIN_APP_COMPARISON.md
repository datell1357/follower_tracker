# Android 로그인 비교와 수정

2026-10-01에 배포된 APK를 확보해 패키지·서명을 확인하고 전용 `FollowerTracker_Widget_QA_API_36` 독립 에뮬레이터에 설치했다. 사용자의 데스크톱 포커스를 바꾸는 UI 조작은 중단했으며, 이후 검사는 Android 앱의 instrumentation과 읽기 전용 SDK 진단으로 진행한다. 경쟁 앱에 사용자의 SNS 자격증명이나 기존 세션을 전달하지 않았다.

## 분석한 배포본

| 앱 | 확인한 배포본 | 원본 base APK SHA-256 | 서명 인증서 SHA-256 |
| --- | --- | --- | --- |
| [FollowMeter](https://play.google.com/store/apps/details?id=com.beakerapps.instameter2) | `com.beakerapps.instameter2`, 7.0.1 / 96, arm64 | `5b95b593f3d778aecf251a912ea9f9bc2fd9203c15bb516a6e1ba48dfb4388c7` | `edec4da8439c93d4d094cb1bb8892b6160996b9fc46c1bc375e208b2844f983a` |
| [Followers & Unfollowers](https://play.google.com/store/apps/details?id=get.instagram.followers.unfollowers) | `get.instagram.followers.unfollowers`, 9.2 / 92, arm64 | `673462a47ff28b9f6b83aeac4cb3da6343fc58bfa72ef09534bdeb6e76311f10` | `49f1e1e0af3b5dc34a2ae473e6eeacd4ce75518773388c0e5da98e71d7c80d36` |

APKPure의 [FollowMeter 배포 페이지](https://apkpure.net/followmeter-for-instagram/com.beakerapps.instameter2/download)와 [Followers & Unfollowers 배포 페이지](https://apkpure.net/followers-unfollowers/get.instagram.followers.unfollowers/download)에서 원본 XAPK와 arm64 분할 패키지를 받았다. Android `apksigner`가 두 base APK의 서명을 검증했으며 Google source stamp도 확인했다. 인증서 정보는 배포 사이트의 표시와 일치한다. Play 스토어에서 직접 내려받은 파일과의 바이트 동일성까지 검증한 것은 아니다.

원본 파일과 JADX 1.5.6 출력은 Git에서 제외된 `.local/research/follower-apps/`에 보관한다. JADX는 공식 릴리스의 SHA-256을 확인했다. 타사 코드·리소스를 앱 소스로 복사하지 않았다.

## APK에서 확인한 구조

| 항목 | FollowMeter 7.0.1 | Followers & Unfollowers 9.2 |
| --- | --- | --- |
| 화면 기술 | Flutter, `flutter_inappwebview` Android 브리지 | Java Activity와 Android WebView |
| 공식 로그인 | Instagram 로그인 URL과 WebView·CookieManager 관련 코드/문자열 | `LoginActivity`가 `https://www.instagram.com/accounts/login/` 로드 |
| 쿠키 | 쿠키로 로그인 종료를 판단하는 함수와 관련 문자열 존재 | 교차 사이트 쿠키 허용. 페이지 완료 후 `sessionid`와 `ds_user_id` 검사 |
| 추가 인증 | challenge URL, 재로그인 안내, Instagram SSO 요청 문자열 존재 | challenge 페이지에서는 완료 처리를 보류하는 분기 존재 |
| 데이터 요청 | WebView 안의 `fetch(...credentials:'include')` 및 네이티브 처리 관련 문자열 | `i.instagram.com` 프로필·명단 요청, 웹 프로필·GraphQL 대체 경로, Room DB |

FollowMeter는 Flutter AOT 코드가 `libapp.so`에 있다. JADX로 Java 브리지와 Manifest·리소스를 읽었고, 네이티브 문자열로 로그인 관련 경로를 추가 확인했다. Dart의 전체 제어 흐름을 복원한 것은 아니다. JADX 출력에 각각 2,176개·51개 복원 오류가 있어 모든 클래스가 원본과 같은 소스로 복원됐다고 설명하지 않는다. 위 표는 실제 읽힌 부분만 적었다.

Followers & Unfollowers에서는 공개 계정의 프로필 등록 이후 사용자 ID·사용자명·쿠키를 JSON으로 구성하고 쿠키 업로드용 URL에 HTTP POST하는 코드 경로도 확인했다. URL 반환부는 네이티브 함수다. 이 경로가 실제로 실행되는지, 어느 서버로 전송되는지는 사용자 세션으로 시험하지 않았다. 이 앱을 순수한 기기 내부 수집의 검증 사례로 사용할 수는 없다.

두 앱의 공식 설명과 APK 존재는 현재 모든 계정에서 로그인이 성공한다는 증거가 아니다. 이번 비교에서는 경쟁 앱의 실제 계정 로그인·명단 수집을 실행하지 않았다.

## 우리 앱에 적용한 변경

- 로그인 WebView에서 교차 사이트 쿠키를 허용한다. 로그인 창을 열 때 다른 SNS 쿠키를 지우지 않는다.
- 로그인 이동의 허용 범위를 데이터 수집 목적지와 분리했다. Instagram의 Facebook 로그인과 X의 Twitter 로그인 주소를 허용하되, 수집 쿠키를 보내는 호스트는 기존 SNS 경계를 유지한다.
- Google·Apple 외부 로그인 이동에는 지원 제한과 아이디·비밀번호·이메일 방식 안내를 표시한다. 외부 브라우저의 쿠키를 앱이 공유받는 기능은 구현하지 않았다.
- 로그인 세션의 존재 여부를 창이 열린 동안 확인해 안내한다. 세션이 없으면 연결 확인 전에 로그인 완료를 요청한다. 이 안내는 팔로워 수 수집 성공을 의미하지 않는다.
- 페이지 새로고침, 주 페이지 HTTP/네트워크 오류 코드, 로딩 종료, 키보드 여백을 추가했다.
- Android 로그인 창의 실제 `FLAG_SECURE` 적용을 Compose `securePolicy`로 지정한다. 웹 디버깅·콘솔 출력은 계속 비활성화한다.
- Instagram 네이티브 읽기 요청에 웹 출처·참조 헤더를 추가한다. 타사 서버, 쿠키 업로드, 유료 API는 추가하지 않았다.

WebView 교차 사이트 쿠키는 [Android CookieManager API](https://developer.android.com/reference/android/webkit/CookieManager#setAcceptThirdPartyCookies(android.webkit.WebView,%20boolean))의 로그인 창 단위 설정이다. 일반 브라우저나 운영체제의 개인정보 설정을 바꾸지 않는다.

## 검증 범위

APK 설치, 코드 분석과 로그인 화면 진입 검사는 계정 인증 성공과 구분한다. `LoginRuntimeTest.recordOfficialLoginPageAvailability`는 실제 공식 페이지의 호스트·입력 요소 개수·로그인 문구 여부만 로컬 JSON에 기록한다. 비밀번호·쿠키 값·사용자명·DOM 원문은 기록하지 않는다. 이 진단이 통과해도 로그인 제출, 2단계 인증, 쿠키를 이용한 팔로워 수 수집과 백그라운드 갱신은 별도 검사해야 한다.

이번 수정은 Android에 적용했다. iOS 로그인 코드는 이 변경에서 수정하거나 다시 검증하지 않았다.

공식 페이지 진단에서 Reddit은 계정 인증 전에도 세션 후보 쿠키를 발급했다. 따라서 쿠키의 존재만으로 로그인 성공을 표시하지 않도록 안내를 구체화했다. 실제 연결은 기존처럼 자기 계정 응답과 ID를 검증한 뒤 완료한다.

## 2026-10-02 화면 표시·입력 재검증

앞선 입력 요소 개수 진단은 실제 표시와 터치 가능 여부를 확인하지 않아 충분하지 않았다. Instagram 페이지에는 두 입력란이 존재했지만 문서와 여러 상위 영역의 높이가 0으로 계산돼 하얀 화면으로 남았다. 확대율이나 로딩 시점 변경으로는 해결되지 않았다.

AndroidView 안의 WebView에 `MATCH_PARENT` 레이아웃 파라미터를 명시했다. 백분율 높이와 잘림을 사용하는 로컬 폼은 수정 전 `709px` 화면에서 높이 `0px`로 실패했고, 수정 후 높이·그리기·터치 포커스 검사가 통과했다. 공식 Instagram 페이지의 문서 높이는 약 `713px`이며 두 입력란의 중심점에서 실제 입력란이 터치 대상으로 확인됐다. Facebook·Reddit도 두 입력란, X는 현재 화면의 한 입력란이 터치 대상으로 확인됐다. TikTok의 첫 화면은 로그인 방식 선택 화면이므로 입력란 수 0을 로그인 실패로 해석하지 않는다.

시험 환경은 별도 API 36 AVD와 WebView 133.0.6943.137이다. Apple Silicon 에뮬레이터의 시스템·그래픽 불안정은 Vulkan을 끈 실행으로 완화했고 기존 AVD 데이터는 보존했다. 앱의 공식 로그인 주소·TLS 검증·화면 캡처 보호·쿠키 경계는 유지한다. 실제 계정의 로그인 제출·추가 인증·쿠키를 이용한 팔로워 수 수집은 아직 별도 확인이 필요하다.
