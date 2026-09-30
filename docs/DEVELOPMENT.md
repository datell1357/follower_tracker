# 개발과 검증

## 환경

- Android: SDK 36, JDK 21, Gradle 8.13, AGP 8.13.2, Kotlin 2.2.21.
- iOS 핵심 패키지: Swift 6 도구, iOS 17+/macOS 14+ 대상. 앱·위젯 프로젝트 구성은 구현 중이다.
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

## 수집 경로의 근거와 상태

Instagram의 기기 세션 요청은 공식 외부 개발자 API 계약과 별개의 웹 경로 후보다. User Info와 관계 페이지의 응답을 버전별 수집기에서 해석한다. 공개 웹 클라이언트 식별자와 경로를 검토할 때 [Instaloader의 요청 컨텍스트](https://github.com/instaloader/instaloader/blob/master/instaloader/instaloadercontext.py)와 [프로필 구조](https://github.com/instaloader/instaloader/blob/master/instaloader/structures.py)를 참조했다. 코드 복사나 SDK 의존성 추가는 하지 않았다. 이 참조는 우리의 실제 계정 수집 성공이나 서비스의 수집 허가를 증명하지 않는다.

TikTok은 자기 프로필의 페이지 JSON과 로그인 사용자 문맥이 일치할 때만 수치를 읽는 후보를 구현한다. Reddit은 로그인 사용자 정보의 프로필 구독자 수를 해석한다. X·Facebook은 로그인한 자기 계정과 일치하는 WebView 정보만 처리하며 백그라운드 수집은 아직 검증되지 않았다. 실제 결과와 기능 표는 구현 기록에 추가한다.

원시 쿠키, 로그인 폼 값, 응답 전체, 개인 명단을 진단 로그·fixture·커밋에 넣지 않는다. 시험 중 생성된 빌드·화면·측정 파일은 `.local/` 또는 `.cache/`에 보관하고 개인정보를 제거한 증거만 공유한다.
