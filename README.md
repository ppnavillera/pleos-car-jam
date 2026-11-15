# P-JAM (피잼)

## 1. 앱 이름과 한 줄 소개

**P-JAM (피잼)** — 주차(P단) 중인 차의 브레이크·방향지시등·핸들·공조 버튼으로 DRUM / BASS / MELODY / FX 네 트랙의 루프를 쌓는 Pleos Connect(Android Automotive) 전용 루프스테이션 앱.

> 이 문서는 접수용 초안(태그 `submission-draft-1115`) 기준입니다. 최종 제출본은 마감 전에 다시 만듭니다.

## 2. 실행 방법

실행할 파일은 **`apk/TeamPJAM_PJAM_release.apk`** (서명된 release 빌드)입니다.

1. Pleos Connect 에뮬레이터 `Pleos_Connect_v2.1.0`을 켠다.
2. APK를 설치한다: `adb install -r apk/TeamPJAM_PJAM_release.apk` (같은 앱의 debug 빌드가 깔려 있으면 서명이 달라 `adb uninstall app.pjam.studio`가 먼저 필요하다). 앱 이름 "P-JAM"을 연다.
3. Virtual Console을 연다: `adb forward tcp:8088 tcp:8088` 뒤 브라우저에서 `http://127.0.0.1:8088/`.
4. Virtual Console에서 **Gear Selection을 P**로, **HVAC Power를 On**, **HVAC Dual을 Off**로 둔다. 기어가 D이면 플랫폼이 앱을 가려 앱이 소리를 멈추고 잠금 화면을 띄운다.
5. 트랙 카드(DRUM → BASS → MELODY → FX)를 눌러 고르고 **녹음**을 누른다. 1마디 카운트인 뒤 녹음이 시작된다. 화면 패드를 치거나 Virtual Console에서 브레이크·방향지시등·핸들을 움직인다.
6. 녹음은 **녹음 버튼(■ 녹음 종료)을 다시 누를 때까지** 계속된다. 루프가 돌 때마다 앞 바퀴 내용이 재생되고 새 입력이 그 위에 쌓인다. 녹음 중에는 매 박 메트로놈이 울린다(트랙에는 기록하지 않음).
7. Virtual Console에서 A/C, AUTO, 내기순환, 성에 제거를 바꾸면 대응하는 트랙이 켜지고 꺼진다. 차량 입력이 하나도 없어도 화면 조작만으로 모든 것이 된다.

**시연은 전체 화면(에뮬레이터 창 전체)으로 한다.** 시연 순서는 `docs/DEMO.md`.

## 3. 테스트 환경과 필요한 설정

- 개발 PC: macOS (Apple Silicon), Android Studio. 실차 없이 에뮬레이터와 Virtual Console로만 검증했다.
- 에뮬레이터: Pleos Connect Emulator `Pleos_Connect_v2.1.0`, Android 14 (API 34), Vehicle Service 2.3.22.
- Virtual Console 설정: Gear = **P**, HVAC Power = **On**, HVAC Dual = **Off**, 방향지시등 Off, 브레이크 0%. (HVAC Power가 꺼져 있으면 공조 신호가 안 올 수 있고, 앱의 "내 차 장비" 화면이 그 사유를 보여 준다.)
- 소스에서 빌드: `./gradlew assembleDebug` (JDK는 Android Studio 내장 JBR). 전체 검증: `scripts/check.sh` (에뮬레이터가 켜져 있어야 하고 3분 재생 때문에 10분 남짓 걸린다. 빠르게는 `SKIP_LONG=1 scripts/check.sh`).
- 빌드 환경: AGP 9.5.1, Kotlin 2.4.0, Jetpack Compose, minSdk 28 / compileSdk 36 / targetSdk 34.

## 4. 개발에 사용한 에뮬레이터 버전과 화면 규격

