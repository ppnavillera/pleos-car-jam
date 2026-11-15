# 진행 기록

## 현재 상태
- 끝난 작업: M-P 전부, M0(T0-12 제외), M1 전부(T1-5 [수동] 제외), **M2 전부(T2-1~T2-10)**. `scripts/check.sh` **101줄 전부 PASS**(기어 회귀, 3분 재생, release 스모크 포함), 단위 테스트 89개 통과. M2 검수 `docs/REVIEW_M2.md`(필수 2건 조치: 누락 스텝 지표가 상수 0이던 것 수정, 태그·문서 작성), 태그 `p0-complete`. 검수 권장 9건은 고치지 않았다(2번 P 복귀 후 재생은 사용자 결정으로 구현함, PLAN_CHANGES (6)).
- 이번 M2에서 한 것: 방향지시등 카메라 창 측정(FINDINGS), 공조 4종 + 전원 SDK 연결과 6.3 배정 규칙, 핸들 대체 3버튼, 두 열 반응형 배치(카메라 안전 구역), "내 차 장비" 화면, 화면만으로 4트랙 e2e(차량 소스 비활성 디버그 플래그), 창 크기 변경 시 유지, 콜백 try/catch(`SupportTracker`), 3분 재생 측정, release 서명·빌드·설치 스모크, `docs/DEMO.md` 초안, README 갱신.
- 사용자 결정 반영(PLAN_CHANGES (5)): CAR_POWERTRAIN 릴리스 제외, 드라이브 모드는 구독하지 않음(화면 버튼으로만), 와이퍼는 꺼짐→켜짐마다 없음→약→강 한 칸씩(구현은 T3-7).
- **사용자 확인 결과(11/15 두 번째 지시)**: 확인 결과 칸(핸들 방향, 4트랙 3분, BASS/MELODY 연주감, 카메라 창, 잠금 화면, REVIEW_M2 권장 수정)이 모두 비어 있어 반영할 보고가 없었다. `STEERING_LEFT_IS_NEGATIVE`와 REVIEW_M2 권장 수정은 건드리지 않았다(계속 확인 대기).
- **사용자 결정 반영(PLAN_CHANGES (6))**: 잠금 직전에 재생 중이었다면 P 복귀 때 루프 처음부터 재생을 이어 간다(정지 상태였으면 정지, 녹음은 이어 가지 않음). 앱 전환 복귀는 정지 유지(구현자 판단). 와이퍼 순환(없음→약→강→없음) 확정.
- 확인 대기([수동], 사용자): 아래 "내가 귀·눈으로 확인할 것"
- 다음 할 일: M3(P1). T3-1 풍량→템포, T3-2 저장·보관함, T3-3 조용한 연주 모드·설정, … T0-12(오디오 포커스 V8)와 T0-11(진짜 멀티 윈도우 크기)은 아직 측정 안 함.
- release 서명 키: `~/.pjam-signing/`(저장소 밖). 분실하면 같은 서명으로 업데이트할 수 없으니 백업할 것.
- 막힌 것 / 대체 경로로 넘어간 것
  - 기어: 계획서의 "SDK로 못 읽는다"는 틀렸고 SDK `Gear`로 읽힌다(FINDINGS V3).
  - 지연 측정의 "화면 녹화(60fps)로 클릭~로그 표시" 부분은 에이전트가 못 해서 PUT 이후만 쟀다. [수동]
  - `inject-vhal-event`는 user 빌드라 불가 → Virtual Console HTTP API로 대체(성공).
  - 진짜 멀티 윈도우 분할은 시험하지 못했다. 앱 폭을 바꾸는 `wm density`로 좁은 배치만 확인(FINDINGS M2 실측).
  - check.sh 기어 회귀: 11/15 한 번 3줄 실패했다가 재실행 모두 통과한 일이 있었고(원인 미상, 일반 에뮬레이터를 막 끈 직후), M2 최종 전체 실행(101줄)에서는 실패하지 않았다. 다시 보이면 `build/check-artifacts/gear-6-park-all.log`에 PJAM_* 로그가 남는다.
  - 시험 중 에이전트가 `am start -W`가 한 번 멈춘 적이 있다(시계 앱이 앞으로 와 있었음). 시계 앱을 강제 종료하고 다시 하니 정상. 앱 문제로 보이지 않는다.

