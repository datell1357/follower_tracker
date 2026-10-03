# SNS 공통 데이터·배터리 비용 관리

AI Quota의 현재 Android 수집 코드에서 세션 준비 재사용, 계정별 수집 자원 보관, 확실한 오프라인 상태의 자동 요청 생략, 표시용 데이터와 위젯 갱신 병합, 디버그 계측을 참고했다. AI Quota 저장소는 수정하지 않았다.

## 공통 적용 범위

Instagram·TikTok·X·Facebook·Reddit 모두 같은 갱신 정책과 표시 경로를 사용한다. 최적화는 기존 수집 지원 범위를 늘리거나 모든 SNS의 실제 로그인·백그라운드 수집을 검증했다는 뜻이 아니다.

| 처리 | Android | iOS |
|---|---|---|
| 자동 수집의 오프라인 사전 확인 | 공통 SyncCoordinator에서 활성 네트워크가 아예 없을 때 수·명단 요청 생략 | 공통 SyncService에서 NWPath가 확실히 unsatisfied일 때 생략, 처음의 알 수 없는 상태는 시도 |
| 최신 수치 요청 | 공통 HTTP 요청에 Cache-Control: no-cache. 매번 현재 쿠키를 읽고 본인 ID 검증 | 같은 요청 헤더와 reloadIgnoringLocalCacheData, 현재 Vault 세션 검증 |
| 연결·준비 재사용 | 기존 OkHttp 연결 풀 유지, 저장값이 바뀌면 무효화되는 세션 메타데이터 캐시 | ephemeral URLSession을 프로세스 안에서 재사용, 자동 쿠키 저장·응답 캐시·리디렉션은 계속 차단 |
| 위젯 조회 | 최신 두 수 기록만 조회. 전체 이력과 명단은 유지 | 같은 방식, 선택한 SNS만 조회 |
| 위젯 갱신 | 2초 범위의 연속 요청을 병합. 갱신 중 새 요청도 다음 갱신에 반영 | 프로세스별 2초 병합, BGTask는 작업 종료 전에 갱신 요청을 flush |
| 반복 실패 | 기존 인증·확인·형식 오류 중단, 서버 Retry-After와 일시 오류 1·2·4·8·15분 대기 유지 | 같은 기존 정책 유지 |
| 디버그 계측 | 수·명단 수집의 앱 UID 송수신 바이트 차이와 경과 시간 | HTTP 요청의 수신 본문 바이트와 경과 시간 |

팔로워 수를 캐시해서 새로운 수집으로 기록하지 않는다. 값이 같아도 실제로 새 응답을 확보하면 새 관측을 저장하고 위젯의 수집 시각을 갱신한다. 오프라인 생략은 새 관측·실패 횟수·관측 시각을 만들지 않는다. 수동 갱신은 시도할 수 있지만 서버 대기 시각은 건너뛰지 못한다.

## WebView 수집

현재 Android에서 검증된 프로필 브라우저 수집 경로는 Instagram이다. WebView 보관 구조는 계정·연결 시각·세션 버전·User-Agent·프로필 URL에 묶는다. 빠른 추적 중에만 인스턴스를 보관하고, 매 수집마다 공식 프로필 문서를 다시 요청한다. 문서는 no-cache로 재확인하며 정적 리소스는 WebView의 기본 HTTP 캐시 정책을 사용한다. 수집용 창의 네트워크 이미지는 차단한다. 사용자가 로그인하는 창의 이미지와 동작은 바꾸지 않는다.

수집 후 스크립트를 끄고 about:blank 전환 완료를 기다려 SNS 문서와 실행 작업을 제거한다. 추적 종료·연결 변경·연결 해제·수집 실패·렌더러 종료에서는 보관 자원을 해제한다. 전체 WebView에 영향을 주는 pauseTimers()를 사용하지 않는다. 다음 수집은 이전 DOM이나 팔로워 수를 읽지 않는다.

TikTok은 기존 HTTP 프로필 응답의 JSON을 해석하므로 페이지의 이미지·영상·스크립트를 실행하지 않는다. Reddit은 기존 로그인 사용자 JSON 경로를 유지한다. X·Facebook의 전경 캡처에는 공통 저장·위젯 처리가 적용되며, 미검증 백그라운드 경로를 새로 활성화하지 않는다. 향후 검증된 수집 경로도 공통 정책을 거친다.

