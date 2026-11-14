# 진행 기록

## 현재 상태
- 끝난 작업: **필수 범위 P1~P6 완료** (T0-1, T0-4, T1-1~T1-7, T1-9), T1-8 일부(InputMapper 순수 로직), T0-0(Virtual Console HTTP 조작 성공)
- 확인 대기([수동]): 없음 (P9에서 한 번에 요청)
- 다음 할 일: P7 (SDK 시그니처 조사 서브에이전트 진행 중 → PleosVehicleInputSource 브레이크·방향지시등 연결), P8 보강, P9 마감(21:00)
- 막힌 것 / 대체 경로로 넘어간 것: 없음

## 기록 (최신이 위)
- 11/15 P3~P6 완료. 단위 테스트 43개, check.sh 전체 PASS. e2e: 화면 패드로 DRUM 3노트 녹음 → 녹음 후 루프 wrap 2회 이상(자동 반복) → 격자 UI 3칸 켜짐을 adb로 확인. 소리가 실제로 들리는지는 귀로 확인 못 함([수동], P9).
- 11/15 환경 이슈: 에뮬레이터 기어가 D여서 앱이 플랫폼에 가려짐 → `scripts/vc.sh gear P`로 해결(T0-0 ② 성공, FINDINGS).
- 11/15 P2 완료. 데이터 모델·Quantizer·Transport(순수 advanceTo 코어) + 단위 테스트 13개 통과, check.sh 통과.
- 11/15 T1-4 완료. tools/soundgen로 basic 팩 27개 합성(이름 규약은 tools/soundgen/README.md), verify.py 통과. 귀로 확인은 못 함(P9 수동 요청에 포함).
- 11/15 P1 완료. `assembleDebug`·`lintDebug`·`testDebugUnitTest` 통과, 에뮬레이터 설치·실행·`pjam_root` 노드 확인. Vehicle SDK 2.3.2 해석 성공. 툴체인은 템플릿(AGP 9.5.1) 유지 (FINDINGS "문서와 다른 점").
