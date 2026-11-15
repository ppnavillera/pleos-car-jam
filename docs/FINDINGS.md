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
Vehicle SDK 2.3.2 AAR의 classes.jar를 javap로 확인(sources jar 없음) + 컴파일·실행으로 검증. 패키지 접두 `ai.pleos.playground.vehicle`. 모든 메서드는 비-suspend, 결과는 람다(`onComplete`/`onFailed`)로 온다. **Brake, TurnSignal(P7)과 M0에서 확인한 Gear·SteeringWheel·HVAC(A/C·AUTO·내기·성에·풍량·온도)·Window·Wiper·DrivingMode·EvBattery(충전)·Light(앰비언트 밝기)를 적었다.** 영역 객체(`SeatArea`, `WindowArea`)는 `SeatArea(vararg SeatValue)`처럼 만들고, 빈 영역 `SeatArea()`는 `areaId is Invalid`로 실패한다.
- **영역 주의 (실측):** A/C·AUTO·내기순환·풍량·앰비언트는 `ROW_1_LEFT` 같은 낱개 영역으로 조회하면 `IllegalArgumentException: area ID: 0x1 not supported`로 실패한다. **capability가 돌려준 영역(예: A/C `ROW_1_LEFT+ROW_1_RIGHT+ROW_2_LEFT+ROW_2_CENTER+ROW_2_RIGHT`)을 그대로 get/register에 넘기면 된다.** 온도와 창문은 낱개 영역으로 됐다.
- 도메인 접근자는 `getHVAC()`, `getWindow()`, `getWiper()`, `getDrivingMode()`, `getEvBattery()`, `getLight()`, `getSteeringWheel()`, `getGear()`.

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
| SteeringWheel.checkSteeringWheelAngleCapabilities | onComplete:(CapabilityConfig, AreaBounds<Float>)->Unit, onFailed | 람다 | `AreaBounds<T>(minValue, maxValue)`. 실측 −470.0..500.0. 영역 없음 | 없음 | **CAR_DRIVE** (READ_CAR_STEERING 아님) |
| SteeringWheel.getCurrentSteeringWheelAngle / register… | onComplete:(Float?)->Unit / `SteeringWheelAngleListener.onSteeringWheelAngleUpdated(Float?)` | 람다/리스너 | Float(도). **부호가 Virtual Console과 반대**(V4) | 없음 | CAR_DRIVE |
| HVAC.checkHvacAc/Auto/Recirculation/DefrosterCapabilities | onComplete:(CapabilityConfig, List<Area>)->Unit, onFailed (Function1 오버로드도 있음) | 람다 | A/C·AUTO·내기는 `List<SeatArea>`, 성에 제거는 `List<WindowArea>`. **돌려받은 영역을 그대로 get/register에 넘겨야 한다**(아래 주의) | 있음 | `pleos.car.permission.CAR_CLIMATE` |
| HVAC.getHvacAcState / getHvacAutoState / getHvacRecirculationState / getHvacDefrosterState | (area, onComplete:(Boolean?)->Unit, onFailed) | 람다 | Boolean. 내기순환은 `getHvacRecirculationState`(AutoRecirculation은 별개 속성) | SeatArea / 성에 제거는 WindowArea | CAR_CLIMATE |
| HVAC.registerHvacAcState / AutoState / RecirculationState / DefrosterState | (area, listener) | `HvacAcStateListener.onHvacAcStateUpdated(Boolean?)`, `HvacAutoStateListener.onHvacAutoStateUpdated`, `HvacRecirculationStateListener.onHvacAutoRecirculationStateUpdated`(이름이 AutoRecirculation), `HvacDefrosterStateListener.onHvacDefrosterStateUpdated` | Boolean? | 위와 같음 | CAR_CLIMATE |
| HVAC.checkHvacFanSpeedCapabilities / getHvacFanSpeed / registerHvacFanSpeed | onComplete:(CapabilityConfig, Map<SeatArea, AreaBounds<Int>>)->Unit / (area, onComplete:(Int?)->Unit) / `HvacFanSpeedListener.onHvacFanSpeedUpdated(Int?)` | 람다/리스너 | Int. 실측 범위 1..8 (값 0도 콜백으로 옴) | SeatArea | CAR_CLIMATE |
| HVAC.checkHvacTemperatureCapabilities / getHvacTemperature / registerHvacTemperature | (CapabilityConfig, Map<SeatArea, AreaBounds<Float>>) / (area, TemperatureUnit, onComplete:(Float?)) / `HvacTemperatureListener.onHvacTemperatureUpdated(Float?)` (2값짜리 `HvacTemperatureValueListener` 오버로드도 있음) | 람다/리스너 | Float(°C, `TemperatureUnit.CELSIUS`). 실측 범위 15.0..30.0, 0.5 단위 | SeatArea(운전석 ROW_1_LEFT로 성공) | CAR_CLIMATE |
| Window.checkWindowPositionCapabilities / getCurrentWindowPosition / registerWindowPosition | (CapabilityConfig, Map<WindowArea, AreaBounds<Int>>) / (area, onComplete:(Int?)) / `WindowPositionListener.onWindowPositionUpdated(Int?)` | 람다/리스너 | Int. 실측 **0..10** (Virtual Console은 0..100%) | WindowArea(`WindowValue.ROW_1_LEFT` 등) | `pleos.car.permission.CAR_WINDOWS` |
| Wiper.checkWiperStateCapabilities / getCurrentWiperState / registerWiperState | (CapabilityConfig, Map<WindowArea, List<WiperState>>) / (area, onComplete:(WiperState?)) / `WiperStateListener.onWiperStateUpdated(WiperState?)` | 람다/리스너 | `WiperState`: OTHER, **OFF, ON**, SERVICE. 속도 단계 없음. `WiperSwitch`(MIST·INTERMITTENT_1..5·CONTINUOUS_1..5·AUTO·SERVICE)는 `getCurrentWiperSwitch`로 조회만 되고 **리스너가 없다** | WindowArea(`FRONT_WINDSHIELD`) | **CAR_WINDOWS** (CAR_WINDOWS가 맞고 계획서의 READ_WINDSHIELD_WIPERS 아님) |
| DrivingMode.checkDrivingModeStateCapabilities / getCurrentDrivingModeState / registerDrivingModeState | (CapabilityConfig) / (onComplete:(DrivingModeType?)) / `DrivingModeStateListener.onDrivingModeStateUpdated(DrivingModeType?)` | 람다/리스너 | `DrivingModeType`: NORMAL, ECO, SPORT | 없음 | CAR_DRIVE |
| EvBattery.checkEvChargeSwitchCapabilities / getEvChargeSwitchState | (CapabilityConfig) / (onComplete:(Boolean?)) | 람다 | Boolean. **리스너 없음**(조회만) | 없음 | `pleos.car.permission.CAR_ENERGY` |
| EvBattery.checkEvChargePortOpenCapabilities / registerEvChargePortOpen | (CapabilityConfig) / `EvChargePortOpenListener.onEvChargePortOpenUpdated(Boolean?)` | 람다/리스너 | Boolean | 없음 | CAR_ENERGY |
| Light.checkAmbientLightBrightnessCapabilities / getAmbientLightBrightness / registerAmbientLightBrightnessListener | (CapabilityConfig, Map<SeatArea, AreaBounds<Int>>) / (area, onComplete:(Int?)) / `AmbientLightBrightnessListener.onBrightnessUpdated(Int?)` | 람다/리스너 | Int. 실측 0..100 | SeatArea | CAR_LIGHTS |
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

