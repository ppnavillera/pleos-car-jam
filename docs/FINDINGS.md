# FINDINGS

## 환경
- 개발 PC OS / Android Studio / JDK: macOS (Darwin 27.2.0, Apple M1 arm64) / Android Studio AI-262.10968.63 / Android Studio 내장 JBR (빌드 JDK). 바이트코드 타깃 17
- 빌드 도구: AGP 9.5.1, Gradle 9.6.0, Kotlin 2.4.0 (Compose 컴파일러 플러그인 동일 버전), Compose BOM 2025.10.00
- SDK 레벨: compileSdk 36, targetSdk 34, minSdk 28
- AVD 이름 / 시스템 이미지: `Pleos_Connect_v2.1.0` / Android 14 (API 34), arm64-v8a
- Vehicle Service 버전: 2.3.22 (`ai.pleos.playground.service.vehicle` versionName)
- Vehicle SDK 버전(실제 사용): `ai.pleos.playground:Vehicle:2.3.2` (Nexus에서 내려받기 성공, 2.3.1 폴백 불필요)
- Virtual Console 버전: 미확인 (`ai.pleos.connectwebconsole` 패키지 존재. dumpsys versionName=14는 플랫폼 값으로 보이며 의미 불확실)

## V1 시그니처
Vehicle SDK 2.3.2 AAR의 classes.jar를 javap로 확인(sources jar 없음) + 컴파일·실행으로 검증. 패키지 접두 `ai.pleos.playground.vehicle`. 모든 메서드는 비-suspend, 결과는 람다(`onComplete`/`onFailed`)로 온다. **Brake, TurnSignal만 확인(P7). 나머지 도메인은 M0(T0-5).**

| 도메인.메서드 | 파라미터 | 콜백·리스너 타입 | 값 타입·단위·enum | area 필요 | @RequiresPermission |
|---|---|---|---|---|---|
| Brake.checkBrakePedalStatusCapabilities | onComplete:(CapabilityConfig)->Unit, onFailed:(Exception)->Unit | 람다 | `api.model.capability.CapabilityConfig`: `accessType: AccessType(NONE/READ/WRITE/READ_WRITE)`만. min/max·areaBounds 없음 | 없음 | `pleos.car.permission.CAR_DRIVE` |
| Brake.getCurrentBrakePedalStatus | onComplete:(Boolean?)->Unit, onFailed | 람다 | **Boolean?** (눌림 true). 0–100% 수치가 아님 | 없음 | CAR_DRIVE |
| Brake.registerBrakePedalStatus / unregister… | listener: `listener.BrakePedalStatusListener` | `onBrakePedalStatusUpdated(status: Boolean?)` + `VehicleListener.onFailed(Exception)` | Boolean? | 없음 | CAR_DRIVE |
| TurnSignal.checkTurnSignalStateCapabilities | 위와 같음 | 람다 | CapabilityConfig(accessType) | 없음 | `pleos.car.permission.CAR_LIGHTS` |
| TurnSignal.getCurrentTurnSignalState | onComplete:(TurnSignalValue?)->Unit, onFailed | 람다 | `constant.area.TurnSignalValue`: **OFF(0), RIGHT(1), LEFT(2)**. Blink/Flash 구분 없음 | 없음 | CAR_LIGHTS |
| TurnSignal.registerTurnSignalState / unregister… | listener: `listener.TurnSignalStateListener` | `onTurnSignalStateUpdated(state: TurnSignalValue?)` + onFailed | enum | 없음 | CAR_LIGHTS |
| Gear.getCurrentGearSelectionState | onComplete:(VehicleGear)->Unit, onFailed | 람다 | `constant.control.VehicleGear`: GEAR_UNKNOWN, NEUTRAL, REVERSE, **PARK**, DRIVE, GEAR_1..9. capability 확인 메서드 없음 | 없음 | `pleos.car.permission.CAR_DRIVE` |
| Gear.registerGearSelection / unregister… | listener: `listener.GearSelectionListener` | `onSelected(gear: VehicleGear?)` + onFailed | VehicleGear? (**nullable로 오버라이드해야 컴파일됨**) | 없음 | CAR_DRIVE |
| Vehicle | 생성자 `Vehicle(Context)`, `initialize()`, `release()` (반환·콜백 없음) | — | 접근자는 **`getBrake()` / `getTurnSignal()` 메서드 호출**로만 된다 (`vehicle.brake` 프로퍼티 구문은 컴파일 오류: private) | — | — |

