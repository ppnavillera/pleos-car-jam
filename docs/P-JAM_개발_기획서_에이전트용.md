# P-JAM 개발 기획서 (AI 개발 에이전트 전달용)

이 문서는 P-JAM 앱을 구현할 개발 에이전트에게 주는 요구사항 문서입니다. 이 문서를 읽고 상세 개발 계획서(작업 분해, 순서, 검증 방법)를 먼저 작성한 뒤 구현을 시작하세요.

- 문서 기준일: 2026-11-14
- 상위 문서: `P-JAM_접수양식_기획서_수정본` (공모전 제출 기획서)

---

## 0. 한눈에 보기

| 항목 | 내용 |
|---|---|
| 앱 이름 | P-JAM (피잼) |
| 한 줄 정의 | 주차(P단) 중인 차의 핸들·레버·페달·공조 버튼으로 소리를 한 겹씩 쌓아 곡을 만드는 차 안 전용 루프스테이션 |
| 플랫폼 | Pleos Connect (Android Automotive OS 기반 차량 인포테인먼트) |
| 실행·검증 환경 | Pleos Connect Emulator(Android 14, API 34) + Virtual Console. 실차 없음 |
| 목적 | 현대자동차그룹 차량용 앱 개발 공모전 2차 PT(2026-12-03~04)에서 에뮬레이터로 실제 동작 시연 |
| 최종 산출물 | GitHub 저장소(소스 + release APK + README) |
| 개발 완료 기한 | 2026-12-02 |

**성공 기준 한 문장:** 심사위원 앞에서 Virtual Console로 차량 조작을 바꾸면, 에뮬레이터의 앱이 그 조작을 받아 4트랙 루프를 쌓고 트랙을 넣었다 빼는 것이 3분 안에 끊김 없이 시연된다.

---

## 1. 에이전트 작업 규칙 (반드시 지킬 것)

1. **SDK API를 추측해서 쓰지 않는다.** 이 문서의 메서드 이름은 공식 문서 목차에서 확인한 것이지만, 파라미터·콜백 타입·enum 값은 확인하지 못했다. 구현 전에 반드시 IDE 자동완성(SDK 소스/시그니처)이나 공식 API 레퍼런스로 확인한다.
2. **공개 문서 범위 안에서만 개발한다.** 공모전 공지상 사용 가능한 SDK는 Vehicle, NaviHelper, TextToSpeech, SpeechToText, FusedLocation뿐이다. LLM SDK, ADAS SDK 등 미공개 SDK는 쓰지 않는다. 이 앱은 Vehicle SDK와 AAOS 표준 API만 쓴다.
3. **모든 차량 입력은 capability 확인 후 사용한다.** 지원되지 않는 항목은 조용히 비활성화하고 화면 버튼으로 대체한다. 지원 안 됨 때문에 앱이 죽으면 안 된다.
4. **차량 입력 없이도 전 기능이 화면 조작만으로 동작해야 한다.** 에뮬레이터에서 특정 신호가 안 읽혀도 시연이 가능해야 하기 때문이다.
5. **주행 최적화(distractionOptimized) 선언을 하지 않는다.** 이 앱은 주차 전용이다.
6. **차량 제어(set) API는 기본적으로 쓰지 않는다.** 예외는 5.6의 선택 기능 두 가지뿐이며, 둘 다 P2다.
7. **API 키·서명 키 등 비밀값을 저장소에 넣지 않는다.**
8. **사운드 샘플은 직접 합성하거나 CC0 라이선스만 쓴다.** 출처를 README에 적는다. 공모전 출품작은 순수 창작물이어야 하고 저작권 분쟁 시 수상이 취소된다.
9. 확인하지 못한 것, 문서와 다른 것, 에뮬레이터에서 안 되는 것은 숨기지 말고 `docs/FINDINGS.md`에 기록한다.

---

## 2. 확인된 사실과 확인이 필요한 것

### 2.1 확인된 사실 (공식 문서 기준)

