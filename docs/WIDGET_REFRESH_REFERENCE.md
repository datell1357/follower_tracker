# AI Quota의 위젯 갱신 구조 참고

2026-10-03에 `/Volumes/Personal/개발/AI Quota`의 Android 코드를 읽기 전용으로 확인했다. 체크아웃 HEAD는 `c0ec759d1d7d2645e010ced26f929a2c7ee4b4f9`이며, 기존 작업 파일은 수정하지 않았다. 아래는 코드 구조의 비교다. 이번 작업에서 AI Quota 앱을 실행하거나 24시간 수집·배포 승인 상태를 검증한 것은 아니다.

## 코드에서 확인한 구조

| 역할 | AI Quota 코드 | 동작 |
| --- | --- | --- |
| 상시 실행 | `AndroidManifest.xml`, `providers/ProviderBackgroundRefreshService.kt` | `specialUse` 전경 서비스와 `usage_monitor` 하위 용도, 실행 알림, `START_STICKY` 사용 |
| 수집 주기 | `providers/ProviderRefreshPlan.kt` | 기본 60,000ms에서 수집에 걸린 시간을 뺀 다음 지연을 사용하며 최소 지연은 5,000ms |
| 수집기 | `providers/ProviderBackgroundRefreshService.kt` | 각 AI 서비스의 API 또는 웹 수집기를 실행하고 중복 주기를 차단 |
| 위젯 반영 | `providers/UsageSurfaceRefresher.kt` | 저장된 표시용 캐시를 갱신하고 위젯·알림 갱신을 2초 동안 모아서 처리 |
| 사용자 선택 | `sync/ForegroundRefreshController.kt` | 상시 모니터링 선택을 저장하고 서비스 시작·중지와 보조 점검 예약을 제어 |
| 복구 보조 | `sync/ForegroundRefreshHealthScheduler.kt`, `ForegroundRefreshHealthWorker.kt` | 15분 주기의 상태 점검과 최초 1분 후 점검으로 오래된 하트비트를 찾아 재시작을 시도 |
| 네트워크 복귀 | `providers/ProviderBackgroundRefreshService.kt` | 마지막 성공 데이터가 오래됐다면 다음 틱을 기다리지 않고 수집을 시도 |

경로는 AI Quota의 `android/app/src/main/java/com/aiquota/mobile/` 아래이며 manifest는 `android/app/src/main/AndroidManifest.xml`이다. 위젯 자체가 매분 AI 서비스에 요청하는 구조가 아니다. 서비스가 계정별 사용량을 수집하고 저장한 뒤 그 결과를 위젯에 전달한다. 팔로워 트래커에 참고할 부분도 수집과 위젯 표시를 나누는 이 구조다. 15분 WorkManager 점검은 1분 수집을 대신하는 타이머가 아니며 서비스 재시작 시도도 OS의 실행 허용을 보장하지 않는다.

## 현재 팔로워 트래커와의 차이

팔로워 트래커의 `RapidTrackingService`도 수집 경과 시간을 고려한 1분 루프, 실행·중지 알림, 실패 시 마지막 정상 기록 보존과 저장 직후 위젯 갱신 요청을 사용한다. 현재 서비스 유형은 `dataSync`이고 `START_NOT_STICKY`, 자체 상한 5시간 59분 30초, OS `onTimeout` 처리와 사용자의 직접 재시작을 유지한다. 일반 예약은 별도의 15분 이상 WorkManager 작업이다.

Android 15 이상을 대상으로 하는 `dataSync`·`mediaProcessing` 전경 서비스에는 백그라운드 실행 총 6시간/24시간 제한이 적용된다. 이 제한이 모든 전경 서비스 유형에 동일하게 적용되는 것은 아니다. AI Quota의 `specialUse`는 이 두 유형과 다르므로 코드에 같은 6시간 종료가 없는 이유를 설명한다. [Android 전경 서비스 시간 제한](https://developer.android.com/develop/background-work/services/fgs/timeout)

다만 `specialUse`는 다른 전경 서비스 유형에 포함되지 않는 용도에 사용하며 manifest의 용도 설명과 실제 동작은 Play Console에서 검토된다. 공식 문서의 `dataSync`에는 데이터 조회가 포함된다. 현재 SNS 프로필 수치 조회가 `specialUse` 요건을 충족하는지는 이번 코드 비교로 확정할 수 없다. 따라서 서비스 유형을 단순 교체하거나 현재 앱을 24시간 연속 1분 갱신 지원으로 표시하지 않았다. [서비스 유형과 적용 조건](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use), [전경 서비스 선언 안내](https://support.google.com/googleplay/android-developer/answer/13392821)

## 후속 구현·검증 기준

상시 모니터링을 진행할 때에는 수집 설정의 사용자 선택, 실제 성공 시각 기반 상태 점검, 네트워크 복귀 처리, 중지 선택을 존중하는 복구 구조를 참고할 수 있다. 서비스 유형에 맞는 실행 방식과 배포 가능 범위를 먼저 정해야 한다. 이후 앱을 닫고 화면을 잠근 상태의 24시간·72시간 관찰에서 실제 수집 간격, 요청 제한, 세션 만료, 프로세스 종료·재부팅과 위젯의 성공 시각을 확인한다. `START_STICKY` 선언이나 코드의 60초 간격만으로 이 검증을 통과한 것으로 간주하지 않는다.

계정 화면은 SNS별 기록만 보여주고, 실행 상태·중지 이유·수집 간격은 `설정 → 수집 설정`에 둔다. 위젯 미리보기와 추가 방법은 위젯 탭에서 확인한다.
