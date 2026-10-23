# YouTube·Facebook 페이지 연결 서비스

2026-10-23 기준 Android와 운영자용 서버 코드다. **일반 사용자는 개발자 계정·API 키·페이지 토큰을 만들거나 입력하지 않는다.** 운영자가 한 번 Google Cloud 프로젝트·Meta 앱·HTTPS 서버를 설정하면, 앱에는 아래 연결 흐름만 제공한다.

- YouTube: **SNS 연결 → YouTube → 채널 링크 입력 → 연결**.
- Facebook 페이지: **SNS 연결 → Facebook 페이지 → 공식 Facebook 로그인 → 관리 페이지 선택**. 인증 callback은 앱 복귀를 요청한다. 브라우저가 자동 복귀를 막으면 돌아가기 버튼을 제공하며, 앱 복귀 후에는 목록을 자동으로 조회하고 페이지를 누르면 첫 수치와 기록·위젯을 연결한다.
- 설정의 메뉴는 **채널·페이지 연결**이다. 기존 키·토큰 입력 화면은 제거했다.

## 구현과 현재 실행 상태

`server/`는 Node.js 24 이상에서 실행하는 HTTPS 역방향 프록시 뒤의 단일 프로세스 서비스다. 별도 npm 의존성 없이 내장 HTTP·crypto·SQLite를 사용한다. Android는 빌드 시 정한 서비스 주소만 사용하고 사용자에게 서버 주소나 비밀정보를 묻지 않는다.

현재 운영 Google Cloud 프로젝트·Meta 앱·공개 HTTPS 서버는 아직 제공되지 않았다. 이 저장소의 기본 APK는 서비스 주소가 비어 있어 **연결 서비스 준비 중**을 표시한다. 이 상태를 공식 API 수집 성공으로 표시하거나 키 입력을 요구하지 않는다. 기존에 연결한 공개 조회·쿠키 계정은 그대로 유지한다. 새 서비스로 검증된 동일 채널/페이지 연결만 `OFFICIAL_API`로 전환하며, 기존 계정 키·그래프 기록·위젯 바인딩을 보존한다. 과거 개발자 입력 방식의 공식 API 연결은 호환 경로로 계속 읽을 수 있지만 신규 입력 UI는 없다.

운영 서버 배포·Google/Meta 설정·실제 동의와 승인 이후의 실계정 E2E 검증은 별도 완료가 필요하다. iOS 연결 서비스 이식은 이번 범위에 포함하지 않는다.

## 운영자가 한 번 준비할 설정

| 환경변수 | 용도 |
| --- | --- |
| `TRACKER_PUBLIC_ORIGIN` | 실제 HTTPS 서비스 origin. 경로 없이 `https://api.example.com` 형식 |
| `TRACKER_STORAGE_KEY` | 비밀 관리 시스템에서 보관하는 32바이트 AES 키의 Base64. DB와 분리해 보관·백업 |
| `TRACKER_DB_PATH` | 영구 저장 디스크의 SQLite 파일. 기본 `./data/tracker.sqlite` |
| `YOUTUBE_API_KEY` | 운영자 Google Cloud 프로젝트에서 발급한 서버용 키 |
| `YOUTUBE_DAILY_BUDGET` | 프로젝트 공유 호출 상한. 기본 9,000. 실제 할당량에 맞춰 지정 |
| `META_APP_ID` / `META_APP_SECRET` | 운영자 Meta 앱 ID / 서버 전용 앱 시크릿 |
| `META_LOGIN_CONFIG_ID` | Facebook Login for Business를 사용하는 앱의 사용자 액세스 토큰 로그인 구성 ID. 표준 Facebook Login에서는 생략 |
| `PORT` | 내부 loopback 포트. 기본 8787 |