- Pleos Connect Emulator **`Pleos_Connect_v2.1.0`** (Android 14, API 34)
- 화면: **2560 × 1440, 160 dpi** (가로)
- 넓은 화면(전체 화면)에서 쓰도록 만들었다. 좁은 창(멀티 윈도우)은 한 열 + 세로 스크롤로 열리지만 방향지시등 카메라 창을 피하지 못한다.

## 5. APK 파일 위치

- **`apk/TeamPJAM_PJAM_release.apk`** — 서명된 release 빌드 (저장소 안, GitHub Releases는 쓰지 않음)
- `apk/TeamPJAM_PJAM_prototype_debug.apk`는 11/15 프로토타입(debug 서명) 파일로, **제출본이 아니다.**

---

## 사용한 SDK·API와 권한

| 구분 | 내용 |
|---|---|
| SDK | Pleos Vehicle SDK `ai.pleos.playground:Vehicle:2.3.2` (읽기 전용) |
| 안드로이드 API | Compose, ViewModel/StateFlow, SoundPool, AudioManager(오디오 포커스), `CarUxRestrictions`(주행 제한을 알리는 보조 신호) |
| 권한(release) | `pleos.car.permission.CAR_DRIVE` — 브레이크·핸들 각도·기어 읽기 |
| | `pleos.car.permission.CAR_LIGHTS` — 방향지시등 읽기 |
| | `pleos.car.permission.CAR_CLIMATE` — A/C·AUTO·내기순환·성에 제거·공조 전원 읽기 |
| 쓰지 않는 것 | 차량 제어(set) API와 제어 권한, `distractionOptimized` 선언, 외부 샘플·외부 서비스 |
| debug 전용 | 측정용으로 `android.car.permission.CAR_POWERTRAIN`, `pleos.car.permission.CAR_WINDOWS`·`CAR_ENERGY`가 debug 매니페스트에만 있다 |

## 차량 조작 매핑

| 차량 조작 | DRUM | BASS | MELODY | FX |
|---|---|---|---|---|
| 브레이크를 밟음 | 킥 | 현재 음(가운데 옥타브) | 커서 위치의 음 | 임팩트 |
| 방향지시등 좌 | 스네어 | 현재 음, 한 옥타브 아래 | 커서를 한 칸 내리고 연주 | 라이저 |
| 방향지시등 우 | 하이햇 | 현재 음, 한 옥타브 위 | 커서를 한 칸 올리고 연주 | 스윕 |
| 핸들 좌 / 중 / 우 | (소리 없음) | 현재 음을 4도 / 1도 / 5도로 | 커서를 낮은 / 중간 / 높은 음역으로 | (소리 없음) |

| 공조 버튼 (바뀔 때마다) | 효과 |
|---|---|
| A/C | DRUM 트랙 켜기/끄기 |
| AUTO | BASS 트랙 켜기/끄기 |
| 내기순환 | MELODY 트랙 켜기/끄기 |
| 성에 제거 | FX 트랙 켜기/끄기 |

차량이 어떤 항목을 지원하지 않으면 그 입력은 조용히 꺼지고 같은 기능의 화면 조작(패드, 핸들 대체 3버튼, 트랙 스위치)이 대신한다. 지원되는 공조 항목만 DRUM → BASS → MELODY → FX 순으로 배정된다. 어떤 항목이 연결되었는지는 앱의 "내 차 장비" 화면에서 본다.

## 사운드 샘플 출처

모든 샘플은 `tools/soundgen/generate.py`로 직접 합성했다(외부 샘플 없음). 결과 WAV는 `assets/soundpacks/basic/`에 있고, 자세한 내용은 `assets/soundpacks/LICENSES.md`.

## 지금 되는 것 / 안 되는 것 (접수용 초안)