## V2 에뮬레이터 지원 (T0-3, 2026-11-15 실측)
`scripts/v2_probe.sh`가 Virtual Console 값을 `vc.sh put`으로 바꿔 가며 디버그 빌드의 `SignalProbe`(capability → 조회 → 구독)가 콜백을 받는지 확인한다. HVAC Power On·Dual Off로 둔 상태에서 했다. (HVAC Power가 꺼져 있으면 값을 바꿔도 앱에 콜백이 오지 않았다.) 콜백은 전부 Binder 스레드(`binder:<pid>_N`)로 온다.

| # | 항목 | 결과 | capability | SDK 값 | Virtual Console 조작 → 콜백 |
|---|---|---|---|---|---|
| 1 | 브레이크 | **읽힘** | READ | Boolean | brake 100/0 → true/false (P7) |
| 2 | 방향지시등 | **읽힘** | READ | OFF/LEFT/RIGHT | Blink·Flash 모두 같은 방향으로 (P7) |
| 3 | 핸들 각도 | **읽힘** | READ, −470.0..500.0 | Float(도) | steering ±100 → 콜백. **부호 반대** (V4) |
| 4 | A/C | **읽힘** | READ_WRITE | Boolean | ac false/true → false/true |
| 5 | AUTO | **읽힘** | READ_WRITE | Boolean | auto true/false → true/false |
| 6 | 내기순환 | **읽힘** | READ_WRITE | Boolean | recirc true/false → true/false |
| 7 | 성에 제거(전면) | **읽힘** | READ_WRITE (WindowArea 2개) | Boolean | front_defroster true/false → true/false |
| 8 | 풍량 | **읽힘** | READ_WRITE, **1..8** | Int | fan_speed 6/3/0/8 → 6/3/0/8 (**0도 옴**, cap 범위 밖) |
| 9 | 설정 온도(운전석) | **읽힘** | READ_WRITE, **15.0..30.0** | Float | driver_temp 24.5/22/16 → 24.5/22.0/16.0 (0.5 단위 확인) |
| 10 | 창문 위치(앞 좌·우) | **읽힘** | READ_WRITE, **0..10** | Int | window_fl 50/100 → 5/10, window_fr 80 → 8 (**값이 /10**) |
| 11 | 드라이브 모드 | **미지원** | `ERROR_NOT_SUPPORTED_PROPERTY` (속성 0x21400410 없음) | — | Virtual Console에 조작 없음. get도 실패 |
| 12 | 와이퍼 상태 | **읽힘, 단 ON/OFF만** | state READ, switch READ_WRITE | `WiperState` OFF/ON | front_wiper Low → ON, Auto → ON, Off → OFF. **High→(이미 ON이면) 콜백 없음.** Switch(단계)는 get만 되고 리스너 없음 |
| 13 | 충전 상태(EvBattery) | **읽힘(포트), 조회만(스위치)** | port·switch 모두 READ_WRITE | Boolean | charge_port_open true/false → true/false. 충전 스위치는 `getEvChargeSwitchState`만 있고 리스너 없음 |
| 14 | 앰비언트 라이트 | **읽힘(밝기)** | READ_WRITE, 0..100 | Int | ambient_brightness 40/70 → 40/70 |