- AAR 매니페스트: `uses-permission ai.pleos.playground.service.vehicle.VEHICLE_BINDING`, `<queries>` 액션 `ai.pleos.playground.vehicle.VEHICLE_START`, 서비스 최소 버전 2.3.13. 앱은 `pleos.car.permission.CAR_DRIVE`, `CAR_LIGHTS`를 선언했고 설치·호출이 모두 성공했다.
- `initialize()` 완료 콜백은 공개 API에 없다. 바로 `check…Capabilities`를 불러도 첫 시도에 성공했다(재시도 로직 `retryLater`는 안전장치로 둠, 한 번도 발동하지 않음).
- **콜백 스레드: Binder 스레드로 확인** (`binder:<pid>_N`, 메인 아님). 콜백마다 다른 Binder 스레드일 수 있다 → 시각만 찍고 mapper(동기화) → tryEmit.
- 구독 직후 현재 값이 콜백으로 한 번 더 들어온다(초기 `get`과 listener 둘 다 `value=false`) → InputMapper의 "첫 값은 기준값" 규칙으로 걸러진다.

**실측 (에뮬레이터 Pleos_Connect_v2.1.0, 2026-11-15, `scripts/vc.sh`로 조작):**
- Brake: capability `READ`. Virtual Console 카드는 0–100%이지만 SDK에는 Boolean으로만 온다: brake=100 → `true`, brake=0 → `false`. (5%/2% 임계값 로직은 SDK에서는 true/false를 100/0으로 바꿔 통과시킨다.)
- TurnSignal: capability `READ`. BlinkLeft → `LEFT`, FlashRight → `RIGHT`, Off → `OFF`. 깜빡임 중 반복 콜백은 10초 유지 측정은 안 함(V5 미완).
- 입력 표시줄: "브레이크 → 킥", "방향지시등 좌 → 스네어", "방향지시등 우 → 하이햇" 확인(check.sh: `vc-brake-input-bar`, `vc-turn-*`).
- 문서와 다른 점: CLAUDE.md/계획서의 "브레이크는 0–100% 값" 전제는 Virtual Console 카드에만 해당한다. SDK 값은 Boolean.

## V2 에뮬레이터 지원
M0에서 14개 항목 전체를 확인한다. 지금까지: 브레이크 지원(READ, 조작 → 콜백 확인), 방향지시등 지원(READ, 조작 → 콜백 확인). 나머지는 미확인.

## T0-0 에이전트가 Virtual Console을 직접 조작하는 방법 (2026-11-15, 성공)
- ① 브라우저 자동화는 시도하지 않았다(② 가 먼저 됨). ③ `adb shell cmd car_service inject-vhal-event`는 **실패**: `SecurityException: requires non-user build`.
- ② **HTTP API 직접 호출 성공.** `adb forward tcp:8088 tcp:8088` 뒤 `http://127.0.0.1:8088`:
  - 읽기: `GET /vehicle/state`, `GET /vehicle/catalog` (전체 카드 목록. 저장본 `docs/vc-catalog.json`).
  - 쓰기는 **PUT**: `PUT /vehicle/gear?gear=Park|Reverse|Neutral|Drive`, `PUT /vehicle/controls?speed=0&accelerator=0&brake=0..100&steering=-540..540`, 그 외 카드는 카탈로그의 `write_endpoint`와 `control.field`.
  - **쓰기는 브라우저 세션이 살아 있어야 열린다**: 그렇지 않으면 `{"error":"emulator_inactive"}`. 페이지가 1초마다 보내는 하트비트 `POST /browser/session?client=<id>`를 스크립트가 대신 보내면 된다.
  - `scripts/vc.sh gear P|R|N|D`, `brake N`, `steering N`, `state`로 감쌌다. check.sh가 시작 시 `vc.sh gear P`를 부른다.