**개발 환경**
- Android Studio, Android SDK API 34, Kotlin, minSdk 28 이상, Gradle 8.0 이상
- 프로젝트 템플릿: Car/Automotive 카테고리의 Empty Activity
- 에뮬레이터 시스템 이미지: SDK Manager의 SDK Update Sites에 Pleos Connect System Image를 추가해 설치 (Mac은 arm64용, Windows/Linux는 별도 xml). AVD는 Automotive, Cold boot, RAM 4GB, API 34 이미지
- Maven 저장소: `https://nexus-playground.pleos.ai/repository/maven-releases/` 를 `settings.gradle.kts`의 `dependencyResolutionManagement`에 추가
- 의존성: `ai.pleos.playground:Vehicle:<버전>` (시작 가이드 예시는 2.3.1, 공식 샘플 앱은 2.3.2 사용)

**Vehicle SDK 사용 패턴**
- `Vehicle(context)` 생성 → `initialize()` → `getXxx()`로 도메인 객체 획득 → 사용 → `release()`
- 결과는 동기 반환이 아니라 `onComplete` / `onFailed` 람다로 비동기 전달된다 (IPC)
- 콜백이 끝나기 전에 `release()`를 부르면 안 된다. `onDestroy()` 등에서 호출
- 도메인마다 capability 확인(`checkXXXCapabilities`), 조회(`get…`), 구독(`register…` / `unregister…`), 제어(`set…`)가 있다
- 영역 지정 예: `getCurrentDoorPosition(area = DoorArea(DoorValue.ROW_1_LEFT), onComplete, onFailed)`

**이 앱이 쓸 메서드 (문서 목차에서 이름 확인)**

| 도메인 | capability | 조회 | 구독 / 해제 |
|---|---|---|---|
| TurnSignal | `checkTurnSignalStateCapabilities` | `getCurrentTurnSignalState` | `registerTurnSignalState` / `unregisterTurnSignalState` |
| Brake | `checkBrakePedalStatusCapabilities` | `getCurrentBrakePedalStatus` | `registerBrakePedalStatus` / `unregisterBrakePedalStatus` |
| SteeringWheel | `checkSteeringWheelAngleCapabilities` | `getCurrentSteeringWheelAngle` | `registerSteeringWheelAngle` / `unregisterSteeringWheelAngle` |
| HVAC (A/C) | `checkHvacAcCapabilities` | `getHvacAcState` | `registerHvacAcState` / `unregisterHvacAcState` |
| HVAC (AUTO) | `checkHvacAutoCapabilities` | `getHvacAutoState` | `registerHvacAutoState` / `unregisterHvacAutoState` |
| HVAC (내기순환) | `checkHvacRecirculationCapabilities` | `getHvacRecirculationState` | `registerHvacRecirculationState` / `unregisterHvacRecirculationState` |
| HVAC (성에 제거) | `checkHvacDefrosterCapabilities` | `getHvacDefrosterState` | `registerHvacDefrosterState` / `unregisterHvacDefrosterState` |
| HVAC (풍량) | `checkHvacFanSpeedCapabilities` | `getHvacFanSpeed` | `registerHvacFanSpeed` / `unregisterHvacFanSpeed` |
| HVAC (설정 온도) | `checkHvacTemperatureCapabilities` | `getHvacTemperature` | `registerHvacTemperature` / `unregisterHvacTemperature` |
| Window | `checkWindowPositionCapabilities` | `getCurrentWindowPosition` | `registerWindowPosition` / `unregisterWindowPosition` |
| DrivingMode | `checkDrivingModeStateCapabilities` | `getCurrentDrivingModeState` | `registerDrivingModeState` / `unregisterDrivingModeState` |
| Wiper | `checkWiperStateCapabilities` | `getCurrentWiperState` | `registerWiperState` / `unregisterWiperState` |

EvBattery(충전 상태)와 Light(앰비언트 라이트)는 도메인이 있다는 것만 확인했다. 메서드 이름은 직접 확인할 것.

**권한 (AndroidManifest `uses-permission`)**
- 읽기 권한은 `pleos.car.permission.CAR_*`, 제어 권한은 `CONTROL_CAR_*` 형식
- 문서의 도메인별 대표 읽기 권한: Door → `CAR_DOORS`, Window·Wiper → `CAR_WINDOWS`, HVAC → `CAR_CLIMATE`, DrivingMode·Brake → `CAR_DRIVE`, EvBattery → `CAR_ENERGY`, Light·TurnSignal → `CAR_LIGHTS`
- API마다 필요한 권한이 다를 수 있으므로 각 API의 `@RequiresPermission`을 확인한다. 공식 샘플 앱 매니페스트에는 `READ_CAR_STEERING`, `READ_WINDSHIELD_WIPERS` 같은 권한도 있었다
- 공모전 개발 단계에서는 별도 승인 없이 매니페스트 선언만으로 에뮬레이터에서 호출된다
- 사용하는 도메인의 권한만 선언한다

