# 진행 기록

## 현재 상태
- 끝난 작업: P1 (T0-1, T0-4), T1-4(사운드 합성 27개), P2 (T1-1~T1-3)
- 확인 대기([수동]): 없음 (P9에서 한 번에 요청)
- 다음 할 일: P3 (T1-5 SoundPoolSampler), P4 (LoopEngine), P5 (입력·디버그 패널), P6 (스튜디오 화면)
- 막힌 것 / 대체 경로로 넘어간 것: 없음

## 기록 (최신이 위)
- 11/15 P2 완료. 데이터 모델·Quantizer·Transport(순수 advanceTo 코어) + 단위 테스트 13개 통과, check.sh 통과.
- 11/15 T1-4 완료. tools/soundgen로 basic 팩 27개 합성(이름 규약은 tools/soundgen/README.md), verify.py 통과. 귀로 확인은 못 함(P9 수동 요청에 포함).
- 11/15 P1 완료. `assembleDebug`·`lintDebug`·`testDebugUnitTest` 통과, 에뮬레이터 설치·실행·`pjam_root` 노드 확인. Vehicle SDK 2.3.2 해석 성공. 툴체인은 템플릿(AGP 9.5.1) 유지 (FINDINGS "문서와 다른 점").
