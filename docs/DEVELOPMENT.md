# 개발과 검증

## 환경

- Android: SDK 36, JDK 21, Gradle 8.13, AGP 8.13.2, Kotlin 2.2.21.
- Android 화면: Compose BOM 2025.10.01. SDK 36·AGP 8.13.2와 호환되는 AAR 메타데이터를 확인했다. BOM 2026.09.00은 SDK 37·AGP 9.1을 요구해 현재 구성과 맞지 않았다.
- iOS: Xcode 16+의 Swift 6 도구, 앱·위젯은 iOS 17+ 대상. 로컬 빌드는 Xcode 26.6에서 확인한다. 핵심 패키지는 macOS 14+에서도 검사한다.
- WebView 공통 캡처 테스트: Node.js 22+.

Gradle Wrapper는 공식 배포 SHA-256을 검증한다. 버전 카탈로그와 dependency lockfile을 함께 관리한다. Kotlin 컴파일은 Gradle 프로세스 안에서 실행해 한글 경로의 대체 프로세스 인자 처리와 별도 데몬의 캐시 접근 문제를 피한다.

Android 앱의 `:core` 프로젝트 의존성에는 `LibraryElements.JAR` 속성을 지정한다. macOS 한글 경로에서 AGP의 클래스 디렉터리 dex 변환이 정규화된 클래스 경로와 원래 입력 경로를 다르게 비교해 실패하는 문제가 재현되어, Gradle이 제공하는 JAR 산출물을 선택하도록 했다. 저장소를 영문 경로에 복사하거나 캐시를 지우지 않고 원래 경로에서 빌드한다. 의존성 버전과 전이 의존성은 유지한다. [Gradle 산출물 선택](https://docs.gradle.org/current/userguide/artifact_transforms.html)

## 핵심 검사

```sh
cd android
./gradlew :core:test --no-daemon
```

저장소 루트에서:

```sh
swift test --package-path ios/FollowerCore
node --test shared/tests/capture.test.cjs
```

이 검사는 합성 응답과 명단으로 계정 일치, 수치 정밀도, 명단 완료·실패, 관계 비교를 확인한다. 실제 로그인·네트워크 수집·위젯 실행 성공을 증명하지 않는다.

## Android 앱·위젯

```sh
cd android
./gradlew :core:test :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --no-daemon
./gradlew :app:assembleRelease :app:lintRelease --no-daemon
```

디버그 APK는 `android/app/build/outputs/apk/debug/app-debug.apk`에 생성된다. Release APK는 서명되지 않은 로컬 산출물이며, 위 명령은 스토어에 제출하지 않는다.

Android 홈의 `1분 빠른 추적` 또는 `더보기 → 수집 설정`에서 Instagram 연결 후 알림 권한을 허용하고 직접 시작한다. 앱을 홈 화면으로 내린 채 공식 프로필의 새 수치를 수집하고 저장 직후 위젯 갱신을 요청한다. 일반 예약은 최소 15분이며 빠른 모드는 최대 약 6시간의 전경 서비스다. 요청 제한은 수동·예약·빠른 수집 모두 대기하고 로그인·형식 오류는 자동 반복을 중단한다. 실제 성공과 시험 범위는 [기기 검증](DEVICE_VALIDATION.md)에 기록한다.

`RefreshPolicyTest`는 요청 제한 대기, 인증·형식 오류 중단, 기존 계정 JSON 호환성과 Instagram 프로필 경로의 재사용을 검사한다. `InstagramWebProfileTest`는 별도 세션 HTTP 후보 응답의 본인 ID·정확한 정수·실패 분류를 검사한다. 이 응답 검사를 HTTP 후보 경로의 실제 성공으로 취급하지 않는다. `ConnectionStatusDiagnostic.probeNativeCountWithoutLoginScreen`은 `probeNativeCount=true`로 명시적으로 선택해야 실제 요청을 수행하며 계정·쿠키·숫자를 출력하거나 기록을 덮어쓰지 않는다. 실제 사용자 기기에는 전체 instrumentation 검사를 실행하지 않는다.

일시적인 네트워크·서버 오류(`OFFLINE`) 뒤에는 자동 요청의 대기를 1·2·4·8·15분으로 늘리고 이후 15분으로 유지한다. `transientRetry`를 계정 JSON에 저장해 앱을 다시 실행해도 대기를 유지한다. 성공한 수 또는 완료 명단 수집은 이 상태를 초기화하며, 오류는 마지막 정상 기록을 덮어쓰지 않는다. 사용자가 누른 갱신은 로컬 대기 중에도 실행할 수 있지만 SNS의 숫자 `Retry-After`로 설정한 `nextAllowedAt`은 수동 요청에도 적용한다. 두 시각은 다음 실행이 허용되는 경계이며 OS가 정하는 실제 실행 시각을 보장하지 않는다. 일반 예약·빠른 추적·명단 예약은 같은 정책을 사용한다. 새 JSON 필드는 선택형이며 DB 버전 2와 기존 계정 payload를 유지한다.

`SyncTransientRetryRuntimeTest` 6개는 별도 QA 기기의 UUID Room DB와 합성 수집기로 즉시 자동 반복 중단, DB 재개방 후 대기 보존, 명시적 복구·연속 실패·서비스 대기·취소·완료 명단 보존을 검사한다. 위젯 발행도 주입한 함수로 격리하고 실제 앱 DB·세션과 SNS 요청을 사용하지 않는다. 실제 연결 기기에서 전체 검사를 실행하지 않는다.

빈 테스트용 에뮬레이터에서만 다음 실행 검사를 사용한다. Gradle의 기기 검사 수명 주기는 테스트 앱 설치·제거를 포함하므로 실제 SNS를 연결해 사용하는 앱 설치에 실행하지 않는다.

```sh
./gradlew :app:connectedDebugAndroidTest --no-daemon
```

화면 검사는 SNS 선택·탭 이동·설정, 저장 검사는 Android Keystore의 암호화와 변조 거부 및 Room 트랜잭션 경계를 확인한다. 위젯 검사는 실제 Glance `RemoteViews`를 크기별로 생성·적용·레이아웃하고 부모 영역에 가려지거나 숫자·비교 시각이 말줄임 처리되지 않는지 검사한다. 합성 위젯 계정은 검사 안에만 존재하며 앱 DB에 저장하지 않는다. 홈 화면 런처의 위젯 추가·클릭·자동 갱신은 [기기 검증](DEVICE_VALIDATION.md)에서 별도로 확인한다.

`WidgetRuntimeTest.failedRefreshKeepsTheLastCountAndChangeAtEverySize`는 재로그인 필요와 일시 오류의 두 상태를 작은·넓은·큰 위젯에 각각 적용한다. 실제 `RemoteViews`의 마지막 수치·변화·오류 표시와 가시 영역을 확인하며, 정상 기록의 수집 시각 보존은 같은 검사의 별도 시나리오에서 확인한다. 오류 안내만 바뀌어도 기록을 0이나 새 관측으로 바꾸지 않아야 한다.

`AppFlowRuntimeTest`는 네 탭, 앱 지원·도움말의 뒤로 가기, 스크롤·화면 재생성 후 상태 유지와 반복 위젯 진입을 검사한다. `DesignRuntimeTest`는 DB에 저장하지 않는 합성 화면으로 수집 지연·빠른 추적 중지 사유 표시, 위젯 계정 선택, 검색 초기화, 미지원 명단 요청 차단, 320dp·글자 1.5배의 간격 선택 및 주요 밝은/어두운 텍스트 대비를 검사한다. 중지 사유 검사는 개인 세션 없는 QA 기기의 실행 상태만 임시 변경하고 복원한다. 화면 캡처는 기기 내부의 `qa` 폴더에 저장하며 실제 사용자 기기에서 이 검사를 실행하지 않는다. 디자인과 BM 안내의 범위는 [UI/UX](UI_UX.md)에 정리한다.

`LoginNavigationPolicyTest`는 로그인 호스트와 수집 쿠키 호스트의 분리, 공식 로그인 이동과 유사 도메인 거부를 검사한다. `LoginRuntimeTest.eachProviderOpensAProtectedLoginWindowAndCanBeClosed`는 SDK 29 이상에서 다섯 SNS 창 진입·닫기와 실제 `FLAG_SECURE` 적용을 확인한다. `recordOfficialLoginPageAvailability`는 네트워크가 있는 시험 기기에 명시적으로 실행하는 진단이며, 입력 요소 개수·화면 높이·입력란 중심점의 터치 가능 여부와 페이지 상태만 기록한다. 수집하는 입력값은 없고 로그인 성공을 주장하는 검사가 아니다. 기본 CI는 테스트 APK를 컴파일하며 실제 SNS 페이지 진단을 실행하지 않는다.

Android 로그인 창은 인증 쿠키 후보와 공식 페이지 상태를 확인한 뒤 본인 계정 수치를 자동으로 읽는다. 로그인·추가 인증 화면에서는 연결을 보류한다. `AutoConnectionPolicyTest`는 중복 요청과 HTTP 429의 대기 시간, 제한된 네트워크 재시도 및 오류 안내를 검사한다. `AutoConnectionRuntimeTest`는 개인 세션이 없는 별도 기기에서 합성 쿠키·페이지로 버튼 없는 연결, 미인증·다른 계정 거부, 정확한 0, 요청 없이 프로필 수치 읽기, HTTP 429 뒤 추가 요청 없이 늦게 표시된 수치 확보를 검사한다. 합성 계정을 앱 DB에 저장하지 않는다.

Instagram은 쿠키의 본인 ID와 JSON의 사용자 ID를 먼저 맞춘다. 본인 프로필의 정확한 JSON 수치나 같은 프로필의 팔로워 링크 수치를 우선 읽으며, 축약 수만 있으면 저장하지 않는다. Instagram·Reddit의 추가 읽기 요청은 현재 공식 HTTPS 출처 안에서 `fetch`의 세션 자격증명을 사용한다. HTTP 429에는 서버의 숫자 `Retry-After` 또는 기본 15분을 적용하고 네이티브 요청으로 즉시 재시도하지 않는다. 대기 중에도 현재 문서만 읽는 작업은 가능하다. 웹 화면에서 확보한 수치는 전경 수집으로 표시하며 백그라운드 요청 성공으로 취급하지 않는다.

`ConnectionStatusDiagnostic`은 명시적으로 실행하는 읽기 전용 진단이다. SNS별 세션 후보·계정 존재·수치 존재와 상태·수집 경로만 출력하며, 식별자·사용자명·수치·원시 쿠키·DOM은 출력하지 않는다. 실제 세션 기기에는 전체 `connectedDebugAndroidTest`를 실행하지 않는다.

`recordStoredRelationshipStatus`는 `probeStoredRelationships=true`로 선택한 경우에만 저장된 Instagram 관계 결과를 읽는다. 전체 명단의 저장 상태·두 방향의 관측 여부·첫 기준 기록 여부·비교 시각과 세 분류의 중복 여부만 출력한다. 새 명단을 수집하거나 개인 명단·계정 식별자·인원수를 출력하지 않는다. 완료된 결과가 저장되고 분류가 서로 겹치지 않는지 확인하는 진단이며, 실제 관계 변경·장시간 갱신 검사를 대신하지 않는다.

`StartupExitDiagnostic`은 SDK 30 이상에서 `probeStartupExit=true`로 선택해야 앱 자신의 종료 이력을 읽는다. 종료 사유·시각·중요도·종료 상태와 본인 프로세스 메인 스레드의 Java 메서드만 출력하며, 종료 설명·원시 trace·다른 스레드·계정 정보는 출력하지 않는다. 합성 trace 검사는 URL·쿠키 형태의 행과 다른 스레드·프로세스가 출력되지 않는지 확인한다. 상세 trace는 OS의 저장 범위에 따라 없을 수 있으며, 진단 실행 성공을 ANR 해결이나 안정성 통과로 기록하지 않는다. [Android ApplicationExitInfo](https://developer.android.com/reference/android/app/ApplicationExitInfo)

이 두 진단은 실제 앱 데이터를 지우지 않고 테스트 APK만 갱신해 명시적인 메서드를 실행한다. Instrumentation은 대상 앱 프로세스를 다시 시작하므로 사용자가 로그인하거나 빠른 추적 서비스가 실행 중일 때에는 실행하지 않는다. 아래 기기 ID는 확인한 기기로 바꾼다. `System.out`의 진단 표식에 해당하는 출력만 보존한다.

```sh
./gradlew :app:assembleDebugAndroidTest --no-daemon
adb -s TEST_DEVICE_SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s TEST_DEVICE_SERIAL shell am instrument -w -r \
  -e class dev.datell.followertracker.StartupExitDiagnostic -e probeStartupExit true \
  dev.datell.followertracker.test/androidx.test.runner.AndroidJUnitRunner
adb -s TEST_DEVICE_SERIAL shell am instrument -w -r \
  -e class 'dev.datell.followertracker.ConnectionStatusDiagnostic#recordStoredRelationshipStatus' \
  -e probeStoredRelationships true \
  dev.datell.followertracker.test/androidx.test.runner.AndroidJUnitRunner
adb -s TEST_DEVICE_SERIAL logcat -d -s System.out:I | rg 'STARTUP_EXIT_DIAGNOSTIC=|RELATIONSHIP_DIAGNOSTIC='
```

`percentageHeightLoginFormIsVisibleAndAcceptsTouch`는 `height:100%`와 `overflow:hidden`으로 구성한 로컬 로그인 폼의 높이·실제 픽셀·터치 후 입력 포커스를 검사한다. WebView가 Compose의 기본 `WRAP_CONTENT` 레이아웃 파라미터를 사용하면 폼 높이가 0이 되는 실패를 재현했다. 로그인 WebView에는 `MATCH_PARENT`를 명시해 페이지의 백분율 높이가 주어진 화면 영역을 기준으로 계산되도록 한다. [Chromium의 WebView 높이 처리](https://chromium.googlesource.com/chromium/src/+/refs/heads/main/android_webview/java/src/org/chromium/android_webview/AwLayoutSizer.java)

Apple Silicon 에뮬레이터에서 웹 화면 또는 시스템 프로세스가 불안정하면 공식 안내에 따라 테스트 AVD 실행 인자에 `-feature -Vulkan`을 적용할 수 있다. 이번 API 36 시험에서 같은 AVD의 기본 Vulkan 실행은 시스템 프로세스 종료를 동반했고, Vulkan을 끈 실행에서는 로그인·화면 검사가 정상 종료했다. 앱 설정이나 실제 기기의 보안 설정을 바꾸는 인자는 아니다. 사용자가 사용하는 AVD를 초기화하지 않고 별도 시험 AVD로 확인한다. [Android 에뮬레이터 문제 해결](https://developer.android.com/studio/run/emulator-troubleshooting)

Android DB 버전 2는 `relationship_changes`와 `relationship_history_cursor`를 추가한다. Room의 `AutoMigration(1, 2)`를 사용하며 `android/app/schemas/`에 두 버전의 스키마를 보존한다. `RelationshipHistoryRuntimeTest`는 테스트 전용 UUID DB에 실제 버전 1 스키마·합성 암호문을 만들고 최신 Room으로 열어 계정·수치·명단 보존, 모든 저장 명단의 이탈 기록 복원, 연결 해제 cascade를 확인한다. 테스트 스키마는 `androidTest` APK에만 포함된다. [Room 마이그레이션 문서](https://developer.android.com/training/data-storage/room/migrating-db-versions)

## iOS 앱·위젯

iOS DB 버전 2의 추가 마이그레이션 SQL은 `FollowerCore/TrackerSchema.swift`에서 관리한다. 앱·위젯은 같은 SQLite 트랜잭션 안에서 현재 버전을 읽고 마이그레이션한다. macOS 코어 테스트는 실제 SQLite 버전 1 DB를 만들어 기존 payload·명단·동기화 임대의 보존과 새 관계 기록의 cascade를 확인한다. 이 검사는 실제 iOS App Group·Keychain 공유 검증을 대신하지 않는다.

`ios/FollowerTracker.xcodeproj`와 공유 `FollowerTracker` scheme으로 앱 및 위젯 확장을 함께 빌드한다. 프로젝트를 재생성할 때는 `python3 ios/scripts/generate_project.py`를 실행한다. 외부 프로젝트 생성 도구는 필요하지 않다.

iOS 로그인 창도 인증 쿠키 후보와 공식 페이지 상태를 확인한 뒤 정확한 본인 수치를 자동으로 확보한다. `WKWebView.callAsyncJavaScript`의 비동기 결과를 기다려 공통 캡처를 실행하고, 본인 ID·SNS·정수 수치·수집 출처를 검증한 다음 세션과 기록을 저장한다. 연결 확인 버튼은 필요하지 않다. 요청 제한에는 대기 시간을 유지하고 즉시 네이티브 요청으로 다시 시도하지 않는다. 대기 중 현재 문서의 수치 읽기는 계속하며 창을 닫으면 자동 연결 작업을 취소한다. [Apple WebKit API](https://developer.apple.com/documentation/webkit/wkwebview/callasyncjavascript(_:arguments:in:contentworld:))

`AutoConnectionTests`는 로그인·추가 인증 보류, 중복 요청과 제한된 재시도, 계정 일치·정확한 0·출처 검증을 검사한다. `FollowerTrackerTests`의 `AutoConnectionRuntimeTests`는 개인 세션 없는 별도 시뮬레이터의 실제 WKWebView에 합성 쿠키와 문서를 주입해 자동 연결·미인증 거부·로그인 페이지 보류·HTTP 429 뒤 늦게 표시되는 DOM 수치·닫기 취소를 검사한다. 합성 fetch만 사용하며 세션 Vault와 계정 DB에 시험 값을 저장하지 않는다. CI는 테스트 묶음을 컴파일하고 실제 SNS 로그인이나 이 런타임 검사를 실행하지 않는다. 사용자가 로그인 중인 시뮬레이터에 이 검사를 실행하지 않는다.

앱은 홈·관계·위젯·더보기의 탭마다 NavigationStack을 사용한다. 지원·도움말·개인정보는 더보기의 하위 경로이며 계정 상세는 홈에 속한다. 선택 탭·관계 검색과 위젯 미리보기 선택은 SceneStorage로 보존한다. 위젯 확장과 앱 미리보기는 `Shared/TrackerWidgetContent.swift`를 공유하고 실제 마지막 성공 기록의 날짜·시각을 표시한다. iOS에는 1분 빠른 추적을 제공하지 않는다. [화면과 BM 안내](UI_UX.md)

같은 테스트 대상의 `DesignRuntimeTests` 5개는 시간 범위 밖·미래 관측 제외, 당일 경계·최근 14개 기록, 정확하지 않거나 한 개뿐인 기록의 비교 보류, 수집 실패 중 마지막 성공 시각과 0 보존을 검사한다. 실제 UIColor의 밝은/어두운 주요 글자 대비 6쌍을 확인하고, DB에 저장하지 않는 합성 계정 카드와 세 크기의 위젯을 ImageRenderer로 그린다. 카드의 280pt 너비·Dynamic Type accessibility1 캡처를 포함하며 이미지는 QA 앱의 임시 `tracker-ui-qa` 폴더에 남는다. 렌더링 성공은 전체 VoiceOver·다계정 배치·실제 SNS 수집의 성공을 뜻하지 않는다.

iOS의 `RefreshPolicy`는 수동·백그라운드 갱신 모두 `nextAllowedAt` 이전의 수집 요청을 차단한다. 대기가 끝나면 사용자가 명시적으로 재시도할 수 있으며, 인증·확인·형식 오류와 전경 전용 상태의 자동 반복은 계속 중단한다. 앱과 위젯의 수 수집은 같은 `SyncService`를 사용한다. 관계 명단 수집에도 같은 대기 시각을 적용한다.

iOS에도 같은 `transientRetry` 정책을 적용하며 앱 예약과 위젯의 자동 수집·하루 단위 명단 예약에서 로컬 대기를 확인한다. `SyncTransientRetryRuntimeTests` 6개는 UUID 경로의 SQLite DB와 합성 수집기로 실제 `SyncService`를 실행한다. 재개방한 저장소의 대기 복원, 즉시 자동 요청 차단, 수동 복구와 성공 후 초기화, 서비스 대기, 취소 및 이전 완료 명단 보존을 확인한다. 명단 암호화에는 QA 앱의 Keychain을 사용하지만 세션 Vault와 기본 App Group DB를 사용하지 않는다. 이 결과를 앱·위젯의 실제 세션 공유 검증으로 확대하지 않는다.

`RefreshPolicyTests`는 대기 시각의 직전·일치·이후 경계와 다른 상태에서의 대기 보존을 검사한다. `SyncCooldownRuntimeTests` 4개는 UUID 임시 SQLite DB와 주입한 합성 수집기로 실제 `SyncService`를 실행한다. 수동·백그라운드·명단 요청 중 대기 유지, 요청 횟수 0, 마지막 정상 기록 보존, 임대 잠금 미점유와 대기 종료 후 재시도를 확인한다. 시험 DB는 명시적인 경로에만 생성하며 사용자의 App Group DB와 세션 Vault를 사용하지 않는다. 실제 SNS에 요청하는 검사가 아니다.

```sh
xcodebuild -project ios/FollowerTracker.xcodeproj -scheme FollowerTracker \
  -configuration Debug -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 17' \
  CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY=- -parallel-testing-enabled NO test
```

시험 기기 이름은 비어 있는 QA 시뮬레이터로 지정한다. 합성 검사는 실제 계정의 로그인·수치·Keychain 공유·백그라운드 수집 성공을 증명하지 않는다.

```sh
xcodebuild -project ios/FollowerTracker.xcodeproj -scheme FollowerTracker \
  -configuration Debug -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  CODE_SIGNING_ALLOWED=NO build
xcodebuild -project ios/FollowerTracker.xcodeproj -scheme FollowerTracker \
  -configuration Release -sdk iphoneos -destination 'generic/platform=iOS' \
  CODE_SIGNING_ALLOWED=NO build
```

시뮬레이터 이름은 설치한 기기에 맞게 바꾼다. 위 명령은 컴파일 검사이며 시뮬레이터 부팅이나 기기 설치를 하지 않는다. 실기기에서 앱과 위젯을 사용하려면 같은 서명 팀에서 App Group·Keychain 공유 권한을 설정해야 한다. 로컬 `ios/Signing.local.xcconfig`에 실제 팀과 등록된 그룹을 지정하고 Xcode에서 앱·위젯 두 대상의 서명을 확인한다. 이 파일은 Git에서 제외한다.

시뮬레이터에서 앱을 실제로 실행할 산출물은 ad hoc 서명으로 빌드한다. 서명 없는 컴파일 산출물에서는 App Group 컨테이너를 열지 못할 수 있으며, 이를 앱의 저장소 오류를 숨기거나 별도 DB를 만드는 방식으로 해결하지 않는다.

```sh
xcodebuild -project ios/FollowerTracker.xcodeproj -scheme FollowerTracker \
  -configuration Debug -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY=- build
```

이 명령도 설치·실행은 하지 않는다. 설치한 앱의 초기 화면에서 저장소 오류가 없는지 확인하고, SNS 세션 저장·앱과 위젯의 Keychain 공유는 로그인 후 별도로 검사한다. 서명 설정 변경 없이 로컬 시뮬레이터용 빌드 인자로 적용하며 실기기 서명을 대신하지 않는다.

```xcconfig
DEVELOPMENT_TEAM = YOUR_TEAM_ID
APP_GROUP_ID = group.dev.datell.followertracker
SHARED_KEYCHAIN_ID = dev.datell.followertracker.shared
```

앱과 위젯이 같은 App Group DB와 Keychain 그룹을 사용한다. App Group 저장소를 열 수 없으면 오류를 표시하며, 별도 저장소에 조용히 기록하는 방식으로 공유 실패를 숨기지 않는다. 앱과 위젯의 동시에 실행되는 수집은 DB의 계정별 임대 잠금으로 중복을 막는다. 개인정보 manifest의 UserDefaults 접근 이유는 앱·확장 간 공유를 위한 `1C8F.1`이다. [Apple 접근 이유 문서](https://developer.apple.com/documentation/bundleresources/app-privacy-configuration/nsprivacyaccessedapitypes/nsprivacyaccessedapitype)

## 수집 경로의 근거와 상태

Instagram의 기기 세션 요청은 공식 외부 개발자 API 계약과 별개의 웹 경로 후보다. User Info와 관계 페이지의 응답을 버전별 수집기에서 해석한다. 공개 웹 클라이언트 식별자와 경로를 검토할 때 [Instaloader의 요청 컨텍스트](https://github.com/instaloader/instaloader/blob/master/instaloader/instaloadercontext.py)와 [프로필 구조](https://github.com/instaloader/instaloader/blob/master/instaloader/structures.py)를 참조했다. 코드 복사나 SDK 의존성 추가는 하지 않았다. 이 참조는 우리의 실제 계정 수집 성공이나 서비스의 수집 허가를 증명하지 않는다.

TikTok은 자기 프로필의 페이지 JSON과 로그인 사용자 문맥이 일치할 때만 수치를 읽는 후보를 구현한다. Reddit은 로그인 사용자 정보의 프로필 구독자 수를 해석한다. X·Facebook은 로그인한 자기 계정과 일치하는 WebView 정보만 처리하며 백그라운드 수집은 아직 검증되지 않았다. 실제 결과와 기능 표는 구현 기록에 추가한다.

원시 쿠키, 로그인 폼 값, 응답 전체, 개인 명단을 진단 로그·fixture·커밋에 넣지 않는다. 시험 중 생성된 빌드·화면·측정 파일은 `.local/` 또는 `.cache/`에 보관하고 개인정보를 제거한 증거만 공유한다.