**주행 제한**
- 주행 최적화를 선언하지 않은 앱은 주차 중에만 허용되고 주행 중에는 플랫폼이 가린다
- 앱은 `CarUxRestrictions` 상태에 맞춰 UX 제한을 적용해야 한다

### 2.2 확인이 필요한 것 (첫 작업으로 검증)

| # | 확인할 것 | 방법 |
|---|---|---|
| V1 | 위 표의 각 메서드 파라미터, 리스너 타입, 콜백 값 타입·단위·enum | IDE에서 SDK 시그니처 확인, API 레퍼런스 |
| V2 | 에뮬레이터에서 각 항목이 실제로 지원되는지, Virtual Console로 값을 바꿀 수 있는지 | 공식 샘플 앱(`Pleos-Playground/pleos-sample-vehicle-app`)을 AVD에 설치해 항목별 지원 상태 확인 |
| V3 | 기어 상태 읽는 법. Vehicle SDK에는 기어 도메인이 없다. AAOS 표준 `CarPropertyManager`의 `GEAR_SELECTION`(권한 `android.car.permission.CAR_POWERTRAIN`)으로 읽는 것이 계획이다 | 에뮬레이터에서 직접 구독해 P↔D 전환이 들어오는지 확인 |
| V4 | 핸들 각도의 단위·범위·부호(좌/우), 콜백 빈도 | 로그로 측정 |
| V5 | 방향지시등 상태 값 종류(꺼짐/좌/우/비상등 등)와, 점멸마다 콜백이 오는지 상태 변화 때만 오는지 | 로그로 측정 |
| V6 | 차량 신호가 앱에 도착하기까지의 지연 시간 | Virtual Console 조작 시각과 콜백 시각 비교 |
| V7 | 에뮬레이터 화면 규격(해상도·비율), 멀티 윈도우 시 앱 영역 크기 | AVD에서 확인. README에 기재해야 함 |
| V8 | 오디오 포커스 정책(문서: Audio Focus Development Guide) | 문서 확인 |

V2에서 지원되지 않는 항목이 나오면 6.3의 대체 규칙을 따르고 `docs/FINDINGS.md`에 적는다.

### 2.3 참고 링크

- 시작 가이드: https://document.pleos.ai/en/docs/pleos-only/getting-started
- Connect SDK 개요: https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/
- Vehicle SDK 소개: https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/intro
- Vehicle SDK API 레퍼런스: https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/entry
- 도메인별 API 목록: https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/entry
- 리스너 목록: https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.listener/entry
- 권한: https://document.pleos.ai/en/api-reference/connect-sdk-pleos/Vehicle/vehicle-permission
- 샘플 앱 튜토리얼: https://document.pleos.ai/docs/pleos-only/tutorial/vehicle-sdk-sample-app
- 공식 샘플 앱 저장소: https://github.com/Pleos-Playground/pleos-sample-vehicle-app
- 주행 제한 개발 가이드: https://document.pleos.ai/en/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide
- 주행 안전 설계: https://document.pleos.ai/en/docs/pleos-only/vehicle-app-planning-guide/driver-distraction-guideline
- UI 디자인 가이드: https://document.pleos.ai/en/docs/connect/guide/docs-design/Introduction/intro
- 공모전 제출 안내: https://appdevchallenge.auto-hmg.io/guide/submission

---

## 3. 제품 개념

### 3.1 사용자와 상황
- 전기차 충전 대기, 픽업 대기, 약속 전 주차장처럼 P단에서 5~10분을 보내는 운전자와 동승자(아이 포함)
- 음악을 모르는 사람이 대상이다. 틀린 소리가 나지 않아야 하고, 1분 안에 "내가 만든 곡"이 들려야 한다

