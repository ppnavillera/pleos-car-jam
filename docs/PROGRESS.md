# 진행 기록

## 현재 상태
- 끝난 작업: **M-P 전부(P1~P9)**. 필수 P1~P6, 선택 P7(브레이크·방향지시등 실제 SDK)·P8(4트랙) 포함. check.sh 22개 PASS. 태그 `prototype-1115`(push 안 함)
- 확인 대기([수동]): 에뮬레이터 화면에서 DRUM 녹음→반복을 눈으로 확인 (소리 확인과 Virtual Console 브레이크 → 킥·입력 표시줄은 11/15 사용자가 귀·눈으로 확인 완료)
- 다음 할 일: M0(11/16~): 나머지 V1·V2(14개 항목), 핸들 V4, 공조, 기어 V3(벤더 속성 0x21400400 단서), V6 지연, V8 오디오 포커스, T0-13 SoundPool 박자 측정(G2) → `docs/PLAN_CHANGES.md` 작성. 이어서 M1(T1-11 기어 잠금, 미룬 완료 확인)
- 막힌 것 / 대체 경로로 넘어간 것: 기어를 못 읽어 3.6의 4단계("P로 간주 + 기어 확인 불가")로 둠(범위 밖으로 정한 것). `inject-vhal-event`는 user 빌드라 불가 → Virtual Console HTTP API로 대체(성공)

## 미룬 완료 확인 (M1/M2에서 채움)
- T1-2/T1-3 나머지 경계 테스트, T1-5 `[수동]` 모든 샘플 귀로 확인·포커스 잃으면 정지, T1-6 겹침/경계 일부, T1-8 AUTO 연동 옵션·핸들 구간(실제 SDK 연결 전), T1-10 핸들, T1-11 기어 잠금, T1-12 SDK 초기화 실패 주입 테스트, T2-2 BASS/MELODY 연주감, T2-9 3분 연속 재생 측정(p95<15ms), 반응형 배치, 저장·보관함

## 기록 (최신이 위)
- 11/15 P9 마감. check.sh 22 PASS, APK `apk/TeamPJAM_PJAM_prototype_debug.apk`(디버그 서명, 33MB), README 최소본, 태그 `prototype-1115`. push는 하지 않음. 소리 간접 확인: dumpsys audio에 포커스 GAIN 보유, AudioOut 스레드 standby 아님.
- 11/15 P7 완료. SDK 시그니처는 서브에이전트가 javap로 조사 → 컴파일로 검증(FINDINGS V1). 브레이크(Boolean)·방향지시등(OFF/RIGHT/LEFT)이 Virtual Console 조작으로 앱에 도달해 입력 표시줄에 뜨는 것을 check.sh가 확인(`vc-*`). check.sh 전체 PASS.
- 11/15 P8 완료. e2e에 DRUM 반복 중 BASS 녹음(DRUM 유지) 추가, 통과.
- 11/15 P3~P6 완료. 단위 테스트 43개, check.sh 전체 PASS. e2e: 화면 패드로 DRUM 3노트 녹음 → 녹음 후 루프 wrap 2회 이상(자동 반복) → 격자 UI 3칸 켜짐을 adb로 확인. 소리가 실제로 들리는지는 귀로 확인 못 함([수동], P9).
- 11/15 환경 이슈: 에뮬레이터 기어가 D여서 앱이 플랫폼에 가려짐 → `scripts/vc.sh gear P`로 해결(T0-0 ② 성공, FINDINGS).
- 11/15 P2 완료. 데이터 모델·Quantizer·Transport(순수 advanceTo 코어) + 단위 테스트 13개 통과, check.sh 통과.
- 11/15 T1-4 완료. tools/soundgen로 basic 팩 27개 합성(이름 규약은 tools/soundgen/README.md), verify.py 통과. 귀로 확인은 못 함(P9 수동 요청에 포함).
- 11/15 P1 완료. `assembleDebug`·`lintDebug`·`testDebugUnitTest` 통과, 에뮬레이터 설치·실행·`pjam_root` 노드 확인. Vehicle SDK 2.3.2 해석 성공. 툴체인은 템플릿(AGP 9.5.1) 유지 (FINDINGS "문서와 다른 점").
