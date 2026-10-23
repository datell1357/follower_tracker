# Android 공식 API 수집과 발급 방법

작성·공식 문서 확인: 2026-10-23, Asia/Seoul.

## 이번 구현

Android의 **설정 → 공식 API 연결**에서 YouTube 채널과 본인이 관리하는 Facebook 페이지를 연결한다. 기존 계정·페이지가 있으면 같은 ID를 검증한 뒤 수집 경로만 전환하므로 수집 이력과 홈 위젯의 연결 키를 유지한다. API 키나 페이지 토큰은 검증에 성공한 뒤 기기에서 암호화해 저장한다.

현재 개발자가 키·페이지 토큰을 발급해 입력하는 방식이다. 일반 사용자에게 Meta 로그인과 페이지 선택을 제공하는 OAuth/SDK 온보딩, Meta 앱 심사, 서버에서의 토큰 교환은 이번 구현에 포함하지 않는다. 개인 Facebook 계정과 다른 SNS의 기존 수집기는 유지한다. iOS에는 이 API 수집 경로를 추가하지 않았다.

API를 연결하기 전 기존 공개 조회 연결은 유지된다. API 수집으로 전환한 연결은 키 누락·만료·권한 부족·요청 제한 때 웹페이지로 자동 우회하지 않는다. 실패 시 기존 값·실제 관측 시각을 유지한다. 키가 없는 상태를 API 수집 성공으로 표시하지 않는다.

| 구분 | YouTube | Facebook 페이지 |
| --- | --- | --- |
| API | YouTube Data API v3 `channels.list` | Graph API v26.0 Page |
| 최초 연결 | 채널 링크 + API 키 | 페이지 액세스 토큰, `/me`로 대상 확인 |
| 반복 요청 | 저장된 채널 ID, `part=statistics` | 저장된 페이지 ID |
| 필드 | `id`, `subscriberCount`, `hiddenSubscriberCount` | `id`, `name`, `followers_count` |
| 초기 이름 조회 | 처음만 `snippet/title` | 같은 JSON 응답에 포함 |
| 정밀도 | 1,000명 이상은 `약` 표시 | API가 반환한 정수, 실시간성은 보장하지 않음 |
| 로그인 쿠키/WebView | 사용하지 않음 | 사용하지 않음 |

YouTube는 최초 핸들을 공식 API로 해석하고 반복 요청에서는 안정적인 채널 ID를 사용한다. `search.list`, 영상 목록·썸네일·HTML·JavaScript를 요청하지 않는다. Facebook은 `fan_count`를 팔로워 수로 대체하지 않으며 사용자 토큰이나 다른 페이지의 토큰으로 기존 페이지를 덮어쓰지 않는다.

## 1. YouTube 키 발급