### 3.2 핵심 경험
1. 트랙 하나를 고르고 녹음을 누른다
2. 차량 조작(또는 화면 패드)으로 2마디를 연주한다
3. 연주가 자동으로 반복 재생된다
4. 그 위에 다음 트랙을 쌓는다 (최대 4트랙)
5. 공조 버튼으로 트랙을 넣었다 빼고, 풍량으로 템포를 바꾸며 곡의 흐름을 만든다
6. 곡을 저장한다

### 3.3 하지 않는 것 (범위 밖)
- 주행 중 사용, 음성 인식, 내비게이션 연동, 로그인·계정, 결제
- 오디오 파형 편집, 마이크 녹음
- 도어 여닫기, 시트 열선·시트 위치, 안전벨트를 입력으로 쓰는 것 (안전상 제외로 결정됨)

---

## 4. 기능 요구사항

우선순위: **P0** = 시연에 반드시 필요, **P1** = 완성도, **P2** = 시간이 남으면.

### 4.1 P단 잠금 (P0)
- 앱 시작 시와 실행 중 계속 기어 상태를 확인한다
- P가 아니면 제작 화면 대신 "주차(P) 후 이용할 수 있어요" 안내 화면을 보여주고, 재생 중인 소리를 즉시 멈춘다
- P로 돌아오면 직전 세션(쌓아 둔 루프)을 그대로 복원한다
- `CarUxRestrictions`도 함께 구독해, 제한이 걸리면 같은 처리를 한다
- 기어를 읽을 수 없는 환경이면 P로 간주하되 화면에 "기어 확인 불가" 표시를 한다 (개발·시연용 안전장치)

### 4.2 루프 엔진 (P0)
- 박자: 4/4, 루프 길이 2마디, 16분음표 격자(총 32스텝)
- 템포: 기본 100 BPM, 범위 70~140 BPM
- 트랙 4개: `DRUM`, `BASS`, `MELODY`, `FX`
- 트랙 상태: 비어 있음 / 녹음 대기 / 녹음 중 / 재생 중 / 꺼짐(뮤트)
- 녹음 흐름: 트랙 선택 → 녹음 버튼 → 1마디 카운트인(메트로놈 4번) → 2마디 녹음 → 자동으로 재생 상태 전환
- 오버더빙: 이미 내용이 있는 트랙을 다시 녹음하면 덮어쓴다. "겹쳐 넣기" 옵션은 P1
- 퀀타이즈: 입력 시각을 가장 가까운 16분음표 스텝에 맞춘다. 입력 지연 보정값(ms)을 설정으로 둔다 (V6 측정값으로 기본값 결정)
- 녹음 중에도 입력 즉시 소리가 나야 한다 (모니터링). 퀀타이즈는 기록에만 적용한다
- 전체 재생/정지, 트랙별 지우기, 전체 지우기
- 템포를 바꿔도 기록된 스텝 위치는 유지된다 (스텝 기반 저장)

### 4.3 트랙별 소리와 입력 (P0)

녹음 대기·녹음 중인 트랙에 따라 "연주 입력"이 내는 소리가 달라진다.

| 선택 트랙 | 브레이크 밟음 | 방향지시등 좌 | 방향지시등 우 | 핸들 구간(좌/중/우) | 화면 패드 |
|---|---|---|---|---|---|
| DRUM | 킥 | 스네어 | 하이햇 | 사용 안 함 | 킥·스네어·하이햇·클랩 4패드 |
| BASS | 현재 구간의 음 연주 | 한 옥타브 아래 음 | 한 옥타브 위 음 | 음 선택(1도/4도/5도) | 3음 패드 |
| MELODY | 현재 구간의 음 연주 | 이전 음 | 다음 음 | 음역 선택(낮음/중간/높음) | 펜타토닉 5~8음 패드 |
| FX | 임팩트 | 라이저 | 스윕 | 사용 안 함 | 효과음 4패드 |

