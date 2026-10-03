# Android WebView 연동 로그인

2026-10-03에 AI Quota의 현재 Android 로그인 구현을 읽고 팔로워 트래커의 공통 SNS 로그인 창에 적용했다. 참조한 파일은 `ProviderWebViewUserAgent.kt`, `ProviderLoginWebViewPolicy.kt`, `WebLoginActivity.kt`이며 참조 저장소의 HEAD는 `c0ec759d1d7d2645e010ced26f929a2c7ee4b4f9`다. AI Quota의 소스·사용자 변경·설치 상태는 수정하지 않았다.

## 로그인 창

- 현재 설치된 WebView의 기본 User-Agent에서 `; wv`와 `Version/4.0`만 조정한다. 기기·OS·Chrome 엔진 버전을 고정하거나 데스크톱 기기로 바꾸지 않는다.
- Google의 `accounts.google.com`과 Apple의 `appleid.apple.com`을 HTTPS 인증 이동으로 허용한다. 사용자 정보가 들어간 URL·비표준 포트·유사 호스트·다른 스킴은 차단한다.
- JavaScript 인증 팝업을 별도 WebView로 만든다. 부모와 팝업은 같은 CookieManager와 로그인 설정을 사용하며 `window.opener`·`postMessage`·`window.close` 동작을 유지한다. 동시 팝업은 최대 3개다.
- 현재 보이는 창에서 자동 연결을 검사한다. 팝업을 닫으면 원래 창으로 돌아가고, 로그인 창을 닫으면 부모·팝업을 모두 해제한다. 렌더러 종료 후에는 새로고침으로 로그인 창을 다시 만든다.
- 주 문서의 초기 팝업 요청과 POST 이동도 허용 주소를 검사한다. TLS 오류는 취소하고 파일·콘텐츠 접근, 웹 디버깅과 콘솔 원문 출력은 계속 차단한다. 로그인 창의 화면 캡처 보호는 유지한다.

## 데이터 수집 경계

인증 페이지의 허용은 데이터 수집 호스트의 확장이 아니다. `Provider.allows`, `SessionStore.header`, 본인 ID 확인과 정확한 수치 확인은 그대로 사용한다. Google·Apple 페이지에서는 자동 연결이나 팔로워 캡처를 시작하지 않는다. 캡처 도중 인증 페이지로 이동한 경우 SNS 캡처의 정리 스크립트도 실행하지 않는다.

쿠키가 존재하는 것만으로 연결을 저장하지 않는다. 사용자가 SNS로 돌아오고 로그인한 본인 계정과 수치가 확인되면 기존 연결·암호화 저장·위젯 갱신 경로로 연결을 완료한다. Google의 인증 URL, 폼 입력값, 비밀번호, 쿠키 원문은 진단 파일·로그·fixture·커밋에 저장하지 않는다.

## 검증 해석

합성 HTTPS 페이지와 별도 QA 에뮬레이터로 현재 엔진 버전 보존, 인증 호스트와 수집 호스트 분리, 팝업의 설정·opener·복귀·해제, Google 페이지의 자동 연결 차단과 SNS 본인 페이지의 자동 연결, 기존 화면 표시·터치·요청 제한·본인 ID 검사를 수행한다. HTTPS fixture는 실제 로그인 WebViewClient의 허용 주소 검사를 거치고 다른 네트워크 요청은 모두 차단한다.

실제 사이트의 Google 버튼과 인증 페이지 진입은 별도로 확인한다. 인증 화면을 연 것과 실제 사용자가 자격증명·추가 인증을 완료하고 SNS 세션 및 팔로워 수를 확보한 것은 구분한다. 이 변경은 Android 로그인 창에 적용하며 iOS의 인증 동작을 검증한 결과로 표시하지 않는다.

### 2026-10-03 관측 결과

Android 16 / API 36, WebView `133.0.6943.137`의 별도 QA 에뮬레이터에서 확인했다. 실제 Google 계정의 자격증명은 입력하지 않았다.

| 서비스 | 실제 페이지 진입 | 확인 범위 |
| --- | --- | --- |
| X | Google 버튼 → `accounts.google.com` | 이메일 입력 필드, 부모 창 연결, 해당 단계의 차단 메시지 없음 |
| Reddit | Google 버튼 → `accounts.google.com` | 이메일 입력 필드, 부모 창 연결, 해당 단계의 차단 메시지 없음 |
| TikTok | 앱 설치 안내의 `Not now` → Google 버튼 → `accounts.google.com` | ADB 터치와 접근성 노드로 이메일 입력 필드 및 해당 단계의 차단 메시지 없음 확인 |

네트워크 진단 자동 검사에서는 TikTok 설치 안내 때문에 Google 버튼 검색이 시간 초과됐다. 이후 같은 APK에서 안내를 닫고 Google 버튼을 직접 눌러 진입을 확인했다. 진단 실행 자체의 성공을 서비스 로그인 성공으로 계산하지 않는다. Instagram·Facebook의 Google 로그인 경로는 이 검사의 대상이 아니며, 두 서비스에도 공통 로그인 창 변경은 적용된다.

- Android core 36개·app 23개 단위 검사 및 공통 캡처 검사 15개 통과.
- 로그인·자동 연결 런타임 검사 9개 통과. 합성 Google 팝업에서 SNS 본인 페이지로 이동한 뒤 자동 연결, `opener.postMessage`, JavaScript `window.close`를 함께 확인했다.
- Debug·Release 빌드, Debug·Release lint 및 테스트 APK 빌드 통과.
- 사용자 테스트 에뮬레이터는 데이터를 지우지 않고 업데이트했다. 업데이트 전후 기존 연결·암호화 세션 메타데이터·위젯 설정·수집 이력이 유지됐고, 빠른 수집을 다시 시작한 뒤 새 관측이 저장되는 것을 확인했다.

AI Quota의 호환 설정을 적용해도 Google의 공식 WebView OAuth 지원을 의미하지 않는다. [Google OAuth 정책](https://developers.google.com/identity/protocols/oauth2/policies)은 개발자가 제어하는 embedded user-agent를 허용하지 않는다. 사이트·WebView 버전·계정 조건에 따라 거절될 수 있으며, 실제 관측 범위만 지원 검증으로 기록한다.