AI Quota의 about:blank + JSON 방식은 사이트별 유효한 데이터 경로가 필요하다. Instagram에서 429가 발생했던 HTTP 후보를 새로 자동 탐색하거나 반복 호출하지 않는다. 현재 작동하는 프로필 수집을 유지한다. 모든 사이트에 30분 엔드포인트 대기를 추가하지도 않는다. 현재는 수집 실패 뒤 공통 계정 정책으로 반복을 중단하거나 서버·일시 오류 대기를 지키며, 다른 후보 엔드포인트로 우회하지 않는다.

## 빠른 추적과 측정의 한계

Android 빠른 추적의 1분 간격과 약 6시간 실행 범위는 유지한다. 확실히 오프라인일 때는 연결 복귀 이벤트를 기다리고, 복귀 후 기존 서비스·일시 오류 대기를 지킨다. 이벤트 등록이 실패하면 1분 간격의 확인으로 복구한다. 내용이 같은 알림의 재게시를 생략하며 성공 관측과 앱의 수집 상태는 계속 갱신한다. 서비스 유형과 유료 기능은 변경하지 않는다.

계측 로그에는 SNS 식별자·처리 종류·바이트·시간만 남기며 계정 ID, 사용자명, URL, 쿠키, 응답, 관계 명단을 기록하지 않는다. Android의 미지원 또는 초기화된 바이트 카운터는 unknown으로 표시한다. 앱 UID 값은 동시 작업을 포함하고 다른 UID의 WebView 프로세스 통신까지 모두 나타낸다고 보장할 수 없다. iOS 값은 TLS·요청 헤더를 제외한 본문 크기이며 Android 값과 직접 비교하지 않는다. Release에는 이 계측을 남기지 않는다.

합성 테스트와 에뮬레이터 실행은 배터리 절감률이나 실기기의 24시간 갱신을 증명하지 않는다. 실제 효과는 같은 기기·SNS·연결·절전 조건에서 수집 시간과 데이터, 배터리를 비교해야 한다.

## 2026-10-03 검증

- Android 코어 36개·앱 단위 검사 21개, Debug·Release 빌드와 lint, 테스트 APK 컴파일을 통과했다. 공통 웹 캡처 검사 15개도 통과했다.
- 개인 계정이 없는 별도 API 36 QA 에뮬레이터에서 `ResourceEfficiencyRuntimeTest` 5개와 기존 일시 오류·저장 실패·위젯 실행 검사를 합쳐 24개가 통과했다. 다섯 SNS의 오프라인 보존·수동 갱신·서버 대기, 최신 두 기록과 정확한 0·선택·전체 이력 보존, 세션 캐시 무효화, WebView 재사용 후 새 수치와 빈 페이지 전환을 확인했다.
- 사용자가 연결한 에뮬레이터에는 일반 Debug APK만 업데이트했다. 연결 ID·연결 시각을 보존했고 기존 Instagram 세션으로 새 관측 3회와 WebView 재사용 로그를 확인했다. 관측 완료 간격은 약 56.9초·60.1초였으며 홈 위젯 수집 시각이 20:10에서 20:11로 바뀌었다. 관측된 실제 팔로워 증감이나 배터리 절감률을 주장하지 않는다.
- 로컬 iOS 검사와 실행 검사는 Xcode 라이선스 상태로 미실행이다. iOS의 공통 정책·표시·연결 풀 회귀 검사 3개를 작성했고 프로젝트에 포함했다. CI의 Swift 코어·Debug 테스트 묶음 컴파일·Release 앱과 위젯 빌드 결과는 별도로 확인한다. CI의 컴파일을 iOS 런타임 검사 실행으로 표시하지 않는다.

참고 API: [Android WebSettings](https://developer.android.com/reference/android/webkit/WebSettings#setCacheMode(int)), [WebView pauseTimers](https://developer.android.com/reference/android/webkit/WebView#pauseTimers()), [TrafficStats](https://developer.android.com/reference/android/net/TrafficStats), [Apple ephemeral URLSession](https://developer.apple.com/documentation/foundation/urlsessionconfiguration/ephemeral), [NWPath.Status](https://developer.apple.com/documentation/network/nwpath/status-swift.enum).
