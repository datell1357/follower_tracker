# 본인 YouTube 채널 연결

계정의 SNS 연결 목록에서 YouTube를 고르면 공식 YouTube Studio를 연다. Google 로그인·추가 인증과 채널 선택은 공식 화면에서 사용자가 완료한다. Studio의 선택한 채널 ID와 현재 구독자 수를 확인한 뒤 자동 연결한다. API 키·유료 데이터 서비스·새 서버는 추가하지 않는다.

## 정확한 수치와 채널 경계

[YouTube 공식 도움말](https://support.google.com/youtube/answer/6051134?hl=ko)은 Studio의 실시간 집계와 공개 채널의 축약 표시를 구분한다. [일반 Data API의 subscriberCount](https://developers.google.com/youtube/data/reference/rest/v3/channels#statistics.subscriberCount)도 유효숫자 세 자리로 내림 처리되므로 정확한 Studio 수치의 대체 경로로 사용하지 않는다.

공유 판독기는 공식 `studio.youtube.com/channel/UC…`의 채널 대시보드·분석 문서만 받는다. Studio 앱과 채널 분석 카드, 선택한 채널 ID·이름과 정확한 정수 수치가 준비되어야 한다. 공개 채널·동영상 화면·추천 채널·조회수·시청 시간·구독자 증감 수치로 대신하지 않는다. 축약 문자열·서로 다른 정확한 값·다른 채널 ID는 저장하지 않는다. 정확한 0은 정상 관측이다.

동일 채널의 키는 `YOUTUBE:<channel-id>`로 유지하고 기존 수치 이력 구조를 재사용한다. 수치 명칭은 `구독자`, 표시 이름은 채널 이름이다. 팔로워·팔로잉 명단 분석은 지원하지 않는다. 현재 개인 계정과 같은 SNS별 단일 연결 정책을 사용하며 여러 YouTube 채널의 동시 연결은 추가하지 않았다.

## 세션과 반복 수집

YouTube 도메인의 로그인 쿠키와 채널 ID·사용자 에이전트를 기존 보안 저장소에 보관한다. Google 로그인 주소는 로그인 이동에만 허용하고 SNS 쿠키를 Google·외부 호스트로 보내는 수집 요청은 만들지 않는다. 비밀번호·인증번호·OAuth 토큰·요청 본문을 추출하지 않는다. 사용자 로그아웃은 앱의 YouTube 연결과 YouTube 쿠키를 해제하며 다른 SNS가 사용하는 Google 로그인 전체를 지우지 않는다. Android는 `studio.youtube.com`에만 설정된 쿠키도 공식 로그인 호스트에서 만료시킨다.

Android는 연결한 Studio 문서를 기존 15분·빠른 추적 브라우저 수집에 포함한다. 매 수집에서 같은 채널 ID와 정확한 수를 새로 확인하며 새 관측을 저장한 뒤 위젯 갱신을 요청한다. 정적 자원·비활성 WebView를 재사용하고 이미지·자동 미디어 재생을 제한한다. 실제 수치·세션 유지가 비용 절감보다 우선한다.

iOS는 Studio 로그인 창에서 확보한 관측을 저장하고 위젯에 마지막 실제 값을 표시한다. 현재 Studio의 네이티브 백그라운드 판독 경로는 검증되지 않아 전경 수집으로 유지한다. Google의 WebView 로그인 허용 여부·추가 인증과 실제 Studio 문서 구성, OS의 백그라운드 실행 허용은 코드 검사와 별도로 확인한다.

## 검증 범위

공통 Node 검사와 Kotlin·Swift 핵심 검사는 정확한 0, Studio 채널 ID·출처·정밀도·공식 URL, 다른 채널·공개 수치·축약값·충돌값 거부를 검사한다. 실제 SQLite 저장·위젯 선택·로그아웃의 SNS 격리는 macOS 저장소 검사에 포함한다. Android `YouTubeStudioRuntimeTest`는 빈 `.loginvalidation` 앱에 한정해 네트워크가 차단된 로컬 Studio 모양 문서로 반복 읽기·세션 보존·새 수치·무효화 후 중단·Studio 호스트 전용 쿠키의 로그아웃과 다른 SNS 쿠키 보존을 검사한다. 실제 Google 로그인·Studio DOM 성공·장시간 실서비스 수집과 구분한다.

공식 아이콘은 [YouTube Icon](https://brand.youtube/youtube-icon)이 제공한 AI 파일을 SVG와 Android VectorDrawable로 변환했다. 색상·경로를 유지하고 아트보드의 빈 여백만 잘랐다. 출처와 해시는 `shared/brand-assets/sources.json`에 기록한다.

2026-10-23 로컬 검증: 공통 Node 80개, Kotlin 핵심 51개, Android 앱 Debug·Release 각 126개, Swift 핵심 96개, 실제 공유 SQLite·HTTP 검사 46개를 통과했다. 분리한 Android 에뮬레이터 검증 앱의 오프스크린 Studio 검사 3개도 통과했으며, 기존 로그인 앱 프로세스는 유지됐다. Android Debug·Release 빌드·린트와 iOS 앱·위젯의 Debug 테스트 타깃·서명 없는 Release 빌드를 통과했다. 실제 Google 로그인과 본인 채널의 정확한 수 연결, 실제 장기 반복 수집·배터리·데이터 측정은 아직 완료하지 않았다.