결론
- 14개 중 **13개가 SDK로 읽힌다**(와이퍼는 ON/OFF만, 충전은 포트만 구독 가능). **드라이브 모드 1개는 이 에뮬레이터에서 지원되지 않는다** → 계획서 3.5 T3-5대로 화면 버튼으로 사운드팩을 전환한다.
- 범위는 항상 capability에서 받아 정규화한다. 풍량 1..8(값 0이 올 수 있음 → 0.0으로 고정), 창문 0..10(Virtual Console 0..100과 다름), 온도 15..30(Virtual Console 16..30과 다름).
- 와이퍼는 단계를 구분하지 못하므로 `Cycle` 입력(이펙트 단계 순환)이 아니라 OFF→ON 에지로만 쓸 수 있다. 기획서 4.5의 "와이퍼 단계→이펙트 단계"는 **불가**, "와이퍼 켜짐→이펙트 한 번"으로 줄인다.
- 풍량 템포 연동(T3-1)은 값이 1..8(+0)이라 9단계 가정과 달라진다: 0~8을 70~140 BPM에 대응.
- 필요한 권한(읽기만): CAR_DRIVE(브레이크·핸들·드라이브모드·기어), CAR_LIGHTS(방향지시등·앰비언트), CAR_CLIMATE(공조 6종), CAR_WINDOWS(창문·와이퍼), CAR_ENERGY(충전, P2). 현재 릴리스 매니페스트는 CAR_DRIVE·CAR_LIGHTS만 있고 나머지는 디버그 매니페스트에 있다. 공조·창문 기능을 구현할 때 릴리스에 추가한다(T2-5, T3-7).

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

**D 전환부터 신호가 오기까지 (호스트 `PUT` 직전 시각 기준, D·P 각 6회). 처음 잰 값에서 에뮬레이터 시계가 호스트보다 50ms 앞선 오차를 뺐다(V6 참고)**