1. [Google Cloud Console](https://console.cloud.google.com/)에서 이 앱용 프로젝트를 생성하거나 선택한다.
2. **API 및 서비스 → 라이브러리**에서 **YouTube Data API v3**를 활성화한다.
3. **사용자 인증 정보 → 사용자 인증 정보 만들기 → API 키**에서 키를 만든다.
4. 키의 **API 제한**을 YouTube Data API v3로 지정한다. **애플리케이션 제한**은 Android 앱으로 지정하고 패키지 `dev.datell.followertracker`와 실제 설치 APK의 서명 인증서 SHA-1을 등록한다. 디버그 서명과 배포 서명은 다르므로 각각 해당 키의 허용 목록에 넣거나 별도 키를 사용한다. 앱은 `X-Android-Package`·`X-Android-Cert` 헤더를 전송한다.
5. 앱의 **설정 → 공식 API 연결 → YouTube → 공식 API로 연결**에 채널 링크와 키를 입력한다. 이미 연결된 채널 링크는 채워져 있다. **확인하고 연결**을 누른다.
6. 카드가 **공식 API 사용 중**으로 바뀌고 새 수집 시각이 저장되는지 확인한다. 연결 확인·키 변경 때는 빈 키 입력을 저장된 키 재사용으로 처리한다.

APK의 공개 서명 인증서 지문은 Android SDK의 `apksigner verify --print-certs <APK 경로>`로 확인할 수 있다. `Signer #1 certificate SHA-1 digest`가 필요한 값이다. 비공개 서명 키나 keystore 파일을 업로드할 필요가 없다. 현재 디버그 APK가 `.apivalidation` 같은 별도 검증 패키지라면 그 패키지는 본 앱과 다르다. 실제 사용자 앱에는 `dev.datell.followertracker`를 등록한다.

공식 문서상 `channels.list`는 호출당 1 단위다. 채널 1개를 매분 24시간 성공적으로 조회하면 1,440회, 15분 간격이면 96회다. 초기 연결·수동 갱신·다른 설치 기기의 사용량은 별도이며 같은 프로젝트의 한도를 공유한다. 기본 일일 할당과 실제 사용량은 Google Cloud에서 확인한다. 한도 초과 응답은 미국 태평양 시간의 다음 날짜 시작까지 대기하며 서버가 더 긴 `Retry-After`를 주면 그 대기를 따른다.

공식 API도 `subscriberCount`를 유효숫자 세 자리로 내림한다. API로 전환해도 큰 채널의 정확한 1명 단위 수치를 제공하지 않는다. 구독자 수 비공개·누락·다른 채널 ID·잘못된 숫자는 수집 실패다.

근거: [시작하기·부분 응답](https://developers.google.com/youtube/v3/getting-started), [channels.list](https://developers.google.com/youtube/v3/docs/channels/list), [구독자 수 정의](https://developers.google.com/youtube/v3/docs/channels#statistics.subscriberCount), [Android API 키 제한](https://docs.cloud.google.com/docs/authentication/api-keys).

## 2. Facebook 페이지 토큰 발급

현재 앱의 API 연결은 **본인이 관리하고 조회 권한을 부여할 수 있는 페이지**를 대상으로 한다. 페이지 URL만 안다고 공식 API 권한이 생기지 않는다. 관리하지 않는 공개 페이지는 Meta의 Page Public Metadata Access/Content Access 기능과 심사 조건이 별도이며 이 앱의 페이지 토큰 입력 흐름에서는 지원하지 않는다.

1. [Meta for Developers](https://developers.facebook.com/apps/)에서 앱을 만들고 페이지 조회와 Facebook 로그인을 사용할 수 있는 용도로 구성한다. Meta가 요구하는 개발자 계정·앱 역할·비즈니스 확인은 콘솔 안내에 따라 진행한다.
2. 개발 테스트에는 앱 역할이 있는 계정과 그 계정이 관리하는 페이지를 사용한다. `pages_show_list`는 접근 가능한 페이지를 나열할 때, `pages_read_engagement`는 페이지 정보를 읽을 때 사용한다. 실제 Page API 권한·작업 권한 조건은 아래 공식 Page 문서를 따른다. 글쓰기·광고 관리 권한을 이 수집 때문에 추가하지 않는다.
3. [Graph API Explorer](https://developers.facebook.com/tools/explorer/)에서 만든 앱을 선택하고 필요한 조회 권한을 부여해 사용자 액세스 토큰을 얻는다. 해당 페이지에 대한 접근 허용을 확인한다.
4. Explorer의 페이지 토큰 선택 기능을 사용하거나 `GET /me/accounts?fields=id,name,access_token`을 호출한다. 연결할 페이지 항목의 **페이지 액세스 토큰**을 선택한다. 이 응답에는 비밀 토큰이 있으므로 캡처·로그·채팅·저장소에 붙이지 않는다.
5. 앱의 **설정 → 공식 API 연결**에서 기존 Facebook 페이지의 **공식 API로 연결**을 누른다. 새 페이지라면 **Facebook 페이지 API 연결 추가**를 누른다. 페이지 토큰을 입력하고 연결한다.
6. 앱은 `GET /v26.0/me?fields=id,name,followers_count`로 토큰이 가리키는 페이지와 수치를 검증한다. 기존 페이지를 전환할 때는 저장된 페이지 ID가 일치해야 한다. 이후 요청은 `/v26.0/{page-id}`를 사용한다.

앱 ID·앱 시크릿·클라이언트 토큰·사용자 액세스 토큰을 페이지 토큰 입력란에 넣지 않는다. 앱은 앱 시크릿을 요구하거나 APK에 포함하지 않는다. 서버 설정에서 `appsecret_proof`를 강제한 앱은 별도의 서버 중계 구현 없이 이 직접 호출을 사용할 수 없다.

짧은 수명의 테스트 토큰은 장기 수집에 적합하지 않다. [장기 페이지 토큰 발급](https://developers.facebook.com/docs/facebook-login/guides/access-tokens/get-long-lived/) 절차에 따라 장기 사용자 토큰에서 페이지 토큰을 발급한다. 앱 시크릿을 쓰는 교환은 신뢰할 수 있는 서버에서 수행하며 모바일 앱에 시크릿을 넣지 않는다. 장기 페이지 토큰에 고정 만료일이 없더라도 권한 철회·계정/보안 변경 등으로 무효화될 수 있다. [Access Token Debugger](https://developers.facebook.com/tools/debug/accesstoken/)에서 대상 앱·페이지·권한·만료·데이터 접근 기한을 확인하고, 만료 응답이 오면 앱에서 토큰을 교체한다. 앱은 토큰을 임의로 영구화하거나 만료를 숨기지 않는다.

일반 사용자에게 배포할 때는 해당 권한의 고급 액세스·App Review 및 필요한 비즈니스 확인, 공식 로그인/페이지 선택 UI를 마련해야 한다. 현재 키·토큰 입력은 개발자가 자기 채널·페이지를 테스트하기 위한 연결 방식이다.

근거: [Page 필드·접근 조건](https://developers.facebook.com/docs/graph-api/reference/page/), [페이지 시작하기](https://developers.facebook.com/docs/pages-api/getting-started/), [액세스 토큰](https://developers.facebook.com/docs/facebook-login/guides/access-tokens/), [Graph API 버전](https://developers.facebook.com/docs/graph-api/changelog/versions/), [권한 참조](https://developers.facebook.com/docs/permissions/).

## 수집·보안·호환성

- HTTP 연결을 재사용하고 응답 필드를 제한한다. OkHttp의 기본 gzip 처리, 20초 요청 제한, 최대 64KiB 응답을 적용한다. 응답을 재사용해 새 관측으로 만들지 않는다. 실제 절감률은 측정 전이다.
- 쿠키 저장소·응답 캐시·인증 리다이렉트·연결 실패 자동 재요청을 사용하지 않는다. 키/토큰을 다른 호스트로 전달하지 않는다. Facebook 토큰은 Authorization 헤더로만 보낸다.
- 키·토큰은 기존 Android Keystore AES-GCM 암호화로 앱 전용 설정에 저장한다. 백업·기기 전송 제외를 유지한다. 입력 화면은 화면 캡처를 막고 입력값을 saved instance state에 저장하지 않는다.
- 디버그 로그는 고정된 `OFFICIAL_API` 단계·HTTP 상태·수집 결과만 기록한다. 요청 URL·키·토큰·응답 본문·서버 오류 메시지를 기록하지 않는다.
- 키/토큰 교체·로그아웃·연결 해제는 진행 중인 이전 응답의 저장을 차단한다. 로그아웃은 해당 SNS API 인증 정보를 제거한다. 페이지 하나 연결 해제는 다른 페이지 토큰을 보존한다.
- API 연결은 기존 계정 JSON에 `countTransport=OFFICIAL_API`를 추가한다. 기존 연결은 그대로 읽히며 Room 테이블 스키마는 바뀌지 않는다. 새 값을 모르는 이전 APK로의 다운그레이드는 지원하지 않는다.
- 1분 빠른 추적·15분 일반 예약·OS의 백그라운드 제약은 유지한다. 공식 API가 수집 실행 간격이나 외부 서비스 가용성을 보장하지는 않는다.

## 검증

- Android core 62개, app Debug/Release 각각 143개 단위 검사 통과. Debug·검사 APK·Release/R8 빌드와 Debug/Release lint 통과. 의존성 추가·lockfile 변경은 없다.
- core 단위 검사: 정밀도·0·비공개/누락·대상 ID·계정 JSON 호환·쿼터 초기화 시각·권한/만료/제한 오류.
- Android 단위 검사: 필드 제한·토큰 전달 위치·리다이렉트 거부·쿠키 미사용·응답 크기·취소·키 보관·교체/삭제 시 저장 차단.
- 전용 `dev.datell.followertracker.apivalidation` 에뮬레이터 앱에서 합성 API 응답으로 5개 런타임 검사 통과: 반복 수집, 기존 기록/위젯 ID 유지, 백그라운드 저장, 인증/쿼터 실패 시 마지막 값 보존, 로그아웃/토큰 교체 응답 차단, 실제 Keystore 보관과 재생성.
- 기존 앱을 데이터 삭제 없이 업데이트하고, 실제 에뮬레이터에서 연결된 채널·페이지와 새 공식 API 설정 화면을 확인했다. 자격 증명이 없는 두 연결은 `API 연결 준비` 상태로 표시하며 기존 수집 경로를 유지한다. 개인 계정 앱에서 합성 토큰·응답 검사를 실행하지 않았다.
- 실제 YouTube 키·Meta 페이지 토큰은 아직 발급되지 않았다. 실제 API 호출·24시간 반복·배터리/데이터 절감률·Meta 권한 심사 결과는 미검증이다. 합성 검사를 실제 SNS 호출 성공으로 취급하지 않는다.