비밀값은 채팅·저장소·APK·서버/프록시 접근 로그에 넣지 않는다. `TRACKER_STORAGE_KEY`를 잃거나 바꾸면 이전 토큰을 복호화할 수 없으므로 안정적으로 유지해야 한다. 샘플 비밀값이나 런타임마다 새로 생성하는 기본 키는 없다.

### Google Cloud / YouTube

1. 운영자 계정으로 [Google Cloud Console](https://console.cloud.google.com/)에서 앱용 프로젝트를 만든다.
2. API 라이브러리에서 **YouTube Data API v3**를 활성화한다.
3. 사용자 인증 정보에서 서버용 API 키를 발급한다. API 제한은 YouTube Data API v3로 한정하고, 고정 송신 IP가 있다면 서버 IP 제한도 적용한다. 이전 Android 직접 호출용 키의 Android 앱 제한을 서버 키에 적용하지 않는다.
4. 서버 비밀 저장소의 `YOUTUBE_API_KEY`에 등록한다. 앱 이용자가 키를 발급할 일은 없다.
5. 할당량과 경보를 설정한다. `channels.list`는 1회 1단위이며, 이 프로젝트의 모든 이용자가 할당량을 공유한다. 채널 단위 60초 캐시·동시 요청 합치기·서버 예산 검사로 중복 호출을 줄인다. 이용자 수 증가 시 공식 할당량 증설이 필요할 수 있다.

[channels.list 공식 문서](https://developers.google.com/youtube/v3/docs/channels/list), [프로젝트 설정](https://developers.google.com/youtube/v3/getting-started), [API 키 보호](https://docs.cloud.google.com/docs/authentication/api-keys).

공개 구독자 수는 공식 API도 유효숫자 세 자리로 내림 처리한다. 앱은 1,000명 이상에 `약` 표시를 유지한다. 이 방법은 본인 Studio의 정확한 실시간 수를 얻는 방법이 아니다. [subscriberCount](https://developers.google.com/youtube/v3/docs/channels#statistics.subscriberCount)

### Meta / Facebook 페이지

1. [Meta for Developers](https://developers.facebook.com/apps/)에서 앱을 만들고 Facebook 로그인과 페이지 조회 사용 사례를 구성한다. 앱 ID·앱 시크릿은 운영자가 관리한다.
2. **Facebook Login for Business**를 사용하는 경우 사용자 액세스 토큰 방식의 로그인 구성을 만들고 `META_LOGIN_CONFIG_ID`에 등록한다. 이 구현은 사용자 토큰 → 장기 사용자 토큰 → 페이지 토큰 흐름이다. Business Integration System User 토큰 구성은 이 흐름의 대체로 사용하지 않는다. 구성 ID를 지정하면 OAuth URL에는 `scope` 대신 `config_id`를 보낸다.
3. 필요한 권한은 `pages_show_list`, `pages_read_engagement`다. 게시·메시지·광고 권한은 요청하지 않는다. 로그인 구성에도 같은 권한을 지정한다.
4. 유효한 OAuth 리디렉션 URI에 **`https://실제서비스주소/oauth/facebook/callback`**을 정확히 등록한다. 브라우저 로그인을 마친 뒤 앱 복귀 링크는 `followertracker://facebook/complete`지만 Meta의 인증 콜백은 이 HTTPS 서버 주소다.
5. 앱 설정에 실제 개인정보처리방침 URL과 운영자 정보를 등록한다. 권한 해제 callback은 `/meta/deauthorize`, 사용자 데이터 삭제 callback은 `/meta/delete`다. 두 callback은 앱 시크릿으로 `signed_request`를 검증한 뒤 해당 Facebook 사용자의 서버 페이지 토큰·임시 목록·캐시를 삭제한다.
6. 개발 모드에서는 앱 역할을 가진 계정과 관리 페이지로 검증한다. 일반 이용자에게 공개하려면 해당 권한의 Advanced Access/App Review와 Meta가 요구하는 사업자 확인 등을 완료하고 앱을 Live로 전환한다. 개발 코드가 심사 승인을 대신하지 않는다.
7. `META_APP_ID`·`META_APP_SECRET`을 서버 비밀 저장소에 등록한다. 사용자는 Facebook에 로그인하고 페이지를 선택하기만 한다.

[수동 OAuth 코드 흐름](https://developers.facebook.com/docs/facebook-login/manually-build-a-login-flow/), [Facebook Login for Business](https://developers.facebook.com/docs/facebook-login/facebook-login-for-business/), [페이지 권한·필드](https://developers.facebook.com/docs/graph-api/reference/page/), [장기 액세스 토큰](https://developers.facebook.com/docs/facebook-login/guides/access-tokens/get-long-lived/), [appsecret_proof](https://developers.facebook.com/docs/graph-api/securing-requests/), [데이터 삭제 callback](https://developers.facebook.com/docs/development/create-an-app/app-dashboard/data-deletion-callback/).

서비스가 단기 사용자 토큰을 장기 토큰으로 교환하고 `/me/accounts`에서 관리 가능한 페이지의 토큰을 얻는다. 선택하지 않은 페이지 토큰은 선택 완료 시 버리고, 로그인 완료 전 임시 데이터는 최대 10분 후 삭제한다. 사용자 토큰은 영구 저장하지 않는다. 선택한 페이지 토큰은 장기 유지되지만 비밀번호 변경·권한 철회·Meta 보안 조치 등으로 무효화될 수 있다. 이 경우 실제 재로그인이 필요하며 무한 유지나 강제 자동 갱신을 보장하지 않는다. 일반 공개 페이지 URL만으로 관리 권한이 생기지는 않는다.

## 서버 실행과 Android 빌드

```sh
# 운영자가 비밀 환경변수를 안전하게 주입한 뒤
cd server
node --test test/*.test.mjs
node src/main.mjs
```

서버는 `127.0.0.1:8787`에만 바인딩한다. 앞단 프록시에서 유효한 TLS 인증서로 HTTPS를 제공한다. 인증 callback의 query·Cookie·Authorization과 upstream URL을 접근 로그에 기록하지 않는다. 인터넷에 공개하기 전 프록시에 등록·로그인 요청 속도 제한을 적용한다. 서버는 실제 socket IP 기준 제한만 사용하고 클라이언트가 만든 `X-Forwarded-For`를 신뢰하지 않는다. 프록시 뒤에서는 이 한도가 전체 이용자에게 합산되므로 앞단 IP별 제한과 서비스 용량을 함께 설정한다.

```sh
cd android
./gradlew :app:assembleDebug -PtrackerServiceUrl=https://api.example.com
# 또는 빌드 환경변수 TRACKER_SERVICE_URL 사용
```

APK에는 서비스의 공개 origin만 포함된다. HTTPS가 아니거나 경로·인증정보를 포함한 주소는 거부한다. 연결 서비스는 익명 설치 단위 자격증명을 발급한다. 이 자격증명은 Android Keystore로 암호화하며, Facebook 페이지 접근은 그 설치에 소속된 grant만 허용한다. 다른 기기·새 설치는 다시 Facebook 동의를 받아야 한다. 사용자 이메일·앱 자체 회원가입은 필요 없다.

현재 서버는 단일 프로세스·영구 SQLite 디스크 기준이다. 여러 인스턴스 배포는 공유 DB·분산 요청 합치기·속도 제한 저장소로 이전한 뒤 해야 한다. 설치 토큰은 앱 무결성 증명이 아니므로 공개 대규모 서비스에는 운영 환경의 앱 검증/어뷰징 방어와 할당량 관리가 추가로 필요하다. 서버 자동 배포·유료 리소스 생성은 수행하지 않는다.

## 수집·보호 계약

- YouTube: 서버가 입력 URL을 공식 채널 ID/handle로 검증한 뒤 `channels.list`의 ID·이름·구독자 통계만 받는다. 임의 URL을 서버가 열지 않는다.
- Facebook: OAuth state와 브라우저 쿠키, 설치 소유자, 10분 유효기간을 검증한다. 앱 복귀 링크에는 인증 코드·토큰을 싣지 않는다. 앱은 보관한 로그인 ID로 자동 확인한다.
- 모든 Graph API 요청에 `appsecret_proof`를 붙인다. 페이지 목록은 공식 `me/accounts`와 검증한 cursor로 끝까지 읽으며 upstream의 임의 paging URL을 따라가지 않는다.
- 서버 SQLite는 AES-256-GCM으로 값들을 암호화한다. 페이지 토큰과 장기 사용자 토큰·키를 모바일 응답에 반환하지 않는다.
- 새 페이지는 선택한 ID와 공식 응답의 ID가 일치해야 저장한다. 선택 응답 유실/앱 재시작 후에는 같은 선택을 재조회해 복구한다.
- 60초 이내 캐시 결과는 **원래 수집 시각**을 반환한다. 실패한 응답이나 오래된 캐시를 새 관측으로 만들지 않는다. 오래된 서버 수치 캐시는 하루 뒤 정리한다.
- 일반 15분·빠른 추적 간격과 위젯 저장 경로는 유지한다. 요청 제한은 서버와 앱 모두 대기하고 HTML 수집으로 즉시 우회하지 않는다.
- 로그아웃은 로컬 수집을 즉시 멈추고 서버 토큰 삭제를 요청한다. 오프라인 삭제 요청은 암호화한 큐에 남겨 다음 온라인 앱 복귀/새 Facebook 연결 전에 재전송한다. 서비스는 앱 요청 없이 자체 주기 수집을 하지 않는다.
- 같은 기기의 과거 수집 이력은 로그아웃 후에도 유지한다. 연결 해제는 기존 앱 정책대로 이력을 삭제한다.

## 검증 방법과 제한

서버 검사는 실제 HTTP handler, OAuth 상태·브라우저·소유권, 코드 재사용 거부, 페이지 선택, 장기 토큰 교환 요청, 페이지 목록 pagination, 암호화 재시작, 캐시 시각·호출량, 요청 제한, 취소·로그아웃·권한 삭제와 늦은 응답을 합성 공식 응답으로 검증한다. Android 단위 검사는 서비스 주소 고정·자격증명 재생성·로그인 복원·페이지 검증·삭제 큐·취소를 검사한다.

`ManagedApiRuntimeTest`는 별도 `dev.datell.followertracker.managedvalidation` 앱에서만 실행한다. 실제 Room·Keystore·수집기·위젯 조회 경로와 YouTube 링크 입력/Facebook 페이지 선택 UI를 검사한다. 개인 계정·쿠키·위젯 데이터가 있는 원래 앱에는 합성 값을 넣지 않는다.

운영 계정의 실제 OAuth 동의, Google/Meta 실 API 호출, 배포한 서버의 운영 안정성, 24시간 수집, 배터리·데이터 감소량 측정은 아직 미실행이다. 코드 검사나 합성 응답의 성공을 이 결과로 대신하지 않는다.

### 이번 변경의 확인 결과

- 서버 Node 회귀 17개: 통과. 실제 HTTP handler와 합성 공식 응답 사용.
- Android core 62개, app Debug/Release 각 159개: 통과, 실패·skip 없음.
- Debug·Release APK 빌드와 양쪽 lint: 통과. Lint 오류 0, 경고 23개이며 경고 없는 빌드는 아니다.
- 분리 검증 앱의 Room·Keystore·반복 수집·입력/페이지 선택 UI 4개: 통과. 개인 계정 데이터와 분리했다.
- 기존 주력 앱의 새 화면은 서비스 미설정 상태로 제공하며, 실제 Google·Meta 연결 성공을 표시하지 않는다.