| 신호 | 지연 |
|---|---|
| SDK Gear 콜백 | 22~34ms (원측정 72~84ms − 50ms. Virtual Console이 값을 기록한 시각으로부터 2~8ms) |
| CarPropertyManager 콜백 | SDK와 같은 순간 |
| CarUxRestrictions 콜백 | 31~72ms (한 번 142ms) |
| 앱 엔진 STOP 로그 (`gear_regression.sh` 측정) | **29~51ms** (원측정 87·93·101ms에서 보정 → 37·43·51, 보정 내장 회귀 스크립트 29) — SDK 콜백 → `PlaybackGuard` → 엔진 Stop |
| 오디오 출력 스레드 standby | 마지막 소리 뒤 약 3초(오디오 플링거 standby 지연 3000ms) |

**처리 (계획서 3.6의 단계 1)**
- 잠금 판정은 SDK 기어를 따른다: P가 아니면 잠금 화면 + 엔진 Stop(녹음 중이면 끝내고 내용 유지) + 오디오 포커스 반납. 기어를 SDK가 못 줄 때만 CarPropertyManager → UX 제한 → "P로 간주" 순으로 내려간다(`PleosGearSource`, 화면 상단에 어느 방식인지 표시).
- **CarUxRestrictions는 두 번째 독립 정지 신호다.** `requiresDistractionOptimization=true`는 "플랫폼이 이 앱(주행 최적화 미선언)을 가린다"는 뜻이라, 기어가 P로 읽히더라도 그 뒤에서 소리가 계속 나는 것이 바로 고친 버그다. 그래서 P로 읽히는데 UX 제한이 걸려도 멈춘다(오탐이 아님). 에뮬레이터 P에서 UX는 `false`로 측정됐다(P 복귀 후 6회 모두). 판정은 `잠금 = 강제 || SDK 기어가 P 아님 || UX 제한`이고, SDK 콜백이 UX보다 약 10~40ms 빨라 보통 SDK가 먼저 멈춘다. UX가 있으면 SDK 콜백을 놓쳐도 멈춘다. 기어를 아예 못 읽을 때(3.6 단계 3)에는 UX만으로 판정한다.
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

## V4 핸들 (T0-8, 2026-11-15 실측)
`scripts/vc_measure.py steering`: 0 → −470 → +500 → 0 으로 10° 간격·100ms마다 PUT(197번).
- **값**: Float, 단위 도. SDK 범위 **−500.0..470.0** (capability는 −470.0..500.0이라고 답함, 실제 값은 반대 부호로 온다).
- **부호: Virtual Console과 반대.** VC −470 → SDK **+470**, VC +500 → SDK **−500**. AAOS 표준 속성(PERF_STEERING_ANGLE)의 정의는 "왼쪽이 음수"이고 SDK 값이 그 정의를 따르는 것으로 보이므로 **SDK 음수 = 왼쪽**으로 구현했다(코드 상수 한 곳 `STEERING_LEFT_IS_NEGATIVE`). **[수동] Virtual Console의 핸들 그림이 + 값에서 어느 쪽으로 도는지 눈으로 확인 필요.** 반대면 상수만 뒤집는다.
- **단위 간격**: 연속 콜백 차이는 10°(또는 PUT 두 개가 합쳐진 20°). 정수 도 단위로 온다.
- **콜백 간격**: 197번 PUT에 174번 콜백(일부 합쳐짐), 평균 123ms, 중앙값 110ms, p95 215ms, 최대 325ms. PUT 간격이 100ms였으므로 간격은 Virtual Console 쪽이 만든다. 연속으로 움직일 때 약 8~10회/초라서 3구간 판정에는 충분하다.
- **3구간 경계**: 계획서 기본값(±10° 경계, ±3° 히스테리시스) 유지. 값이 정수 도 단위이고 간격이 거칠어(10° 점프 가능) 히스테리시스 ±3°는 충분히 좁다. 이 경계의 체감은 손으로 해 봐야 안다 → [수동].

