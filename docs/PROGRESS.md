# 진행 기록

## 현재 상태
- 끝난 작업: M-P 전부(P1~P9), **M0(T0-0~T0-13 중 T0-12 제외)**, M1의 T1-6 재작성(녹음 방식 변경)·T1-8(핸들 구간)·T1-10(브레이크·방향지시등·핸들)·T1-11(기어 잠금)·T1-12(입력 소스 실패 대비). `scripts/check.sh` 전체 PASS(기어 회귀 21줄 포함), 단위 테스트 통과. 기어 잠금은 SDK 기어 + CarUxRestrictions 두 신호.
- 확인 대기([수동], 사용자): 아래 "내가 귀·눈으로 확인할 것" 참고
  - 에뮬레이터 화면에서 DRUM 녹음→반복을 눈으로 확인 (11/15 소리·브레이크→킥·입력 표시줄은 사용자 확인 완료)
  - 새 녹음 방식(2마디 뒤에도 계속 쌓임, 녹음 버튼으로 종료, 녹음 중 메트로놈 매 박)이 손에 맞는지
  - 핸들: Virtual Console에서 + 값이 화면상 어느 쪽으로 도는지(SDK 음수 = 왼쪽으로 구현함. 반대면 `PleosVehicleInputSource.STEERING_LEFT_IS_NEGATIVE`만 뒤집는다)
  - 디버그 "잠금 강제" 버튼으로 뜨는 잠금 화면의 모양
  - T1-5: 모든 샘플 귀로 확인, T2-2 BASS/MELODY 연주감
- 다음 할 일: M2(T2-1 나머지, T2-2~T2-10). 공조 4종 Toggle(T2-5)은 V2 결과대로 SDK 구독을 `PleosVehicleInputSource`에 추가하고 릴리스 매니페스트에 CAR_CLIMATE를 넣는다. 반응형 배치(T2-6, 세로 좁은 화면에서 상단 버튼이 밀림 확인됨). T0-12(오디오 포커스 V8)와 T0-11(화면 규격)은 아직 측정 안 함
- 막힌 것 / 대체 경로로 넘어간 것
  - 기어: 계획서의 "SDK로 못 읽는다"는 틀렸고 SDK `Gear`로 읽힌다. 3.6 단계 1에 해당(FINDINGS V3, PLAN_CHANGES).
  - 지연 측정의 "화면 녹화(60fps)로 클릭~로그 표시" 부분은 에이전트가 못 해서 PUT 이후만 쟀다. [수동]
  - `inject-vhal-event`는 user 빌드라 불가 → Virtual Console HTTP API로 대체(성공).
  - 2026-11-15 `check.sh` 한 번 기어 회귀 3줄이 실패했다가 같은 코드로 재실행 4회(전체 1 + 단독 3) 모두 통과. 일반 에뮬레이터를 막 끈 직후 실행이었다는 것까지만 확인, 원인 미상. 다시 보이면 `build/check-artifacts/gear-6-park-all.log`에 PJAM_* 로그가 남는다.

## 내가 귀·눈으로 확인할 것 (사용자용 요약)
1. 녹음: DRUM 녹음 → 2마디가 지나도 안 끝나고 계속 쌓이는지, 녹음 중 매 박 메트로놈이 나고 첫 박이 더 큰지, 녹음 버튼으로 끝내면 메트로놈이 꺼지고 루프만 도는지. 다른 트랙 카드를 누르면 녹음이 끝나고 선택만 바뀌는지.
2. 기어: 루프를 돌린 채 `scripts/vc.sh gear D` → 플랫폼이 화면을 가리는 즉시 소리가 멈추는지, `gear P`로 돌아오면 칸이 그대로이고 재생은 멈춰 있는지(▶를 눌러야 다시 재생).
3. 방향지시등을 켜면 작은 카메라 창이 앱 위에 뜨는데(플랫폼 동작), 그 상태에서도 소리가 나는지.
4. 핸들: Virtual Console 핸들 슬라이더를 움직여 입력 표시줄이 "핸들 좌/중/우"로 바뀌는데 방향이 맞는지.

## 미룬 완료 확인 (M1/M2에서 채움)
- 끝남: T1-2/T1-3 경계 테스트(정확한 박, 반 스텝, 31.6→0, 음수, BPM 70/100/140, 1000스텝 오차 0, 템포 변경 후 스텝 번호 연속 — 이미 있었음), T1-6 겹침·경계(새 엔진 테스트 53개: 2마디 뒤 계속, 앞 바퀴 재생·쌓기, 시작 순간 한 번만 지우기, 매 박 메트로놈, 이중 발음, 템포 변경, FinishRecord, 정지 시 종료), T1-8 핸들 구간(실제 SDK로 check.sh `vc-steering-*`), T1-10 핸들, T1-11 기어 잠금(`PlaybackGuard` 단위 테스트 8개 + `gear_regression.sh`), T1-12 SDK 초기화 실패 주입 테스트(`InputSourceManagerTest`, Throwable·NoClassDefFoundError 포함)와 일반 에뮬레이터 실행(크래시 없음)
- 남음: T1-5 `[수동]` 모든 샘플 귀로 확인·포커스 잃으면 정지, T1-8 AUTO 연동 옵션(T3-9), T2-2 BASS/MELODY 연주감, T2-9 강제 예외 주입 테스트, 반응형 배치, 저장·보관함, 일반 에뮬레이터에서 1트랙 녹음(세로 화면에서 상단 버튼이 밀려 못 함)

## 기록 (최신이 위)
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
