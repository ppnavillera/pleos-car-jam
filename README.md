# P-JAM (피잼)

주차(P단) 중인 차의 조작계로 4트랙 루프를 쌓는 Pleos Connect(Android Automotive) 전용 루프스테이션 앱.
DRUM / BASS / MELODY / FX 네 트랙을 2마디(32스텝) 루프로 녹음하고 겹쳐 쌓는다.

> **프로토타입(2026-11-15, 태그 `prototype-1115b`)입니다.** 아래 "지금 되는 것"만 동작을 확인했습니다. 최종 제출본은 별도로 만듭니다.
> 이 APK는 **debug 서명**이고 디버그 전용 가짜 입력 패널과 디버그 전용 권한이 들어 있습니다. release 서명 APK는 아직 없습니다.

## 실행 방법

1. Pleos Connect 에뮬레이터 `Pleos_Connect_v2.1.0`(Android 14, API 34, 2560×1440 @160dpi)을 켠다.
2. Virtual Console을 연다: `adb forward tcp:8088 tcp:8088` 뒤 브라우저에서 `http://127.0.0.1:8088/`.
3. Virtual Console에서 **기어를 P**로 둔다. D이면 플랫폼이 앱을 가려 앱이 소리를 멈추고 잠금 화면을 띄운다. 터미널에서는 `scripts/vc.sh gear P`.
4. Virtual Console에서 **HVAC를 Power On**으로 둔다. 지금 앱은 공조 값을 쓰지 않지만, Power가 꺼져 있으면 공조 값이 SDK 콜백으로 오지 않는다(측정 결과). 공조 연결을 시험할 때 필요하다.
5. APK 설치: `adb install -r apk/TeamPJAM_PJAM_prototype_debug.apk` (또는 Android Studio에서 `app` 실행). 앱을 연다.
6. 트랙 카드를 눌러 트랙을 고르고 **녹음**을 누른다. 1마디 카운트인 뒤 녹음이 시작된다. 화면 패드로 치거나, Virtual Console에서 브레이크(킥)·방향지시등(좌/우 패드)·핸들(좌/중/우 구간)을 움직인다.
7. 녹음은 **녹음 버튼(■ 녹음 종료)을 다시 누를 때까지** 계속된다. 루프가 돌 때마다 앞 바퀴 내용이 재생되고 새 입력이 그 위에 쌓인다. 녹음 중에는 매 박 메트로놈이 울리고 첫 박이 강하다(트랙에는 기록하지 않는다). 다른 트랙 카드를 누르면 녹음이 끝나고 선택만 바뀐다.

소스에서 빌드: `./gradlew assembleDebug` (JDK는 Android Studio 내장 JBR 사용). 전체 검증: `scripts/check.sh` (에뮬레이터가 켜져 있고 기어가 P여야 한다).

## 지금 되는 것 / 안 되는 것

| 상태 | 항목 |
|---|---|
| 되는 것 | 4트랙 루프 엔진: 카운트인 → 녹음(종료 버튼까지 계속, 바퀴마다 쌓기) → 반복, 트랙 시작 순간 한 번만 지우기, 재생 중 다른 트랙 녹음, 트랙 뮤트·지우기, 템포 70–140 BPM |
| 되는 것 | 화면 패드 연주, 입력 표시줄, 트랙 카드의 32스텝 격자와 재생 위치 |
| 되는 것 | Vehicle SDK 입력 3종: 브레이크(눌림 → 킥), 방향지시등(좌/우 → 패드), 핸들 각도(좌/중/우 구간). Virtual Console 조작이 앱에 도달함을 `scripts/check.sh`로 확인 |
| 되는 것 | 기어 잠금: 기어가 P가 아니면(Vehicle SDK `Gear` + `CarUxRestrictions` 두 신호) 약 30ms 안에 소리를 멈추고 잠금 화면을 띄운다. 녹음 중이면 녹음을 끝내고 내용은 유지한다. P로 돌아오면 내용이 그대로이고 재생은 정지 상태(▶로 다시 시작) |
| 되는 것 | 앱 전환(백그라운드) 시 같은 경로로 정지. 방향지시등이 띄우는 플랫폼 카메라 창은 백그라운드로 보지 않는다 |
| 되는 것 | SDK 초기화 실패·Car 라이브러리 없음(일반 에뮬레이터)에서도 크래시 없이 화면 조작만으로 동작 |
| 되는 것 | 디버그 빌드의 가짜 차량 입력 패널(브레이크·방향지시등·핸들·공조 버튼)과 "잠금 강제" 버튼 |
| 확인 안 됨 | 핸들 좌/우 방향(SDK 음수 = 왼쪽으로 구현, 화면으로 확인하지 못함), 모든 샘플의 소리 확인, 새 녹음 방식의 손맛, 소리가 스피커로 나오기까지의 출력 지연, 오디오 포커스 이동(T0-12), 멀티 윈도우 크기(T0-11) |
| 안 되는 것 | 공조 4종 Toggle(A/C·AUTO·내기·성에)·풍량→템포·온도→조: SDK로는 읽히지만(`docs/FINDINGS.md` V2) 앱에 아직 연결하지 않았다 |
| 안 되는 것 | 와이퍼(켜짐 → 이펙트 한 번으로 축소 예정), 창문, 드라이브 모드(에뮬레이터 미지원, 화면 버튼으로 대체 예정) |
| 안 되는 것 | 보관함(저장), 설정, AUTO 연동 옵션, 세로·좁은 화면 배치(일반 에뮬레이터 세로 화면에서는 상단 버튼이 밀려 1트랙 녹음을 못 했다), release 서명 APK |

## 테스트 환경

- 개발 PC: macOS (Apple Silicon), Android Studio
- 에뮬레이터: Pleos Connect Emulator `Pleos_Connect_v2.1.0`, Android 14 (API 34), 2560×1440 @160dpi, Vehicle Service 2.3.22
- 빌드: AGP 9.5.1, Kotlin 2.4.0, Jetpack Compose, minSdk 28 / compileSdk 36 / targetSdk 34
- 사용 SDK: Pleos Vehicle SDK `ai.pleos.playground:Vehicle:2.3.2`
- 권한: release 매니페스트는 `CAR_DRIVE`, `CAR_LIGHTS`만 선언한다. 디버그 매니페스트에만 `android.car.permission.CAR_POWERTRAIN`, `pleos.car.permission.CAR_CLIMATE`·`CAR_WINDOWS`·`CAR_ENERGY`가 더 있다(측정용). 이 APK는 debug 빌드라 모두 포함한다.

## 사운드 출처

모든 샘플은 `tools/soundgen/generate.py`로 직접 합성했다(외부 샘플 없음). 자세한 내용은 `assets/soundpacks/LICENSES.md`.

## 문서

- 진행 상태: `docs/PROGRESS.md`
- 검증 결과·에뮬레이터 관찰: `docs/FINDINGS.md`
- 계획서와 달라진 점: `docs/PLAN_CHANGES.md`
