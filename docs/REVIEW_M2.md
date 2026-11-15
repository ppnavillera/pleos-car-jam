# M2 검수 (REVIEW_M2)

- 작성자: 구현 맥락이 없는 별도 서브에이전트(1회). 아래 "검수 보고" 절은 그 보고를 그대로 옮긴 것이다. 구현자(메인 세션)의 조치는 "조치" 절에 따로 적었다.
- 범위: 기획서 7.1(P0 완료 조건 아홉 항목)과 계획서 M2 표 T2-1~T2-10의 "완료 확인" 열. 읽기 도구와 `SKIP_LONG=1 bash scripts/check.sh`만 사용(종료 코드 0, 101줄: PASS 100, SKIP 1(`lag-3min`), FAIL 0, 단위 테스트 85개 통과).
- 검수는 한 번만 했고 재검수는 하지 않는다(계획서 13장).

## 검수 보고 (서브에이전트 원문 요약)

### 7.1 완료 조건(P0) 아홉 항목

| # | 항목 | 판정 | 근거 |
|---|---|---|---|
| 1 | release APK 설치·실행 | 통과 | `release-*` 13줄 PASS. 서명 확인, debuggable 아님, 권한은 CAR_DRIVE·CAR_LIGHTS·CAR_CLIMATE뿐, distractionOptimized 없음, 가짜 입력 패널 없음 |
| 2 | 기어 D 잠금·소리 정지 / P 복원 | 통과(데이터 기준). 잠금 화면 모양·재생 재개 방식은 사람 확인 대기 | `gear_regression.sh` 21줄 PASS. D 전환 후 엔진 STOP까지 36ms. P 복귀 시 재생은 자동으로 켜지지 않는다(권장 2) |
| 3 | 브레이크·방향지시등 → 소리 + 입력 표시줄 | 입력 표시줄 통과. 실제 소리는 사람 확인 대기 | `vc-brake-input-bar`, `vc-turn-*`, `release-vc-brake-input-bar` PASS. 소리가 났는지를 보는 자동 검사는 없다 |
| 4 | DRUM 녹음 후 반복 | 통과 | `e2e-record-loop` PASS. "2마디 자동 종료 → 버튼으로 종료"는 사용자 결정(PLAN_CHANGES)이라 결함이 아님 |
| 5 | 반복 중 BASS·MELODY·FX 쌓기 | 통과 | `e2e-four-tracks-screen-only`. 실제 차량 입력으로 4트랙을 쌓는 e2e는 없음(화면 패드 기준) |
| 6 | 공조 → 트랙 켜짐/꺼짐 | 통과 | `hvac_equipment.sh` 36줄 PASS. 같은 값 재입력 무시 포함 |
| 7 | 차량 입력 없이 화면만으로 4트랙 | 통과 | `e2e-four-tracks-screen-only` (디버그 플래그로 차량 소스 차단) |
| 8 | 3분 연속 재생 | 통과(`lag3min.log` 기준, 귀로 끊김은 사람 대기) | n=1200, p95 1.02ms, missed=0. 단 필수 1번 참고 |
| 9 | 미지원·콜백 실패에도 앱 안 죽음 | 통과 | `no-fatal-exception`, `SupportTrackerTest`, `InputSourceManagerTest` |

규칙 위반은 찾지 못했다(set API 없음, distractionOptimized 없음, 쓰는 권한만 선언, SDK 호출은 `input/pleos/` 안, 서명 키는 저장소 밖, `.gitignore` 4종 있음).

### 필수

1. **"누락 스텝 0" 검사가 사실상 항상 0이다.** `StepLagStats.noteMissed()`를 호출하는 곳이 없어 `missed`가 상수 0이었다(기준: T2-9 "누락 스텝 0", 7.1 8번). 근거: `AppContainer.kt`의 `onStepFired`가 `add()`만 부름, `check.sh`의 `lag-3min`이 `missed=0`을 통과 조건으로 사용. `Transport.advanceTo`는 늦으면 밀린 스텝을 모두 순서대로 부르므로 실제 누락 가능성은 낮다. 방향: 스텝 번호가 건너뛴 수를 실제로 세거나 지표·문구를 없앤다.
2. **`PROGRESS.md`가 아직 없는 태그와 파일을 완료로 적고 있다.** `p0-complete` 태그와 `docs/REVIEW_M2.md`가 없었다(검수 시점). 방향: 필수 수정 뒤 태그와 파일을 만든다.

