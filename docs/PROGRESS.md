# 진행 기록

## 현재 상태
- 끝난 작업: **필수 범위 P1~P6 완료** (T0-1, T0-4, T1-1~T1-7, T1-9), T1-8 일부(InputMapper 순수 로직), T0-0(Virtual Console HTTP 조작 성공), **P7 완료**(브레이크·방향지시등 실제 SDK 연결), P8 완료(4트랙 e2e)
- 확인 대기([수동]): 없음 (P9에서 한 번에 요청)
- 다음 할 일: P9 마감 절차(아직 시간 있음. 21:00 전까지 안정화·기어 잠금 등은 하지 않기로 한 범위 밖). M0(11/16~): 나머지 V1·V2, 핸들, 공조, 기어 V3
- 막힌 것 / 대체 경로로 넘어간 것: 없음

## 기록 (최신이 위)
- 11/15 P7 완료. SDK 시그니처는 서브에이전트가 javap로 조사 → 컴파일로 검증(FINDINGS V1). 브레이크(Boolean)·방향지시등(OFF/RIGHT/LEFT)이 Virtual Console 조작으로 앱에 도달해 입력 표시줄에 뜨는 것을 check.sh가 확인(`vc-*`). check.sh 전체 PASS.
- 11/15 P8 완료. e2e에 DRUM 반복 중 BASS 녹음(DRUM 유지) 추가, 통과.
- 11/15 P3~P6 완료. 단위 테스트 43개, check.sh 전체 PASS. e2e: 화면 패드로 DRUM 3노트 녹음 → 녹음 후 루프 wrap 2회 이상(자동 반복) → 격자 UI 3칸 켜짐을 adb로 확인. 소리가 실제로 들리는지는 귀로 확인 못 함([수동], P9).
- 11/15 환경 이슈: 에뮬레이터 기어가 D여서 앱이 플랫폼에 가려짐 → `scripts/vc.sh gear P`로 해결(T0-0 ② 성공, FINDINGS).
- 11/15 P2 완료. 데이터 모델·Quantizer·Transport(순수 advanceTo 코어) + 단위 테스트 13개 통과, check.sh 통과.
- 11/15 T1-4 완료. tools/soundgen로 basic 팩 27개 합성(이름 규약은 tools/soundgen/README.md), verify.py 통과. 귀로 확인은 못 함(P9 수동 요청에 포함).
- 11/15 P1 완료. `assembleDebug`·`lintDebug`·`testDebugUnitTest` 통과, 에뮬레이터 설치·실행·`pjam_root` 노드 확인. Vehicle SDK 2.3.2 해석 성공. 툴체인은 템플릿(AGP 9.5.1) 유지 (FINDINGS "문서와 다른 점").
