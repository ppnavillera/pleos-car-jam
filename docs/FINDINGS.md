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
(P7에서 브레이크·방향지시등부터 채운다. 미착수)

| 도메인.메서드 | 파라미터 | 콜백·리스너 타입 | 값 타입·단위·enum | area 필요 | @RequiresPermission |
|---|---|---|---|---|---|

## V2 에뮬레이터 지원
미착수 (M0)

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