- 음계는 펜타토닉 등 "아무 음이나 눌러도 어울리는" 음만 쓴다
- 방향지시등은 상태가 꺼짐에서 좌 또는 우로 **바뀌는 순간**만 1회 입력으로 처리한다. 점멸 주기마다 오는 신호가 있다면 무시한다 (V5)
- 브레이크는 뗌→밟음으로 바뀌는 순간만 입력이다
- 핸들은 중앙 기준 좌우 약 30° 이내를 3구간으로 나눈다. 구간 경계에서 떨리지 않도록 여유 폭(히스테리시스)을 둔다. 실제 각도 범위는 V4 결과로 조정한다
- 위 표의 세부 배치는 초안이다. 연주해 보고 더 자연스러운 배치가 있으면 바꾸고 문서에 남긴다

### 4.4 트랙 스위치 (P0)
쌓아 둔 트랙을 켜고 끈다.

| 차량 조작 | 대상 트랙 |
|---|---|
| A/C 버튼 | DRUM |
| AUTO 버튼 | BASS |
| 내기순환 버튼 | MELODY |
| 성에 제거 버튼 | FX |

- 공조 상태가 **바뀔 때마다** 해당 트랙의 켜짐/꺼짐을 뒤집는다. 공조가 켜져 있는지 꺼져 있는지 자체는 의미가 없다 (앱 시작 시 공조 상태와 무관하게 동작하도록)
- 켜짐/꺼짐은 다음 스텝 경계에서 적용한다
- 화면의 트랙 카드에도 같은 스위치가 있다

### 4.5 노브·페이더 (P1)

| 차량 조작 | 효과 | 규칙 |
|---|---|---|
| 풍량 단계 | 템포 | capability로 받은 최소~최대 단계를 70~140 BPM에 고르게 대응 |
| 설정 온도 | 조(키) | 앱 시작 시 온도를 기준 0으로 두고, 한 단계 변할 때마다 반음 ±1, 범위 −3~+3 |
| 앞좌석 창문 위치 | 이펙트 양 | 0~100%를 FX 트랙 음량 또는 전체 필터 양에 대응 |
| 드라이브 모드 | 사운드팩 | 모드가 바뀔 때마다 다음 사운드팩으로 전환 |
| 와이퍼 상태 | 전체 이펙트 단계 | 상태가 바뀌는 순간에만 없음→약→강 순환 |

- 모든 항목에 같은 기능의 화면 조작(슬라이더·버튼)이 있다
- 사운드팩은 최소 2종 (예: 기본 / 일렉트로닉)

### 4.6 조용한 연주 모드 (P1)
- 켜면 방향지시등·와이퍼 입력을 무시한다 (차 밖으로 드러나는 조작)
- 첫 실행 안내 화면에서 "주변에 차량과 사람이 많으면 이 모드를 쓰세요"를 알린다
- 기본값은 꺼짐, 설정은 저장한다

### 4.7 곡 저장·보관함 (P1)
- 현재 루프(4트랙의 스텝 데이터, 템포, 조, 사운드팩, 뮤트 상태)를 이름과 함께 기기에 저장한다
- 보관함에서 불러와 다시 재생·편집한다
- 저장 형식은 JSON. 네트워크 없이 동작한다
- 곡마다 "어떤 조작이 어떤 소리를 냈는지" 요약(입력 종류별 횟수)을 함께 저장해 카드에 보여준다

### 4.8 함께 쌓기 (P1)
- 화면 패드를 넓게 띄우는 모드. 운전석은 차량 조작으로, 동승자는 화면 패드로 서로 다른 트랙을 맡는다
- 구현상으로는 "차량 입력이 향하는 트랙"과 "화면 패드가 향하는 트랙"을 따로 고를 수 있게 하면 된다

### 4.9 충전 세션 (P2)
- 충전 중이면 홈 화면에 "충전 세션 시작" 카드를 보여주고, 저장하는 곡에 충전 세션 표시를 붙인다
- 충전 상태를 읽을 수 없으면 카드를 숨긴다

### 4.10 루프 챌린지 (P2)
- 미리 만든 루프를 들려주고, 사용자가 같은 스텝에 입력하면 점수를 준다
- 내장 챌린지 3개면 충분하다

### 4.11 선택 기능 (P2, 제어 API 사용)
- 앰비언트 라이트를 박자에 맞춰 밝기 변화. 지원 차량에서만, 설정으로 끌 수 있게
- 앱 종료 시 풍량·온도를 시작 전 값으로 되돌릴지 묻기
- 둘 다 제어 권한 선언이 필요하므로, 넣을 때만 권한을 추가한다

