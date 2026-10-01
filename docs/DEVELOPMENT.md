# 개발과 검증

## 환경

- Android: SDK 36, JDK 21, Gradle 8.13, AGP 8.13.2, Kotlin 2.2.21.
- Android 화면: Compose BOM 2025.10.01. SDK 36·AGP 8.13.2와 호환되는 AAR 메타데이터를 확인했다. BOM 2026.09.00은 SDK 37·AGP 9.1을 요구해 현재 구성과 맞지 않았다.
- iOS: Xcode 16+의 Swift 6 도구, 앱·위젯은 iOS 17+ 대상. 로컬 빌드는 Xcode 26.6에서 확인한다. 핵심 패키지는 macOS 14+에서도 검사한다.
- WebView 공통 캡처 테스트: Node.js 22+.

Gradle Wrapper는 공식 배포 SHA-256을 검증한다. 버전 카탈로그와 dependency lockfile을 함께 관리한다. Kotlin 컴파일은 Gradle 프로세스 안에서 실행해 한글 경로의 대체 프로세스 인자 처리와 별도 데몬의 캐시 접근 문제를 피한다.

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

빈 테스트용 에뮬레이터에서만 다음 실행 검사를 사용한다. Gradle의 기기 검사 수명 주기는 테스트 앱 설치·제거를 포함하므로 실제 SNS를 연결해 사용하는 앱 설치에 실행하지 않는다.

```sh
./gradlew :app:connectedDebugAndroidTest --no-daemon
```

화면 검사는 SNS 선택·탭 이동·설정, 저장 검사는 Android Keystore의 암호화와 변조 거부 및 Room 트랜잭션 경계를 확인한다. 위젯 검사는 실제 Glance `RemoteViews`를 크기별로 생성·적용·레이아웃한다. 합성 위젯 계정은 검사 안에만 존재하며 앱 DB에 저장하지 않는다. 홈 화면 런처의 위젯 추가·클릭·자동 갱신은 [기기 검증](DEVICE_VALIDATION.md)에서 별도로 확인한다.

Android DB 버전 2는 `relationship_changes`와 `relationship_history_cursor`를 추가한다. Room의 `AutoMigration(1, 2)`를 사용하며 `android/app/schemas/`에 두 버전의 스키마를 보존한다. `RelationshipHistoryRuntimeTest`는 테스트 전용 UUID DB에 실제 버전 1 스키마·합성 암호문을 만들고 최신 Room으로 열어 계정·수치·명단 보존, 모든 저장 명단의 이탈 기록 복원, 연결 해제 cascade를 확인한다. 테스트 스키마는 `androidTest` APK에만 포함된다. [Room 마이그레이션 문서](https://developer.android.com/training/data-storage/room/migrating-db-versions)

## iOS 앱·위젯

iOS DB 버전 2의 추가 마이그레이션 SQL은 `FollowerCore/TrackerSchema.swift`에서 관리한다. 앱·위젯은 같은 SQLite 트랜잭션 안에서 현재 버전을 읽고 마이그레이션한다. macOS 코어 테스트는 실제 SQLite 버전 1 DB를 만들어 기존 payload·명단·동기화 임대의 보존과 새 관계 기록의 cascade를 확인한다. 이 검사는 실제 iOS App Group·Keychain 공유 검증을 대신하지 않는다.

`ios/FollowerTracker.xcodeproj`와 공유 `FollowerTracker` scheme으로 앱 및 위젯 확장을 함께 빌드한다. 프로젝트를 재생성할 때는 `python3 ios/scripts/generate_project.py`를 실행한다. 외부 프로젝트 생성 도구는 필요하지 않다.

```sh
xcodebuild -project ios/FollowerTracker.xcodeproj -scheme FollowerTracker \
  -configuration Debug -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  CODE_SIGNING_ALLOWED=NO build
xcodebuild -project ios/FollowerTracker.xcodeproj -scheme FollowerTracker \
  -configuration Release -sdk iphoneos -destination 'generic/platform=iOS' \
  CODE_SIGNING_ALLOWED=NO build
```

시뮬레이터 이름은 설치한 기기에 맞게 바꾼다. 위 명령은 컴파일 검사이며 시뮬레이터 부팅이나 기기 설치를 하지 않는다. 실기기에서 앱과 위젯을 사용하려면 같은 서명 팀에서 App Group·Keychain 공유 권한을 설정해야 한다. 로컬 `ios/Signing.local.xcconfig`에 실제 팀과 등록된 그룹을 지정하고 Xcode에서 앱·위젯 두 대상의 서명을 확인한다. 이 파일은 Git에서 제외한다.

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