- 발견: 이 세션 시작 때 에뮬레이터 기어가 `Drive`(source: web)였고, 그 상태에서는 플랫폼이 앱을 "주행 중에는 사용할 수 없습니다"로 가린다(앱 코드 문제 아님). Park으로 돌려 해결.

## V3 기어 (T0-7, 2026-11-15 실측)
**결론: 계획서 3.6의 단계 1에 해당한다.** 기어 속성(GEAR_SELECTION)을 Vehicle SDK `Gear.registerGearSelection`(권한 CAR_DRIVE, 이미 선언)으로 읽는다. 계획서 1.1의 "SDK로 기어를 못 읽는다"는 틀렸다(AAR에 `Gear` 클래스가 있다).

**읽기 경로별 결과**

| 경로 | 결과 |
|---|---|
| Vehicle SDK `Gear` (CAR_DRIVE) | **동작.** 초기값 `GEAR_PARK`, 전환마다 `onSelected` 콜백(Binder 스레드). 앱의 기본 판정 |
| `CarPropertyManager` GEAR_SELECTION 0x11400400, CURRENT_GEAR 0x11400401 | 권한 선언 전: `SecurityException: requires android.car.permission.CAR_POWERTRAIN`. **`android.car.permission.CAR_POWERTRAIN`을 선언하면 읽힘**(에뮬레이터가 grant). `pleos.car.permission.CAR_POWERTRAIN`만으로는 부족. 구독 콜백도 옴 |
| 벤더 속성 0x21400400 (Virtual Console 카드의 속성) | `requires android.car.permission.CAR_VENDOR_EXTENSION`, 에뮬레이터가 grant 안 함 → **읽을 수 없음**. 표준 0x11400400은 같은 기어 값을 준다 |
| `CarUxRestrictions` | 동작. D에서 `requiresDistractionOptimization=true`(activeRestrictions=80), P에서 false. 권한 불필요 |
| Activity 생명주기 | **D 전환에는 신호가 없다.** onPause·onStop·창 포커스 변화 모두 안 옴(플랫폼이 가림막을 앱 밖에서 덮는다). 소리가 계속 났던 원인 |

**D 전환부터 신호가 오기까지 (호스트 `PUT` 직전 시각 기준, D·P 각 6회, 호스트-에뮬레이터 시계 오차 ±30ms)**

| 신호 | 지연 |
|---|---|
| SDK Gear 콜백 | 72~84ms (Virtual Console이 값을 기록한 시각으로부터 2~8ms) |
| CarPropertyManager 콜백 | SDK와 같은 순간 (+84 / +75ms) |
| CarUxRestrictions 콜백 | 81~122ms (한 번 192ms) |
| 앱 엔진 STOP 로그 (`gear_regression.sh` 측정) | **87, 93, 101ms** — SDK 콜백 → `PlaybackGuard` → 엔진 Stop |
| 오디오 출력 스레드 standby | 마지막 소리 뒤 약 3초(오디오 플링거 standby 지연 3000ms) |

**처리 (계획서 3.6의 단계 1)**
- 잠금 판정은 SDK 기어를 따른다: P가 아니면 잠금 화면 + 엔진 Stop(녹음 중이면 끝내고 내용 유지) + 오디오 포커스 반납. 기어를 SDK가 못 줄 때만 CarPropertyManager → UX 제한 → "P로 간주" 순으로 내려간다(`PleosGearSource`, 화면 상단에 어느 방식인지 표시).
- **CarUxRestrictions는 병행 정지 신호가 아니라 기어를 모를 때의 대체다.** 기어를 P로 아는데 UX 제한이 걸리는 경우는 잠그지 않는다(실차에서 P단 오탐 방지). SDK 콜백을 놓치는 경우의 이중화는 UX가 SDK보다 약 10~40ms 늦게 오므로 별도로 하지 않았다.
- **릴리스 매니페스트에는 `android.car.permission.CAR_POWERTRAIN`을 넣지 않았다.** SDK 경로가 CAR_DRIVE만으로 되고, 승인 안 된 권한을 선언하면 심사에서 떨어질 수 있어서다(계획서 1.3). 디버그 매니페스트(`app/src/debug/`)에만 두어 CarPropertyManager 경로를 시험한다. 결정은 PLAN_CHANGES에 적었다. 사용자가 원하면 릴리스에도 넣을 수 있다.
- 입력 차단: 플랫폼이 화면을 가려도 SDK 콜백은 계속 온다. 비활성(잠금·백그라운드) 중에는 차량·화면 입력이 소리도 포커스도 만들지 않는다(`PJAM_LOCK input dropped`).
- P 복귀: 트랙 데이터 그대로, 재생은 정지 상태(자동 재생 안 함). `scripts/gear_regression.sh`가 확인한다.