### 4.12 곡 공유 (P2)
- 저장한 곡을 QR 코드로 폰에 전달. 서버가 필요하므로 PT 범위에서는 화면 구성만 두고 "준비 중"으로 표시해도 된다

---

## 5. 화면 구성

차량 디스플레이는 가로로 넓고, 운전석에서 팔을 뻗어 누른다. 멀티 윈도우로 앱 영역이 좁아질 수 있다.

### 5.1 공통 원칙
- 터치 영역을 크게, 글자를 적게, 대비를 높게 (Pleos Connect UI 디자인 가이드 준수)
- 어두운 테마 기본
- 화면 크기에 따라 배치가 바뀌는 반응형 (Jetpack Compose)
- 텍스트 입력을 요구하지 않는다. 곡 이름은 자동 생성(예: "11월 14일 세션 2")

### 5.2 화면 목록

| 화면 | 내용 | 우선순위 |
|---|---|---|
| 잠금 안내 | P단이 아닐 때. 안내 문구만 | P0 |
| 첫 실행 안내 | 3장 이내. 이 차에서 쓸 수 있는 조작, 주변 배려 안내 | P1 |
| 스튜디오 (메인) | 아래 5.3 | P0 |
| 내 차 장비 | 조작별 지원 여부와 맡은 역할 목록. 조작하면 해당 줄이 반짝인다 (연결 확인용) | P0 |
| 보관함 | 저장한 곡 카드 목록 | P1 |
| 설정 | 조용한 연주 모드, 입력 지연 보정, 사운드팩 | P1 |

### 5.3 스튜디오 화면 요소
- **트랙 카드 4개**: 트랙 이름, 2마디 32스텝 격자(입력이 찍힌 칸 표시), 켜짐/꺼짐 스위치, 선택 표시, 지우기. 각 카드에 그 트랙을 맡은 공조 버튼 아이콘 표시
- **재생 위치 표시**: 현재 스텝을 가로지르는 선
- **조작부**: 재생/정지, 녹음, 템포, 조, 사운드팩
- **화면 패드**: 선택된 트랙에 맞는 4~8개 패드
- **입력 표시줄**: 방금 들어온 차량 입력을 아이콘으로 잠깐 보여준다 ("브레이크 → 킥"). 시연 때 차량 조작과 소리의 연결이 눈에 보여야 한다

"내 차 장비" 화면과 "입력 표시줄"은 시연의 설득력을 좌우하므로 P0다.

---

## 6. 기술 설계

### 6.1 구성
- Kotlin, 단일 Activity, Jetpack Compose, ViewModel + StateFlow
- 의존성 주입은 가볍게(Hilt 또는 수동). 과한 추상화는 피한다

```
app/
  input/      VehicleInputSource (인터페이스)
              PleosVehicleInputSource   실제 SDK
              FakeVehicleInputSource    개발·테스트용 가짜 입력
              GearSource                P단 판정 (AAOS 표준)
              InputMapper               원시 신호 → InputEvent
  engine/     LoopEngine, Transport(박자 시계), Quantizer, Track, Song
  sound/      SoundBank(사운드팩), Sampler(재생)
  data/       SongRepository(JSON 저장)
  ui/         화면별 Composable, ViewModel
```

### 6.2 입력 계층
- 모든 차량 신호는 `VehicleInputSource`를 거쳐 공통 이벤트로 변환한다

```kotlin
sealed interface InputEvent {
    val timestampNanos: Long          // 수신 시각 (SystemClock.elapsedRealtimeNanos)
    data class Trigger(val source: TriggerSource, override val timestampNanos: Long) : InputEvent
    // source: BRAKE, TURN_LEFT, TURN_RIGHT
    data class Zone(val zone: SteeringZone, override val timestampNanos: Long) : InputEvent
    // zone: LEFT, CENTER, RIGHT
    data class Toggle(val source: ToggleSource, override val timestampNanos: Long) : InputEvent
    // source: AC, AUTO, RECIRCULATION, DEFROSTER
    data class Level(val source: LevelSource, val normalized: Float, override val timestampNanos: Long) : InputEvent
    // source: FAN_SPEED, TEMPERATURE, WINDOW  (0.0~1.0 또는 단계)
    data class Cycle(val source: CycleSource, override val timestampNanos: Long) : InputEvent
    // source: DRIVE_MODE, WIPER
}
```