## V5 방향지시등 (T0-9, 2026-11-15 실측)
- enum: `TurnSignalValue` **OFF(0), RIGHT(1), LEFT(2)** 세 가지뿐 (V1). Virtual Console의 BlinkLeft·FlashLeft는 모두 `LEFT`, BlinkRight·FlashRight는 `RIGHT`로 도착한다.
- **점멸 콜백 없음:** `BlinkLeft`를 11초 켜 둔 동안 TURN_SIGNAL 콜백은 **1번**(켤 때)뿐이었다. 깜빡임마다 오지 않으므로 "켜는 순간"이 곧 Trigger다.
- **비상등:** Virtual Console Hazard Lights를 On(`PUT /vehicle/lights?feature=hazard&value=true`, 상태 `hazard_on=true`)으로 해도 TURN_SIGNAL 콜백은 오지 않았고 값도 OFF로 남는다. 별도 속성이라 앱 입력에는 영향이 없다.
- 켜면 플랫폼이 사각지대 카메라 창을 앱 위에 띄운다(V3 백그라운드 신호 참고).
- 카탈로그 단서: 속성 `TURN_SIGNAL_STATE` 0x11400408, 브레이크 `BRAKE_PEDAL_STATUS` 0x21200409(벤더).

## V6 지연 (T0-10, 2026-11-15 실측)
`scripts/vc_measure.py brake`: 브레이크 100/0을 20번 교대로 PUT. 호스트 시각 → 앱의 `PJAM_SIG` 로그 시각(`logcat -v epoch`).
- **시계 보정**: 에뮬레이터 시계가 호스트보다 **약 50ms 앞선다**. 열어 둔 `adb shell`로 왕복 7~8ms짜리 표본 10개의 중앙값으로 구해 뺐다. 보정하지 않고 `adb shell date`를 매번 띄워 재면 호출 지연이 섞여 값이 틀린다(처음엔 62ms로 잘못 구했다).
- **PUT → SDK 콜백**: n=20, 평균 **6.6ms**, 중앙값 5.8ms, p95 11.6ms, 최대 11.6ms, 최소 2.1ms. 20번 모두 도착.
- **콜백 간 간격**: 605~615ms (PUT 간격 0.6초 + 호출 오버헤드), 흔들림 10ms 이내.
- **기어는 더 느리다**: PUT → SDK 콜백 22~34ms, CarUxRestrictions 31~72ms, 앱 엔진 STOP 29~51ms (V3, 같은 방법으로 보정).
- **결정**: 시스템 쪽 지연이 약 7ms로 한 스텝(150ms)의 5% 미만이라 **차량 입력 지연 보정 기본값은 0**으로 둔다(`latencyNanos` 설정 항목은 T3-3에서 노출).
- **측정하지 못한 것 [수동]**: "브라우저에서 클릭 → 앱 로그"까지의 지연(60fps 화면 녹화 필요)과 소리가 실제로 스피커에서 나오기까지의 출력 지연. 에이전트가 화면 녹화를 할 수 없어 PUT 이후만 쟀다. 브라우저 클릭~HTTP는 보통 수십 ms 이하로 보이지만 확인하지 못했다.

## V7 화면
- 해상도 `wm size`: 2560x1440, 밀도 160dpi → 전체 1600×900dp
- 앱 창: uiautomator 기준 bounds `[853,90][2535,1300]` (= 1682×1210 px = 1682×1210dp). 화면 좌측(주행 정보)과 하단 공조 바를 시스템이 차지한다. **T0-11 중 측정한 것은 여기까지다. 멀티 윈도우로 창 크기를 바꾼 경우는 측정하지 못했다(미확인).** 일반 폰 에뮬레이터(1080×2400 세로)에서는 상단 바 버튼이 화면 밖으로 밀리는 것을 확인했다(T2-6 반응형 배치에서 해결)

## V8 오디오 포커스
**미확인 (T0-12).** 문서 요약과 다른 앱과의 포커스 이동 시험은 하지 않았다(에뮬레이터에 시험용 미디어 앱이 없어 포커스를 뺏는 쪽을 만들지 못함). T1-5는 계획서 3.5 기본값(USAGE_MEDIA, AUDIOFOCUS_GAIN, 손실 시 Stop)으로 구현했고, 소리가 날 때 `dumpsys audio` 기준 포커스 GAIN 보유와 AudioOut 스레드 동작만 확인했다. 앱 전환·기어 잠금에서는 포커스를 반납한다(`PJAM_FOCUS`).

