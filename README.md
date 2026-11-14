# P-JAM (피잼)

주차(P단) 중인 차의 조작계로 4트랙 루프를 쌓는 Pleos Connect(Android Automotive) 전용 루프스테이션 앱.
DRUM / BASS / MELODY / FX 네 트랙을 2마디(32스텝) 루프로 녹음하고 겹쳐 쌓는다.

> **프로토타입(2026-11-15)입니다.** 아래 "지금 되는 것"만 동작을 확인했습니다. 최종 제출본은 별도로 만듭니다.

## 실행 방법

1. Pleos Connect 에뮬레이터(Android 14, 이미지 `Pleos_Connect_v2.1.0`)를 켜고 기어를 **P**로 둔다 (D이면 플랫폼이 앱을 가린다).
2. APK 설치: `adb install -r apk/TeamPJAM_PJAM_prototype_debug.apk` (또는 Android Studio에서 `app` 실행).
3. 앱을 열면 스튜디오 화면이 나온다. 트랙 카드를 눌러 트랙을 고르고, 상단 **녹음**을 누른다.
4. 1마디 카운트인(메트로놈) 뒤 2마디 동안 화면 패드나 디버그 패널로 연주하면, 녹음이 끝나는 즉시 자동 반복된다.

소스에서 빌드: `./gradlew assembleDebug` (JDK는 Android Studio 내장 JBR 사용). 전체 검증: `scripts/check.sh`.

## 지금 되는 것 / 안 되는 것

| 상태 | 항목 |
|---|---|
| 되는 것 | 4트랙 구조의 루프 엔진(카운트인 → 녹음 → 자동 반복, 덮어쓰기, 재생 중 다른 트랙 녹음, 트랙 뮤트·지우기, 템포 70–140 BPM) |
| 되는 것 | 화면 패드 연주(트랙별 패드), 입력 표시줄, 트랙 카드의 32스텝 격자와 재생 위치 |
| 되는 것 | 디버그 빌드의 가짜 차량 입력 패널(브레이크·방향지시등·핸들·공조 버튼) |
| 안 되는 것 / 미확인 | 실제 차량 신호 연결(Vehicle SDK), 기어 잠금 화면, 풍량→템포·온도→조 등 노브, 보관함(저장), 설정, release 서명 APK |

## 테스트 환경

- 개발 PC: macOS (Apple Silicon), Android Studio
- 에뮬레이터: Pleos Connect Emulator `Pleos_Connect_v2.1.0`, Android 14 (API 34), 2560×1440 @160dpi, Vehicle Service 2.3.22
- 빌드: AGP 9.5.1, Kotlin 2.4.0, Jetpack Compose, minSdk 28 / targetSdk 34
- 사용 SDK: Pleos Vehicle SDK `ai.pleos.playground:Vehicle:2.3.2` (프로토타입에서는 의존성만 포함. 연결은 다음 단계)

## 사운드 출처

모든 샘플은 `tools/soundgen/generate.py`로 직접 합성했다(외부 샘플 없음). 자세한 내용은 `assets/soundpacks/LICENSES.md`.

## 문서

- 진행 상태: `docs/PROGRESS.md`
- 검증 결과·에뮬레이터 관찰: `docs/FINDINGS.md`