- 시작 순서: `initialize()` → 항목별 capability 확인 → 지원되는 것만 `register…` → 초기값 `get…`으로 기준값 저장
- 종료 순서: 모든 `unregister…` → `release()`. 콜백 진행 중에 release하지 않는다
- 각 항목의 지원 여부를 `Map<InputKind, SupportState>`로 노출해 "내 차 장비" 화면이 쓴다
- `onFailed`는 로그로 남기고 해당 항목만 미지원 처리한다
- `FakeVehicleInputSource`는 디버그 빌드의 숨은 패널(또는 키보드)로 모든 이벤트를 발생시킬 수 있게 한다. 에뮬레이터 없이도 엔진과 UI를 개발·테스트하기 위해서다

### 6.3 미지원 항목 대체 규칙
| 상황 | 처리 |
|---|---|
| 트랙 스위치용 공조 항목 일부 미지원 | 지원되는 공조 항목을 순서대로 DRUM→BASS→MELODY→FX에 배정. 남는 트랙은 화면 스위치만 |
| 공조 전체 미지원 | 트랙 스위치는 화면만. "내 차 장비"에 미지원 표시 |
| 핸들 각도 미지원 | 음 선택을 화면 3버튼으로 |
| 풍량·온도·창문·드라이브 모드·와이퍼 미지원 | 화면 슬라이더·버튼만 |
| 브레이크·방향지시등 미지원 | 화면 패드만 |

### 6.4 루프 엔진
- 박자 시계는 전용 스레드에서 단조 시계(`elapsedRealtimeNanos`)로 돌린다. UI 프레임에 의존하지 않는다
- 다음 스텝들을 조금 앞서 예약하는 방식(lookahead)으로 흔들림을 줄인다
- 데이터 모델

```kotlin
data class Note(val step: Int, val sampleId: String, val velocity: Float = 1f)
data class Track(val kind: TrackKind, val notes: List<Note>, val muted: Boolean)
data class Song(
    val id: String, val name: String, val createdAt: Long,
    val bpm: Int, val transpose: Int, val soundPackId: String,
    val tracks: List<Track>, val inputSummary: Map<String, Int>
)
```

- 퀀타이즈: `step = round((입력시각 − 지연보정 − 루프시작시각) / 스텝길이) mod 32`
- 같은 스텝·같은 샘플 중복 입력은 하나로 합친다

### 6.5 소리
- P0는 `SoundPool`로 짧은 샘플을 재생한다. 지연이 크면 `AudioTrack` 직접 믹싱이나 Oboe를 검토한다
- 조옮김은 재생 속도(rate)로 처리해도 된다 (±3반음 범위)
- 오디오 속성은 미디어 용도로 설정하고 오디오 포커스를 요청·반납한다. 포커스를 잃으면 재생을 멈춘다 (V8)
- 샘플은 `assets/soundpacks/<팩이름>/`에 두고, 출처·라이선스를 `assets/soundpacks/LICENSES.md`에 적는다

### 6.6 기어·주행 제한
- `Car.createCar` → `CarPropertyManager`로 `VehiclePropertyIds.GEAR_SELECTION` 구독, `GEAR_PARK`일 때만 허용 (V3로 검증)
- `CarUxRestrictionsManager`로 제한 상태 구독
- 둘 중 하나라도 "제한"이면 잠금 화면 + 소리 정지

### 6.7 상태 보존
- 화면 회전·멀티 윈도우 크기 변경·백그라운드 전환에서 현재 루프가 사라지지 않게 한다
- 백그라운드로 가면 재생을 멈춘다

---

## 7. 품질 기준