## 오디오 재생 측정 (T0-13, 2026-11-15 실측)
엔진이 스텝 예정 시각과 `play()`를 부르기 직전 시각의 차(lag)를 `StepLagStats`(링 버퍼)로 모은다. 스텝마다 로그를 찍지 않고 200스텝마다 한 줄 요약(`PJAM_LAG`)을 낸다.
- 조건: 100 BPM, DRUM 녹음 상태로 메트로놈(박마다 소리) 75초, 400스텝. 에뮬레이터(arm64), 엔진 스레드 우선순위 URGENT_AUDIO.
- 결과: **p50 0.16ms, p95 0.74ms, p99 1.19ms, 최대 1.36ms, 누락 스텝 0.** 게이트 G2(p95 < 15ms) 통과 → **SoundPoolSampler 유지, MixerSampler 교체 불필요.**
- **3분 연속(T2-9, 190초, 1200스텝, `scripts/lag_run.sh`)**: p50 0.25ms, **p95 0.79ms**, p99 1.25ms, **최대 8.26ms**(처음 200스텝 안의 한 번, 이후 증가 없음), **누락 스텝 0** → T2-9의 "p95 < 15ms, 누락 0" 충족. 최대값 8.26ms는 게이트(15ms) 이내지만 귀로는 안 들릴 수준인지 확인하지 못했다.
- 한계: 이 값은 "엔진이 늦게 깨어난 정도"다. SoundPool 내부 버퍼링과 오디오 출력 지연은 포함하지 않는다(그건 귀로 확인해야 한다, [수동]). 3분 연속 측정은 T2-9에서 같은 도구로 한다.

## 방향지시등 카메라 창 (2026-11-15 실측, M2 2번)
방향지시등을 켜면 플랫폼(`ai.umos.car.evs/.CarEvsBlindSpotCameraPreviewActivity`)이 사각지대 카메라 창을 앱 위에 띄운다. `vc.sh turn BlinkLeft`/`BlinkRight`로 켜고 `dumpsys window`와 스크린샷으로 쟀다.
- **위치·크기**: 앱 창 `[853,90][2535,1300]`의 **왼쪽 위 모서리**에 붙는다. 스크린샷 기준 약 **x 854~1606, y 90~513 (752×423px, 16:9)**, 즉 앱 폭의 약 45%, 앱 높이의 약 35%. `dumpsys`의 `frame=[1280,695][1280,695]`은 0×0이라 쓸 수 없고, 화면에서 잰 값이 기준이다(오차 ±2px). 닫기(X) 버튼이 창 왼쪽 위에 있다.
- **왼쪽과 오른쪽 차이 없음**: BlinkLeft·BlinkRight 모두 같은 위치·크기다(오른쪽도 창이 왼쪽 위에 뜬다).
- **뜨는 시간**: 켠 뒤 약 1.7초 안에 창이 존재하고, **방향지시등을 켜 둔 동안 계속 떠 있다**(22초 동안 유지 확인, 저절로 닫히지 않음). Off로 하면 1.7초 안에 사라진다. 사용자가 적었던 "5초쯤" 같은 숫자는 확인되지 않았다.
- **가리는 것**: 앱의 위쪽 왼쪽 45%×35% 구역. 지금 화면에서는 제목·상태 글자, DRUM 카드의 이름·스위치와 격자 앞쪽 절반, BASS 카드 윗부분이 가려진다. 입력 표시줄(y≈1010)·패드·오른쪽 55%는 가려지지 않는다.
- **조치(T2-6)**: 넓은 화면은 두 열로 나눈다. **왼쪽 열 위쪽 760×430dp는 "카메라 안전 구역"으로 두고 제목·상태·기어 글자만 둔다.** 트랙 카드 4개와 재생·녹음 버튼은 오른쪽 열에, 입력 표시줄과 패드는 왼쪽 열 안전 구역 아래에 둔다. 좁은 창에서 카메라 창 크기가 어떻게 달라지는지는 측정하지 못했다(미확인). 겹침은 `check.sh`가 `turn BlinkLeft` 상태에서 입력 표시줄·트랙 카드의 bounds를 읽어 교집합이 없는지 확인한다.

