# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project state

P-JAM (피잼) is an in-car loop-station app for Pleos Connect (Android Automotive OS). While parked (P gear), the user layers 4 tracks (DRUM/BASS/MELODY/FX) by operating vehicle controls: brake, turn signals, steering angle, and HVAC buttons. It is built for a Hyundai Group vehicle-app contest and demoed on the Pleos Connect Emulator plus its Virtual Console. There is no real vehicle.

**The repo is currently the untouched Android Studio template**: a single `:app` module with no Kotlin sources, no Compose, no Vehicle SDK dependency, and an `<application>` with no activity. The real specification lives in `docs/`. Read both documents before implementing anything, because the plan alone is not enough:

- `docs/P-JAM_개발_기획서_에이전트용.md`: requirements (the source of truth). It holds the input→sound mapping tables (4.3, 4.4), the data model (6.2, 6.4), the fallback rules (6.3), completion criteria (7.1) and submission requirements (8).
- `docs/P-JAM_개발_계획서_에이전트용_v2.md`: execution plan. It breaks work into task IDs (T0-x, T1-x, ...), sets milestones and decision gates, and lists "completion checks". Its section 1 ("pre-checked facts") and section 3 ("plan-stage decisions") override the requirements document where the two differ. Section 14 lists what changed in revision 2.

Both documents are written in Korean. The plan sets 2026-11-15 as the prototype milestone (M-P) and 2026-12-02 as the code freeze. Speed beats polish: when blocked, take the documented default or fallback and record it. Don't wait for answers.

## Build and test commands

Gradle wrapper, AGP 9.5.1, version catalog at `gradle/libs.versions.toml`. Gradle's configuration cache is on.

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest                      # JVM unit tests
./gradlew testDebugUnitTest --tests "com.example.pleos_car_jam.ExampleUnitTest"   # one class (append .method for one test)
./gradlew lintDebug
./gradlew connectedDebugAndroidTest              # instrumented tests; needs a running emulator/device
./gradlew lintDebug testDebugUnitTest assembleDebug   # the plan's daily "must pass" bar
```

The plan calls for a `scripts/check.sh` that runs the line above, then installs and smoke-tests on a connected emulator. It prints one `PASS`/`FAIL` line per criterion and exits non-zero on any `FAIL`. It does not exist yet. The smoke test starts the app with `adb shell am start`, checks `uiautomator dump` for expected `contentDescription`/`testTag` nodes, and fails on any `FATAL EXCEPTION` in `adb logcat -d`.

Virtual Console is a web page served by the emulator (v2.1.0+): `adb forward tcp:8088 tcp:8088`, then open `http://127.0.0.1:8088/`.

## Divergences between the template and the plan

The template does not yet match what the plan prescribes. Reconcile these before building on it:

- Plan: Kotlin 2.0.0, Compose (BOM 2024.04.01+), AGP 8.8.2, JDK 17, minSdk 28, compileSdk/targetSdk 34 (compileSdk 35 only if the SDK requires it). The template has AGP 9.5.1, compileSdk/targetSdk 37, no Kotlin or Compose plugins, and AppCompat/Material views instead of Compose.
- Plan: applicationId is the placeholder `app.pjam.studio` (no real name or account). The template uses `com.example.pleos_car_jam`.
- Plan: Vehicle SDK `ai.pleos.playground:Vehicle:2.3.2`, falling back to `2.3.1`. It also needs `android { useLibrary("android.car") }` and a manifest `uses-feature android.hardware.type.automotive`. Never declare `distractionOptimized`.
- Plan: add `*.jks`, `*.keystore` and `keystore.properties` to `.gitignore`. The project is not a git repo yet (planned remote: `github.com/ppnavillera/pleos-car-jam`). Ask before the first push.
- Plan: `docs/` (FINDINGS.md, PLAN_CHANGES.md, PROGRESS.md, DEMO.md) and `scripts/` live at the repo root. The two spec files now live in `docs/`. A new session should read `docs/PROGRESS.md` first once it exists.

## Architecture (planned)

Single Activity, Jetpack Compose, ViewModel + StateFlow, manual DI via an `AppContainer` (no Hilt), kotlinx.serialization for JSON. Package layout: `input/`, `engine/`, `sound/`, `data/`, `ui/`.

