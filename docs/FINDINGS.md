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

## V3 기어
- 미착수(M0). M-P에서는 3.6의 4단계("P로 간주 + 기어 확인 불가")로 둔다.
- **카탈로그 단서:** Virtual Console의 Gear Selection 카드의 차량 속성은 표준 `GEAR_SELECTION`(0x11400400)이 아니라 **벤더 속성 `VENDOR_GEAR_SELECTION` 0x21400400**이다. 표준 `CarPropertyManager.GEAR_SELECTION` 구독이 안 될 수 있다. M0에서 표준(0x11400400) → 벤더(0x21400400) 순으로 시도한다.
- 플랫폼은 기어가 P가 아니면 주행 최적화 미선언 앱을 가린다(실측). 즉 "D에서 소리 정지"는 플랫폼이 이미 앱을 가리는 것과 겹친다.

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
| 2026-11-15 | 기어 속성이 벤더 속성 0x21400400 | `docs/vc-catalog.json` | V3에서 표준→벤더 순 시도 |
| 2026-11-15 | 툴체인이 계획서 3.1(AGP 8.8.2, Kotlin 2.0.0)과 다름. 템플릿의 AGP 9.5.1/Gradle 9.6 유지, Kotlin 2.4.0 | AGP 8.8.2는 캐시에 없고 현재 Android Studio가 AGP 9.x 템플릿을 만든다. Vehicle SDK 2.3.2가 이 조합에서 내려받기·빌드 성공 | 유지. 문제가 생기면 PLAN_CHANGES에 기록 |
| 2026-11-15 | compileSdk 36 (계획서: 34, 필요 시 35) | 최신 AndroidX 라이브러리가 compileSdk 35+ 요구 | targetSdk는 34 유지 |
| 2026-11-15 | 명세 문서 위치 `app/docs/` → `docs/` | CLAUDE.md는 루트 `docs/`를 기준 경로로 둠 | 이동 완료 |

## 알려진 제약
| 날짜 | 내용 | 영향 | 대체 방법 |
|---|---|---|---|
| 2026-11-15 | 에뮬레이터 기어가 D이면 플랫폼이 앱을 가린다 | 앱 화면 대신 "주행 중에는 사용할 수 없습니다" | 앱을 못 열고 uiautomator 스모크가 실패 | `scripts/vc.sh gear P` (check.sh가 자동 실행) |
| 2026-11-15 | `inject-vhal-event`는 user 빌드라 불가 | SecurityException | T0-0 ③ 사용 불가 | HTTP API(위 T0-0 ②) |
| 2026-11-15 | `uiautomator dump`는 `exec-out ... /dev/tty`로 받는다 | /sdcard 경유 시 AAOS 사용자 10에서 경로 문제 가능 | check.sh가 /dev/tty 사용 |