**백그라운드 신호 (T2-8과 같은 경로)**

| 상황 | 앱에 오는 신호 |
|---|---|
| HOME 키 | 이 에뮬레이터에서는 앱이 오른쪽 창에 그대로 떠 있고 포커스가 67ms 깜빡일 뿐이다. `onPause`/`onStop` 없음 → 앱은 여전히 전경이라 소리를 멈출 근거가 없다 |
| 다른 앱으로 전환 (시계 앱으로 시험) | 창 포커스 손실 → **약 650ms 뒤 onPause → 5ms 뒤 onStop** |
| **방향지시등 켬** | 플랫폼이 `ai.umos.car.evs/.CarEvsBlindSpotCameraPreviewActivity`를 앱 위에 띄운다. `onPause`와 포커스 손실은 오지만 **`onStop`은 오지 않는다**(앱은 보임). 끄면 `onResume`으로 복귀 |

→ 백그라운드 판정은 **`onStop`**으로 한다. 처음에는 onPause와 포커스 손실(250ms 디바운스)을 걸었다가, 방향지시등을 켤 때마다 앱이 백그라운드로 처리되어 이후 방향지시등 입력(앱의 핵심 연주 입력)이 버려지는 것을 `check.sh`의 `vc-turn-right-flash`가 잡아서 `onStop`으로 바꿨다. 대가는 다른 앱 전환 시 정지가 포커스 손실보다 약 0.65초 늦는 것이다. 회귀 확인: `turn-overlay-*`.

**고친 버그: `SoundPoolSampler.stopAll()`이 아무것도 멈추지 못했다.** `autoPause()` 직후 `autoResume()`을 불러 바로 재생이 이어졌다. 시작한 스트림 ID를 기억해 하나씩 `stop()` 하도록 바꿨다.

**회귀 확인**: `scripts/gear_regression.sh` (check.sh 4c, 21줄). 재생 중 gear D → 엔진 STOP(1초 안) → STOP 뒤 루프 wrap 없음 → 오디오 출력 standby가 루프 한 바퀴(4.8초)보다 길게 유지 → D에서 브레이크 입력은 버려지고 포커스도 안 잡음 → gear P → 트랙 칸 수가 그대로 → 잠금 화면 없음 → 재생 자동 시작 안 함. 이어서 다른 앱으로 전환 → 정지 → 복귀 시 데이터 그대로·자동 재생 없음, 방향지시등 사각지대 창에서 정지하지 않음. `PlaybackGuard` 단위 테스트 8개. 정지 호출을 빼고 돌리면 `gear-d-stops-engine`·`gear-d-silent-after-input`·`gear-p-not-autoplay`·`gear-p-silent`가 FAIL 하는 것을 확인했다(음성 대조).

## V4 핸들
- 미착수(M0). 카탈로그 단서: Steering Angle 카드의 범위는 **−470..500**, 1° 단위(공식 문서의 −540..540과 다름). 차량 속성 `PERF_STEERING_ANGLE` 0x11600209. `GET /vehicle/state`의 `controls.limits.steering_deg`는 [-540,540].