**Input layer.** Every vehicle signal goes through `VehicleInputSource` and comes out as a common `InputEvent`: `Trigger` (brake, turn left/right), `Zone` (steering LEFT/CENTER/RIGHT), `Toggle` (A/C, AUTO, recirculation, defroster), `Level` (fan speed, temperature, window), `Cycle` (drive mode, wiper). Implementations are `PleosVehicleInputSource` (real SDK) and `FakeVehicleInputSource` (debug panel). `InputMapper` turns raw values into events. It fires on "value differs from the previous one", not on every callback. Brake is a 0–100% value, so it triggers when the value rises past 5% and re-arms below 2%. Turn signals trigger on off→left and off→right, and Blink and Flash count as the same direction.

**All Vehicle SDK calls stay in one place** (`input/pleos/` or the single `PleosVehicleInputSource` file). SDK signatures are unverified beyond the official sample app, so don't guess them. Check with `javap`, the sources jar or the IDE, and record what you find in `docs/FINDINGS.md`. The SDK's flow is: `initialize` → per-item capability check → register only supported items → read the initial value with `get…`. The listener instance passed to `unregister…` must be the same one passed to `register…`. All values are nullable, and `onFailed` disables only that one item. Release order is unregister everything, then `release()`, and never release mid-callback.

**Capability rule.** Every input is optional. Unsupported items are disabled quietly and replaced by on-screen controls. The app must be fully playable with the screen alone, and it must never crash because of an unsupported signal. Fallbacks are in the requirements doc 6.3.

**Gear lock.** `GearSource` is the only place that touches `Car`, `CarPropertyManager` and `CarUxRestrictions`, and it catches `Throwable` (`NoClassDefFoundError` on non-automotive emulators). Fallback order: `GEAR_SELECTION` property → `CURRENT_GEAR` → UX restrictions only → assume P and show "gear unknown". Anything other than P shows a lock screen and stops sound. P again restores the session. The debug build has a button that forces the lock on.

**Engine.** `LoopEngine`, `Transport`, `Quantizer`, `Track` and `Song` are pure Kotlin with no Android imports. The clock and `Sampler` are injected, so everything runs in JVM unit tests with a fake clock and fake sampler.
- The engine is built with 4 tracks from the start. Loops are 32 steps, with 70–140 BPM driven by fan speed.
- `Transport` runs on a dedicated thread and computes each step time as `start + n × stepLength`, not by accumulating, so drift stays at zero.
- SDK callbacks only stamp `elapsedRealtimeNanos` and return. Only the engine thread mutates engine state; the UI and inputs send it commands. The UI gets immutable snapshots through `StateFlow`.
- The recording state machine (count-in, record, overwrite, loop-boundary start, mute on the next step boundary) is specified in plan 3.3.

**Sound.** A `Sampler` interface sits in front of `SoundPoolSampler` (first implementation) and `MixerSampler` (AudioTrack mixing, the fallback if the scheduling jitter gate G2 fails). Pass criterion: p95 step error under 15 ms. Samples are synthesized by a script in `tools/soundgen/` and the WAVs are committed to `assets/soundpacks/`. No outside samples are used, and the origin goes in `LICENSES.md`. SoundPool `rate` is limited to 0.5–2.0, so octaves are separate samples. Rate is used only for ±3-semitone transposition. Oboe/NDK is not used.

## Rules from the specs that are easy to violate

- Don't use vehicle-control (set) APIs, except the optional P2 features in 4.11. Declare only the permissions for domains actually used. Some are `protectionLevel="normal"`: `pleos.car.permission.READ_CAR_STEERING`, `READ_WINDSHIELD_WIPERS`, `CAR_POWERTRAIN`. Confirm each from `@RequiresPermission`.
- Only the public SDKs are allowed: Vehicle, NaviHelper, TextToSpeech, SpeechToText and FusedLocation. This app uses Vehicle plus the AAOS standard API.
- Normalize values (window, fan speed) from the capability `min..max` range, never from an assumed 0–100.
- Keep real names and emails out of source, README and APK signing info. Never commit signing keys or `keystore.properties`. The commit author is whatever the machine's git config says: show `git config user.name` and `user.email` to the user before the first commit, and never change them.
- Commit messages start with the task ID from the plan (for example `T1-3`).
- Log anything unconfirmed, different from the docs, or broken in the emulator to `docs/FINDINGS.md` immediately.
- Never let a task block for more than 60 minutes. Switch to the documented fallback and note it in `docs/PROGRESS.md`.