## 내가 귀·눈으로 확인할 것 (사용자용 요약)
1. 핸들: Virtual Console 핸들 슬라이더를 움직여 입력 표시줄이 "핸들 좌/중/우"로 바뀌는 방향이 맞는지. BASS 선택 후 핸들 좌=4도, 우=5도, MELODY는 좌=낮은 음역, 우=높은 음역. 반대면 `PleosVehicleInputSource.STEERING_LEFT_IS_NEGATIVE`만 뒤집는다.
2. 녹음·연주감: DRUM → BASS → MELODY → FX를 차례로 쌓을 때 박자가 맞게 들리는지, 새 녹음 방식(버튼으로 끝냄, 매 박 메트로놈)이 손에 맞는지, BASS/MELODY 배치가 어색하지 않은지. 모든 샘플 소리 확인(T1-5).
3. 공조: Virtual Console에서 A/C·AUTO·내기순환·성에 제거를 바꾸면 DRUM·BASS·MELODY·FX가 꺼지고 켜지는 소리가 맞는지. 3분 연속 재생에서 끊김·박자 흔들림이 안 들리는지(스텝 오차 p95는 0.85ms로 양호하지만 최대값이 두 번 측정 모두 15ms를 넘었다: 24.8ms, 31.0ms 각 한 번, 원인 미상). 4트랙이 가득 찬 곡에서도.
4. 방향지시등을 켜면 카메라 창이 앱 왼쪽 위에 뜨는데 조작 부분(카드·버튼·입력 표시줄·패드)이 안 가려지는지, 그 상태에서 소리가 나는지.
5. "내 차 장비": 조작할 때 해당 줄이 반짝이는 모양, 공조 전원이 꺼져 있을 때 안내 문구.
6. 기어 D → 잠금 화면 모양과 소리 정지, P 복귀 후 루프 유지.
7. 좁은 창(멀티 윈도우)에서 화면이 쓸 만한지.

## 미룬 완료 확인 (M1/M2에서 채움)
- 끝남: T1-2/T1-3 경계 테스트(정확한 박, 반 스텝, 31.6→0, 음수, BPM 70/100/140, 1000스텝 오차 0, 템포 변경 후 스텝 번호 연속 — 이미 있었음), T1-6 겹침·경계(새 엔진 테스트 53개: 2마디 뒤 계속, 앞 바퀴 재생·쌓기, 시작 순간 한 번만 지우기, 매 박 메트로놈, 이중 발음, 템포 변경, FinishRecord, 정지 시 종료), T1-8 핸들 구간(실제 SDK로 check.sh `vc-steering-*`), T1-10 핸들, T1-11 기어 잠금(`PlaybackGuard` 단위 테스트 8개 + `gear_regression.sh`), T1-12 SDK 초기화 실패 주입 테스트(`InputSourceManagerTest`, Throwable·NoClassDefFoundError 포함)와 일반 에뮬레이터 실행(크래시 없음)
- 남음: T1-5 `[수동]` 모든 샘플 귀로 확인·포커스 잃으면 정지, T1-8 AUTO 연동 옵션(T3-9), T2-2 BASS/MELODY 연주감 [수동], 저장·보관함(M3), 일반 에뮬레이터에서 1트랙 녹음(세로 화면 상단 버튼 밀림은 T2-6 한 열 배치로 해결했으나 다시 시험하지 않음)

