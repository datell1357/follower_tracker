# Android 디버그 수집 로그

Debug 앱은 시작할 때 `FollowerDiagnostics` Logcat과 앱 내부의 순환 파일 로그를 자동으로 켠다. Release는 별도 소스의 무동작 구현을 사용하므로 파일 로거·큐·실행 ID 생성기를 초기화하지 않는다. 기존 시스템·위젯 경고 로그 전체를 없애는 변경은 아니다. iOS에는 이 로거를 추가하지 않는다.

## 기록하는 정보

- 앱 프로세스 시작과 프로세스마다 새로 생성한 임의 실행 ID, 수집 시도 번호
- SNS 종류·개인 계정/페이지 구분, 수동/백그라운드 수집, 수집 시작·완료·취소
- 세션 메타데이터 또는 인증 부재, 프로필 브라우저 생성/재사용, 네트워크·HTTP·인증 이동·파싱 실패 단계
- 읽기 시간과 기존 앱 UID 단위의 송수신 바이트 계측. 요청 하나의 정확한 사용량을 뜻하지 않는다.
- DB 커밋 후의 관측 저장과 실제 관측 시각, 세션 변경으로 무시한 저장, 저장된 실패 상태와 서버/로컬 재시도 기한
- 로그인 메타데이터 저장·무효화·로그아웃, 빠른 추적 시작/중지/네트워크 대기와 복구 요청, 일반 예약 작업 시작/완료, 위젯 갱신 실패

실행 ID는 SNS 로그인 세션 ID가 아니다. 시도 번호와 함께 읽기·저장·실패 기록을 연결하는 데만 사용하며 앱 재시작 때 바뀐다. `ATTEMPT_FINISH`와 `WORK_FINISHED`는 처리 종료를 뜻한다. 실제 팔로워 수 수집 성공은 동일 시도의 `OBSERVATION_STORED`로 확인한다. 이 이벤트는 DB transaction이 커밋된 뒤에만 남기며, 쓰기 차단이나 저장 롤백을 성공으로 표시하지 않는다. 프로필 읽기 대기가 끝나면 `LOAD_TIMEOUT`/`CAPTURE_TIMEOUT`과 마지막 허용된 원인 코드(예: `EXACT_COUNT_MISSING`)를 구분하며, 알 수 없는 원문은 `UNKNOWN`으로만 남긴다.

쿠키·토큰·비밀번호·인증번호·계정 키/ID·계정명·프로필 주소·URL 쿼리·응답 본문·팔로워/팔로잉 수·명단·예외 메시지와 스택을 기록하지 않는다. 자유 입력 문자열 대신 고정된 이벤트·단계·상태 enum과 허용된 숫자/불리언 필드만 직렬화한다. 외부 전송이나 새로운 SNS 요청·실행 주기를 추가하지 않는다.

## 보관과 읽기

파일은 앱의 `noBackupFilesDir/diagnostics/collection-0.jsonl`부터 `collection-3.jsonl`까지 네 슬롯에 저장하며, 슬롯당 최대 2MiB·전체 최대 8MiB로 순환한다. 새 기록이 차면 다음 앱 전용 슬롯을 덮어쓴다. 그 외의 파일은 건드리지 않는다. 슬롯 번호가 시간 순서를 뜻하지 않으므로 첫 줄의 `generation` 순서로 읽고, 이후 각 줄을 JSON 기록으로 읽는다. 기록 시각은 Unix epoch 밀리초다.

USB 연결이 끊기거나 앱 프로세스가 다시 시작돼도 이미 쓴 기록은 남는다. 데이터 보존 업데이트에서도 유지하며 Android 자동 백업에는 포함하지 않는다. 앱 데이터 삭제·제거는 로그도 지운다. 과거 버전에 로그가 없었던 시간대의 원인을 소급해서 만드는 기능은 아니다.

```sh
adb -d logcat -v time -s FollowerDiagnostics:D
mkdir -p .local/logs
adb -d exec-out run-as dev.datell.followertracker tar -cf - no_backup/diagnostics > .local/logs/debug-diagnostics.tar
```

앱 내부 파일이라 일반 Release 앱이나 다른 앱에서 읽을 수 없다. 위 예시는 디버깅을 허용한 Debug 앱에서 개발자가 로컬로 가져오는 명령이며 업로드 명령이 아니다. 로그를 저장한 `.local/`은 Git에서 제외한다.

파일 쓰기는 단일 IO 소비자가 처리하고 수집/UI 호출은 기다리지 않는다. 대기 큐는 128개로 제한한다. 큐가 차거나 저장에 실패하면 수집을 계속하고, 가능한 다음 기록에 `ENTRIES_DROPPED` 개수를 남긴다. 파일 저장 불가 경고는 Logcat에 고정된 코드로만 출력한다. 강제 프로세스 종료 시 아직 큐에 남은 마지막 이벤트는 유실될 수 있으며, 마지막 파일의 불완전한 JSON 한 줄은 재시작 때 잘라낸 뒤 정상 기록부터 이어 쓴다.

## 검증

```sh
cd android
./gradlew :core:test :app:testDebugUnitTest :app:testReleaseUnitTest \
  :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease \
  :app:lintDebug :app:lintRelease --no-daemon
```

`RotatingDiagnosticLogTest`는 보관 한도·순환 순서·시계 변경과 프로세스 재생성·부분 쓰기·다른 파일 보존·민감정보 필드 제외를 검사한다. `DiagnosticLoggingRuntimeTest`는 별도의 `.loginvalidation` 패키지와 합성 DB에서 실패 이유/기한, 자동 복구의 실제 커밋, 예외 전파·메시지 제외, 저장 롤백의 성공 로그 차단을 확인한다. `DiagnosticProfileFailureRuntimeTest`는 연결·인증이 비어 있음을 먼저 확인한 같은 QA 패키지에 합성 쿠키만 설정하고, 네트워크를 차단한 로컬 HTML의 실제 15초 읽기 대기 뒤 원인 코드가 남는지 검사한다. 이 테스트가 만든 두 쿠키만 종료 후 제거한다. 개인 앱의 로그인 정보나 실제 SNS 요청을 테스트 fixture로 사용하지 않는다.

2026-10-22 검증에서 핵심 48개·앱 Debug/Release 각 119개와 격리 실기기 22개가 실패·skip 없이 통과했다. Debug·테스트 APK·Release/R8·두 lint도 통과했으며 lint 오류 0개·기존 경고 각 16개다. 최적화된 Release APK의 DEX에는 파일 로거 클래스·Logcat 태그·저장 실패 경고 코드가 남아 있지 않음을 확인했다.

실제 Samsung 기기에는 기존 자료를 보존해 업데이트했고, 앱 내부 순환 파일의 생성과 Instagram·Facebook 각각 5회의 시작→읽기→커밋→완료 연결을 확인했다. 파일의 필드도 허용된 스키마에 일치했다. 기존 연결 키·연결 시각·전체 관측·암호화된 세션 메타데이터와 프리미엄 실행 요청을 보존했고 재로그인 없이 후속 수집이 계속됐다. 이 짧은 확인을 실제 24시간 로그 보관·연속 수집 검증으로 계산하지 않는다.