### 7.1 완료 조건 (P0)
- [ ] 에뮬레이터에서 앱이 설치·실행된다 (release APK)
- [ ] Virtual Console에서 기어를 D로 바꾸면 즉시 잠기고 소리가 멈춘다. P로 돌리면 루프가 복원된다
- [ ] Virtual Console에서 브레이크·방향지시등을 조작하면 소리가 나고 입력 표시줄에 뜬다
- [ ] DRUM 트랙을 녹음하면 2마디 뒤 자동 반복된다
- [ ] 반복 중에 BASS, MELODY, FX를 차례로 쌓을 수 있다
- [ ] 공조 버튼 상태를 바꾸면 대응 트랙이 켜지고 꺼진다 (에뮬레이터 미지원이면 화면 스위치로 동일 동작 + FINDINGS 기록)
- [ ] 차량 입력이 하나도 지원되지 않는 상황에서도 화면만으로 4트랙 루프를 완성할 수 있다
- [ ] 3분간 연속 재생해도 박자가 밀리거나 소리가 끊기지 않는다
- [ ] 미지원 항목·콜백 실패로 앱이 종료되지 않는다

### 7.2 테스트
- 단위 테스트: Quantizer, InputMapper(구간 판정·토글·방향지시등 1회 처리), LoopEngine(녹음·덮어쓰기·뮤트), SongRepository
- `FakeVehicleInputSource`로 입력 시나리오를 재생하는 통합 테스트 1개 이상
- 수동 시연 스크립트 `docs/DEMO.md`: Virtual Console 조작 순서와 기대 결과를 단계별로 적는다

---

## 8. 제출 산출물 요건 (공모전 규정)

- 저장소에 소스 코드 전체와 APK를 올린다
- APK 위치: 저장소의 `apk/` 폴더. 용량이 커서 안 올라가면 GitHub Releases에 등록
- APK 파일명: `팀명_앱이름_release.apk` (팀명은 사용자에게 확인)
- APK는 실행 가능한 **release 빌드**여야 한다
- `README.md` 필수 항목
  1. 앱 이름과 한 줄 소개
  2. 실행 방법
  3. 테스트 환경과 필요한 설정
  4. 개발에 사용한 Pleos Connect 에뮬레이터 버전과 화면 규격
  5. APK 파일 위치 또는 Release 링크
- README에 추가로 넣을 것: 사용한 SDK·API와 권한 표, 차량 조작 매핑 표, 사운드 샘플 출처
- 제출 시 메일에 저장소 주소와 최종 커밋 해시를 적으므로, 제출용 커밋에 태그를 단다
- 공유 자료에 개인정보·계정정보·개발자 실명이 드러나지 않게 한다 (커밋 작성자 정보 포함 여부를 사용자에게 확인)

---

## 9. 일정

| 기간 | 목표 | 산출물 |
|---|---|---|
| 0단계 (착수 직후) | 환경 구성, 2.2의 V1~V8 검증 | `docs/FINDINGS.md`, 상세 개발 계획서 |
| ~11/22 | 입력 계층(실제 + 가짜), 사운드 재생, 브레이크·방향지시등·핸들 연주, 1트랙 루프 녹음·반복, P단 잠금 | 동작하는 최소 앱 |
| ~11/29 | 4트랙 오버더빙, 공조 트랙 스위치, 풍량 템포 등 노브, 스튜디오·내 차 장비 화면, 저장·보관함, 조용한 연주 모드 | P0 + P1 대부분 |
| ~12/02 | 함께 쌓기, 남은 P1, 가능하면 P2, 시연 스크립트 점검, release APK, README | 제출용 태그 |

1차 서류 결과는 11/23에 발표된다. 범위를 줄여야 하면 P2 → P1 순으로 뺀다. P0는 줄이지 않는다.

---

## 10. 먼저 해 줄 일

1. 이 문서를 읽고 불명확한 점을 질문 목록으로 정리한다
2. 0단계 검증(V1~V8) 계획을 세우고 실행해 `docs/FINDINGS.md`를 채운다
3. 검증 결과를 반영해 상세 개발 계획서를 작성한다 (작업 단위, 순서, 각 작업의 완료 확인 방법)
4. 계획서에 대한 확인을 받은 뒤 구현을 시작한다

### 사용자에게 확인이 필요한 결정
- 팀명 (APK 파일명·README에 필요)
- 저장소 공개 여부 (비공개로 제출하면 11/16까지 공개 전환해야 가산점 반영)
- 트랙별 소리·입력 배치(4.3)와 공조 버튼 배정(4.4)의 최종안
- 사운드팩의 음악 스타일