## 기록 (최신이 위)
- 11/15 M3 진행(사용량 한도로 여기서 멈춤): T3-1 풍량→템포, T3-2 저장·보관함(예시 4트랙 곡 포함), T3-3 조용한 연주 모드·설정, T3-5 차량 사운드팩(화면 버튼 전환), T3-4 온도→조(코드·단위 테스트 완료, e2e 한 줄 재확인 전)까지 했다. 남은 것: T3-6 함께 쌓기, T3-7 창문·와이퍼(릴리스에 CAR_WINDOWS 추가와 release 권한 검사 갱신 필요), T3-8 첫 실행 안내, T3-9 AUTO 연동 무시 옵션, T3-10 세션 자동 저장. 충전 세션은 제외(PLAN_CHANGES (7)). 최대 지연 조사: FINDINGS 참고(재현 안 됨). 이번 변경 이후 전체 `scripts/check.sh`와 release 빌드는 다시 돌리지 못했다. 접수용 APK는 태그 `submission-draft-1115` 시점 그대로다.
- 11/15 접수용 스냅샷 2 `submission-draft-1115`: P 복귀 시 재생 이어 가기 구현(엔진 `Start` 명령 + `PlaybackGuard` 재개 훅, 단위 테스트 추가), `gear_regression.sh`를 새 동작에 맞게 고치고 정지 중 D→P·녹음 중 D→P 시나리오 추가, release APK `apk/TeamPJAM_PJAM_release.apk`(26.8MB, 서명, 권한 CAR_DRIVE·CAR_LIGHTS·CAR_CLIMATE), README를 기획서 8장 필수 5항목 순서로 재작성, DEMO.md에 "시연은 전체 화면으로". `SKIP_LONG=1 scripts/check.sh` 전부 PASS. push는 하지 않음.
- 11/15 M2: T2-1~T2-10 완료. `check.sh` 101줄 PASS. 서브에이전트 검수 → `docs/REVIEW_M2.md`(필수만 수정), 태그 `p0-complete`. push는 하지 않음.
- 11/15 접수용 스냅샷 `prototype-1115b`: 새 기능 없음. M0·M1 반영본으로 debug APK 교체(`apk/TeamPJAM_PJAM_prototype_debug.apk`, 33.5MB, debug 서명·debug 전용 권한 포함), README의 되는 것/안 되는 것·실행 방법 갱신, 사용자가 고친 CLAUDE.md 포함. `scripts/check.sh` 전부 PASS. push는 하지 않음.
- 11/15 M0 측정 결과: 14개 중 13개 SDK로 읽힘(드라이브 모드만 미지원), 핸들 −500..470 부호 반대, PUT→콜백 평균 6.6ms, 스텝 호출 지연 p95 0.74ms(G2 통과). FINDINGS V1·V2·V4·V6·오디오 재생 측정.
- 11/15 기어 잠금(T0-7): SDK Gear 콜백으로 D 전환 후 약 30ms에 소리 정지. 방향지시등이 플랫폼 카메라 창을 띄워 onPause를 일으켜서 백그라운드 기준을 onStop으로 정함. `SoundPoolSampler.stopAll`이 멈추지 못하던 버그 수정. FINDINGS V3.
- 11/15 녹음 방식 변경(T1-6): 사용자 결정으로 2마디 자동 종료 폐지. PLAN_CHANGES.
- 11/15 P9 마감. check.sh 22 PASS, APK `apk/TeamPJAM_PJAM_prototype_debug.apk`(디버그 서명, 33MB), README 최소본, 태그 `prototype-1115`. push는 하지 않음.
- 11/15 P7 완료. SDK 시그니처는 서브에이전트가 javap로 조사 → 컴파일로 검증(FINDINGS V1). 브레이크(Boolean)·방향지시등(OFF/RIGHT/LEFT)이 Virtual Console 조작으로 앱에 도달.
- 11/15 P8 완료. e2e에 DRUM 반복 중 BASS 녹음(DRUM 유지) 추가.
- 11/15 P3~P6 완료. 단위 테스트, e2e(화면 패드로 DRUM 녹음 → 자동 반복 → 격자 UI 확인).
- 11/15 환경 이슈: 에뮬레이터 기어가 D여서 앱이 플랫폼에 가려짐 → `scripts/vc.sh gear P`로 해결(T0-0 ② 성공, FINDINGS).
- 11/15 P2 완료. 데이터 모델·Quantizer·Transport(순수 advanceTo 코어) + 단위 테스트.
- 11/15 T1-4 완료. tools/soundgen로 basic 팩 27개 합성, verify.py 통과.
- 11/15 P1 완료. `assembleDebug`·`lintDebug`·`testDebugUnitTest` 통과, 에뮬레이터 설치·실행. Vehicle SDK 2.3.2 해석 성공. 툴체인은 템플릿(AGP 9.5.1) 유지.