## V5 방향지시등
- 미착수(M0). 카탈로그 단서: Turn Signal State 카드(`PUT /vehicle/turn-signal`, 필드 `mode`), 차량 속성 `TURN_SIGNAL_STATE` 0x11400408(표준). Hazard는 별도 카드(`HAZARD_LIGHTS_SWITCH` 0x11400E13).
- 브레이크: Brake Pedal 카드 0..100%, 차량 속성 `BRAKE_PEDAL_STATUS` 0x21200409(벤더).

## V6 지연
미착수 (M0)

## V7 화면
- 해상도 `wm size`: 2560x1440, 밀도 160dpi → 전체 1600×900dp
- 앱 창: uiautomator 기준 bounds `[853,90][2535,1300]` (= 1682×1210 px = 1682×1210dp). 화면 좌측(주행 정보)과 하단 공조 바를 시스템이 차지한다. 멀티 윈도우 크기는 M0에서 측정

## V8 오디오 포커스
미착수 (M0). T1-5는 계획서 3.5 기본값으로 구현.

## 오디오 재생 측정
미착수 (T0-13)

## 문서와 다른 점
| 날짜 | 내용 | 근거 | 조치 |
|---|---|---|---|
| 2026-11-15 | 스티어링 범위: 카드 −470..500 (문서 −540..540) | `docs/vc-catalog.json` | V4에서 실측 후 구간 경계 확정 |
| 2026-11-15 | 기어 속성이 벤더 속성 0x21400400 | `docs/vc-catalog.json` | 벤더 속성은 권한 때문에 못 읽지만 표준 0x11400400이 같은 값을 준다 (V3) |
| 2026-11-15 | 계획서 1.1 "SDK로 기어를 못 읽는다" | SDK 2.3.2에 `Gear` API가 있고 에뮬레이터에서 동작 (V1·V3) | 3.6 단계 1을 SDK로 구현 |
| 2026-11-15 | 툴체인이 계획서 3.1(AGP 8.8.2, Kotlin 2.0.0)과 다름. 템플릿의 AGP 9.5.1/Gradle 9.6 유지, Kotlin 2.4.0 | AGP 8.8.2는 캐시에 없고 현재 Android Studio가 AGP 9.x 템플릿을 만든다. Vehicle SDK 2.3.2가 이 조합에서 내려받기·빌드 성공 | 유지. 문제가 생기면 PLAN_CHANGES에 기록 |
| 2026-11-15 | compileSdk 36 (계획서: 34, 필요 시 35) | 최신 AndroidX 라이브러리가 compileSdk 35+ 요구 | targetSdk는 34 유지 |
| 2026-11-15 | 명세 문서 위치 `app/docs/` → `docs/` | CLAUDE.md는 루트 `docs/`를 기준 경로로 둠 | 이동 완료 |

## 알려진 제약
| 날짜 | 내용 | 영향 | 대체 방법 |
|---|---|---|---|
| 2026-11-15 | 에뮬레이터 기어가 D이면 플랫폼이 앱을 가린다 | 앱 화면 대신 "주행 중에는 사용할 수 없습니다" | 앱을 못 열고 uiautomator 스모크가 실패 | `scripts/vc.sh gear P` (check.sh가 자동 실행) |
| 2026-11-15 | `inject-vhal-event`는 user 빌드라 불가 | SecurityException | T0-0 ③ 사용 불가 | HTTP API(위 T0-0 ②) |
| 2026-11-15 | `uiautomator dump`는 `exec-out ... /dev/tty`로 받는다 | /sdcard 경유 시 AAOS 사용자 10에서 경로 문제 가능 | check.sh가 /dev/tty 사용 |
| 2026-11-15 | 방향지시등을 켜면 플랫폼이 사각지대 카메라 창을 앱 위에 띄운다 | 앱이 onPause·포커스 손실을 겪는다(onStop은 없음) | 백그라운드 판정을 onStop으로 한다 |
| 2026-11-15 | HOME 키로는 앱이 가려지지 않는다(오른쪽 창 유지) | HOME으로 소리를 멈추는 동작은 이 에뮬레이터에서 시험할 수 없다 | 다른 앱 전환으로 시험 |