# P-JAM

주차(P단) 중인 차의 조작계로 4트랙 루프를 쌓는 Pleos Connect(Android Automotive) 앱. 공모전 출품작이고 실차 없이 에뮬레이터로만 검증한다.

## 기준 문서
- 요구사항: `docs/P-JAM_개발_기획서_에이전트용.md`
- 순서·결정·검증 방법: `docs/P-JAM_개발_계획서_에이전트용_v2.md` (작업 ID는 이 문서의 것)
- 진행 상태: `docs/PROGRESS.md`. 새 세션은 이 파일부터 읽는다
- 검증 결과: `docs/FINDINGS.md`

## 명령
- 검증 전체: `scripts/check.sh` (작업을 끝냈다고 말하기 전에 통과시킨다)
- 빌드: `./gradlew assembleDebug` / 단위 테스트: `./gradlew testDebugUnitTest`
- Virtual Console: `adb forward tcp:8088 tcp:8088` 뒤 `http://127.0.0.1:8088/`

## 규칙
- Vehicle SDK 호출은 `input/pleos/` 패키지에만 둔다. 시그니처가 예상과 달라도 고칠 곳이 한 곳이어야 한다.
- SDK 시그니처를 추측하지 않는다. `javap`나 sources jar로 확인하고 FINDINGS V1 표에 적은 뒤 쓴다.
- 엔진(`engine/`)과 `InputMapper`는 Android 의존 없이 순수 Kotlin으로 쓴다. 시계와 Sampler는 인터페이스로 주입한다. JVM 단위 테스트로 돌리기 위해서다.
- 모든 차량 입력에는 같은 기능의 화면 조작이 있어야 한다. 에뮬레이터가 신호를 안 줘도 시연이 되어야 한다.
- 차량 제어(set) API와 주행 최적화 선언은 쓰지 않는다.
- 사운드는 `tools/soundgen/` 스크립트로 직접 합성한 것만 쓴다. 외부 샘플을 넣지 않는다.
- 서명 키, 비밀번호, 실명, 이메일을 저장소에 넣지 않는다. git 사용자 설정을 바꾸지 않는다.
- 커밋 메시지 앞에 작업 ID를 붙인다 (예: `T1-3 Transport`). push는 하지 않는다. 사람이 한다.
- 한 작업에서 60분 넘게 막히면 대체 경로(기본값, 화면 조작, 가짜 입력)로 넘어가고 PROGRESS의 "막힌 것"에 적는다.
- 작업을 끝낼 때마다 `docs/PROGRESS.md`를 갱신한다.

## 커밋 메시지
형식은 아래와 같다. type은 feat, fix, test, docs, refactor, chore 중 하나, 괄호 안은 계획서 작업 ID다.
본문은 "무엇이 어떻게 동작하게 됐는지"를 항목으로 적는다. push는 하지 않는다. 사람이 한다.

    feat(T1-6): 녹음을 종료 버튼까지 이어지게 변경

    - 녹음: 루프가 돌아도 계속되고, 바퀴마다 새 입력이 쌓임
    - 메트로놈: 카운트인과 녹음 중에 울리고 녹음이 끝나면 꺼짐
    - 기어 이탈·백그라운드 진입 시 녹음 종료, 내용 유지

    관련: T1-6, 계획서 3.3

    Co-Authored-By: <실제로 쓴 모델 이름> <noreply@anthropic.com>

## 확인된 사실 (2026-11-15 프로토타입)
- SDK 브레이크 값은 Boolean이다. Virtual Console 카드만 0~100%다.
- SDK 방향지시등 값은 OFF/LEFT/RIGHT 세 가지다.
- 에뮬레이터 기어가 P가 아니면 플랫폼이 앱을 가린다. 검증 전에 `scripts/vc.sh gear P`.
- 차량 값 조작은 `scripts/vc.sh`로 한다. inject-vhal-event는 막혀 있다.
- 툴체인은 AGP 9.5.1 / Kotlin 2.4.0 / compileSdk 36이다 (계획서 3.1과 다름).