## 문서와 다른 점
| 날짜 | 내용 | 근거 | 조치 |
|---|---|---|---|
| 2026-11-15 | 스티어링 범위: 카드 −470..500 (문서 −540..540) | `docs/vc-catalog.json` | V4에서 실측 후 구간 경계 확정 |
| 2026-11-15 | 기어 속성이 벤더 속성 0x21400400 | `docs/vc-catalog.json` | 벤더 속성은 권한 때문에 못 읽지만 표준 0x11400400이 같은 값을 준다 (V3) |
| 2026-11-15 | 계획서 1.1 "SDK로 기어를 못 읽는다" | SDK 2.3.2에 `Gear` API가 있고 에뮬레이터에서 동작 (V1·V3) | 3.6 단계 1을 SDK로 구현 |
| 2026-11-15 | 툴체인이 계획서 3.1(AGP 8.8.2, Kotlin 2.0.0)과 다름. 템플릿의 AGP 9.5.1/Gradle 9.6 유지, Kotlin 2.4.0 | AGP 8.8.2는 캐시에 없고 현재 Android Studio가 AGP 9.x 템플릿을 만든다. Vehicle SDK 2.3.2가 이 조합에서 내려받기·빌드 성공 | 유지. 문제가 생기면 PLAN_CHANGES에 기록 |
| 2026-11-15 | compileSdk 36 (계획서: 34, 필요 시 35) | 최신 AndroidX 라이브러리가 compileSdk 35+ 요구 | targetSdk는 34 유지 |
| 2026-11-15 | 명세 문서 위치 `app/docs/` → `docs/` | CLAUDE.md는 루트 `docs/`를 기준 경로로 둠 | 이동 완료 |
| 2026-11-15 | 계획서 1.1: 핸들 권한 `READ_CAR_STEERING`, 와이퍼 `READ_WINDSHIELD_WIPERS` 후보 | `@RequiresPermission`: 핸들 각도는 **CAR_DRIVE**, 와이퍼는 **CAR_WINDOWS** | 두 권한은 선언하지 않는다 |
| 2026-11-15 | 계획서 1.3: 드라이브 모드를 조회로 확인 | `ERROR_NOT_SUPPORTED_PROPERTY` | 미지원, 화면 버튼으로 대체 (V2) |
| 2026-11-15 | 기획서 4.5: 와이퍼 단계 → 이펙트 단계 | SDK `WiperState`는 OFF/ON뿐이고 `WiperSwitch`(단계)에는 리스너가 없다 | "켜짐 → 이펙트 한 번"으로 축소 (PLAN_CHANGES) |
| 2026-11-15 | 계획서 1.3 / 3.4: 풍량 0~8단계 9개, 창문·온도는 Virtual Console 범위 | SDK capability 풍량 1..8(0도 옴), 창문 0..10, 온도 15..30 | capability 범위로 정규화 |
| 2026-11-15 | Virtual Console 핸들 +값 = 계획서의 "오른쪽"? | SDK는 부호가 반대(VC −470 → SDK +470) | SDK 음수 = 왼쪽, 눈으로 확인 필요 (V4) |

## 알려진 제약
| 날짜 | 내용 | 영향 | 대체 방법 |
|---|---|---|---|
| 2026-11-15 | 에뮬레이터 기어가 D이면 플랫폼이 앱을 가린다 | 앱 화면 대신 "주행 중에는 사용할 수 없습니다" | 앱을 못 열고 uiautomator 스모크가 실패 | `scripts/vc.sh gear P` (check.sh가 자동 실행) |
| 2026-11-15 | `inject-vhal-event`는 user 빌드라 불가 | SecurityException | T0-0 ③ 사용 불가 | HTTP API(위 T0-0 ②) |
| 2026-11-15 | `uiautomator dump`는 `exec-out ... /dev/tty`로 받는다 | /sdcard 경유 시 AAOS 사용자 10에서 경로 문제 가능 | check.sh가 /dev/tty 사용 |
| 2026-11-15 | 방향지시등을 켜면 플랫폼이 사각지대 카메라 창을 앱 위에 띄운다 | 앱이 onPause·포커스 손실을 겪는다(onStop은 없음) | 백그라운드 판정을 onStop으로 한다 |
| 2026-11-15 | HOME 키로는 앱이 가려지지 않는다(오른쪽 창 유지) | HOME으로 소리를 멈추는 동작은 이 에뮬레이터에서 시험할 수 없다 | 다른 앱 전환으로 시험 |