### 권장

1. 3분 측정이 실제 부하와 다르다: DRUM 녹음 상태의 메트로놈만 있는 1트랙 조건이고, 지표는 엔진 스레드 지연뿐이며 SoundPool 출력·끊김은 재지 않는다. `SoundPoolSampler.maxStreams=16`이라 4트랙이 빽빽하면 보이스 스틸링 가능성이 있다. 4트랙 곡을 `LoadSong`으로 넣고 같은 측정 + 귀 확인 권장.
2. P 복귀 후 재생이 자동 재개되지 않는다(`PlaybackGuard`, `gear-p-not-autoplay`). 기획서 4.1은 "직전 세션 그대로 복원". PLAN_CHANGES (3)에 구현자가 정한 것으로 기록돼 있고 사용자 결정 표시는 없다. 사용자 확인 후 "잠금 직전에 재생 중이었다면 재개"로 바꿀지 결정 권장.
3. 크래시 검사가 약하다: `no-fatal-exception`은 마지막 `logcat -c` 이후만 본다. 단계마다 FATAL을 검사하거나 로그를 모아 마지막에 검사 권장.
4. `AudioFocusController`가 `AUDIOFOCUS_LOSS` 뒤에도 `held`를 true로 둔다(`AudioFocusController.kt:22-26`). 재생을 다시 누르면 요청 없이 포커스 없이 재생될 수 있다(확신 중간, 실행 확인 안 함). V8 미확인.
5. 소리가 실제로 났는지(오디오 활성, `dumpsys media.audio_flinger`) 확인하는 자동 검사가 없다.
6. 계획서 7장의 JVM 통합 테스트("DRUM → BASS → A/C 토글 → 기어 D → P")가 없다. `FakeInputTest`는 이벤트 방출만 확인.
7. 작은 차이: 잠금 문구가 기획서 4.1("주차(P) 후 이용할 수 있어요")과 다름, 트랙 카드 공조 표시가 아이콘이 아니라 텍스트, 좁은 창 배치는 카메라 창을 피하지 않고 크기 미측정, `release-gear-p-restores`는 release에서 화면 복귀만 보고 루프 데이터는 안 봄.
8. `InputMapper`는 첫 값을 기준값으로만 쓴다. 초기 `get…`이 실패하면 첫 방향지시등·공조 조작이 기준값으로 소비돼 한 번 무시된다(에뮬레이터는 구독 직후 값이 한 번 더 와서 문제없음).
9. 사람 확인이 남은 항목: 모든 샘플 소리와 박자감, 핸들 좌우 방향, 잠금 화면 모양, 줄 반짝임 모양, 3분 재생 끊김, 진짜 멀티 윈도우, T1-5·T0-11·T0-12 잔여.

## 조치 (구현자)

사용자 지시대로 **필수만** 고쳤다. 권장은 고치지 않았고 아래에 사유를 적는다.

| 필수 | 조치 |
|---|---|
| 1 누락 스텝 지표가 상수 0 | 사실 확인: `noteMissed` 호출처 없음(맞음). `LoopEngine.onStepFired`가 절대 스텝 번호를 함께 넘기도록 바꾸고, `StepLagStats.onStep(n, lag)`가 번호가 건너뛴 수를 세게 했다. 번호가 같거나 작아지면 새 재생 구간으로 본다. 단위 테스트 `StepLagStatsTest` 4개(연속=0, 건너뜀=정확한 개수, 재시작은 누락 아님, 시계가 50스텝 뛰어도 모든 스텝 번호를 순서대로 호출). 실제 카운터로 3분 측정을 다시 했다(FINDINGS·PROGRESS에 결과). |
| 2 PROGRESS가 없는 태그·파일을 완료로 적음 | 이 파일을 만들었고, 수정 커밋 뒤 `p0-complete` 태그를 단다. |

| 권장 | 처리 |
|---|---|
| 1~9 | 고치지 않음(사용자 지시: 필수만). 2번(P 복귀 후 자동 재생)은 사용자 결정이 필요해 최종 보고에서 묻는다. 1·4번은 M3 전에 처리하면 좋다. |