| 상태 | 항목 |
|---|---|
| 되는 것 | 4트랙 루프 엔진: 카운트인 → 녹음(종료 버튼까지 계속, 바퀴마다 쌓기) → 반복, 재생 중 다른 트랙 녹음(앞 트랙 유지), 트랙 뮤트(다음 스텝 경계)·지우기, 템포 70–140 BPM(화면 버튼) |
| 되는 것 | 화면 패드 연주, 핸들 대체 3버튼, 입력 표시줄, 트랙 카드의 32스텝 격자와 재생 위치. **차량 입력이 하나도 없어도 화면만으로 4트랙을 완성**(자동 시험 있음) |
| 되는 것 | Vehicle SDK 입력: 브레이크, 방향지시등(좌/우), 핸들 각도(좌/중/우 구간), 공조 4종과 전원 |
| 되는 것 | "내 차 장비" 화면: 항목별 연결 상태, 맡은 역할, 조작 시 줄 반짝임, 공조 전원이 꺼져 있을 때 사유, 기어 판정 방식 |
| 되는 것 | 기어 잠금: P가 아니면 약 30ms 안에 소리를 멈추고 잠금 화면. **잠금 직전에 재생 중이었다면 P로 돌아온 순간 루프 처음부터 재생을 다시 켠다.** 정지 상태였으면 정지로 두고, 녹음은 이어 가지 않는다(찍은 내용은 유지). 앱 전환(백그라운드)으로 멈춘 경우는 돌아와도 정지 상태 |
| 되는 것 | 방향지시등을 켜면 플랫폼이 카메라 창을 앱 왼쪽 위에 띄운다. 넓은 화면 배치는 그 자리를 비워 둔다 |
| 되는 것 | 3분 연속 재생(스텝 호출 오차 p95 < 1ms, 누락 0), SDK 초기화 실패·콜백 예외에도 앱이 죽지 않음 |
| 되는 것 | 서명된 release APK가 에뮬레이터에서 설치·실행되고 Virtual Console 브레이크·기어가 동작함 |
| 확인 안 됨(사람이 귀·눈으로) | 핸들 좌/우 방향(SDK 음수 = 왼쪽으로 구현), 모든 샘플의 소리, BASS/MELODY 연주감, 줄 반짝임 모양, 잠금 화면 모양과 문구, 소리가 스피커로 나오기까지의 출력 지연, 오디오 포커스 이동, 진짜 멀티 윈도우 분할 크기 |
| 아직 안 됨 | 풍량→템포·온도→조·창문→이펙트·와이퍼→이펙트 단계, 드라이브 모드(에뮬레이터 미지원, 사운드팩은 화면 버튼으로만), 보관함(저장)·설정·조용한 연주 모드·첫 실행 안내 |
| 알려진 제약 | 좁은 창에서는 방향지시등 카메라 창을 피하지 못한다 → 시연은 전체 화면으로. 최대 스텝 지연이 3분 측정에서 15ms를 넘은 적이 있다(원인 조사 중, `docs/FINDINGS.md`) |

## release 서명 키 (저장소에 넣지 않는다)

키는 저장소 밖에 둔다. 아래로 만들고(인증서에 실명·이메일을 넣지 않는다) `~/.pjam-signing/keystore.properties`에 `storeFile`, `storePassword`, `keyAlias`, `keyPassword`를 적는다(위치는 환경변수 `PJAM_KEYSTORE_PROPERTIES` 또는 저장소 루트 `keystore.properties`로 바꿀 수 있다). 키가 없으면 `assembleRelease`만 안내 문구와 함께 멈추고 debug 빌드·테스트는 영향이 없다. release APK는 `./gradlew assembleRelease` 후 `app/build/outputs/apk/release/app-release.apk`.

```bash
keytool -genkeypair -keystore ~/.pjam-signing/pjam-release.jks -storetype PKCS12 -alias pjam \
  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=P-JAM"
```

## 문서

- 진행 상태: `docs/PROGRESS.md`
- 검증 결과·에뮬레이터 관찰: `docs/FINDINGS.md`
- 계획서와 달라진 점: `docs/PLAN_CHANGES.md`
- 시연 순서: `docs/DEMO.md`
