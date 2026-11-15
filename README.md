# P-JAM (피잼)

주차(P단) 중인 차의 조작계로 4트랙 루프를 쌓는 Pleos Connect(Android Automotive) 전용 루프스테이션 앱.
DRUM / BASS / MELODY / FX 네 트랙을 2마디(32스텝) 루프로 녹음하고 겹쳐 쌓는다.

> **M2(P0 완성, 태그 `p0-complete`) 상태입니다.** 아래 "지금 되는 것"의 자동 시험은 `scripts/check.sh`가 돌립니다. 최종 제출본(`apk/`의 release APK)은 M4에서 따로 만듭니다.
> `apk/TeamPJAM_PJAM_prototype_debug.apk`는 11/15 프로토타입(debug 서명)이고, M2의 release APK는 `./gradlew assembleRelease`로 만듭니다(서명 키 필요, 아래).

## 실행 방법

1. Pleos Connect 에뮬레이터 `Pleos_Connect_v2.1.0`(Android 14, API 34, 2560×1440 @160dpi)을 켠다.
2. Virtual Console을 연다: `adb forward tcp:8088 tcp:8088` 뒤 브라우저에서 `http://127.0.0.1:8088/`.
3. Virtual Console에서 **기어를 P**로 둔다. D이면 플랫폼이 앱을 가려 앱이 소리를 멈추고 잠금 화면을 띄운다. 터미널에서는 `scripts/vc.sh gear P`.
4. Virtual Console에서 **HVAC를 Power On**으로, **HVAC Dual은 Off**로 둔다. Power가 꺼져 있으면 공조 신호가 안 올 수 있다(앱의 "내 차 장비"가 그 사유를 보여 준다).
5. APK 설치: release는 `./gradlew assembleRelease` 후 `adb install -r app/build/outputs/apk/release/app-release.apk`(debug와 서명이 달라 `adb uninstall app.pjam.studio`가 먼저 필요할 수 있음), debug는 `apk/TeamPJAM_PJAM_prototype_debug.apk` 또는 Android Studio에서 `app` 실행. 앱을 연다.
6. 트랙 카드를 눌러 트랙을 고르고 **녹음**을 누른다. 1마디 카운트인 뒤 녹음이 시작된다. 화면 패드로 치거나, Virtual Console에서 브레이크(킥)·방향지시등(좌/우 패드)·핸들(좌/중/우 구간)을 움직인다.
7. 녹음은 **녹음 버튼(■ 녹음 종료)을 다시 누를 때까지** 계속된다. 루프가 돌 때마다 앞 바퀴 내용이 재생되고 새 입력이 그 위에 쌓인다. 녹음 중에는 매 박 메트로놈이 울리고 첫 박이 강하다(트랙에는 기록하지 않는다). 다른 트랙 카드를 누르면 녹음이 끝나고 선택만 바뀐다.

소스에서 빌드: `./gradlew assembleDebug` (JDK는 Android Studio 내장 JBR 사용). 전체 검증: `scripts/check.sh` (에뮬레이터가 켜져 있어야 하고, 3분 재생 때문에 10분 남짓 걸린다. 빠르게는 `SKIP_LONG=1 scripts/check.sh`). 시연 순서: `docs/DEMO.md`.

### release 서명 키 (저장소에 넣지 않는다)

키는 저장소 밖에 둔다. 아래로 만들고(인증서에 실명·이메일을 넣지 않는다) `~/.pjam-signing/keystore.properties`에 `storeFile`, `storePassword`, `keyAlias`, `keyPassword`를 적는다(위치는 환경변수 `PJAM_KEYSTORE_PROPERTIES` 또는 저장소 루트 `keystore.properties`로 바꿀 수 있다). 키가 없으면 `assembleRelease`만 안내 문구와 함께 멈추고 debug 빌드·테스트는 영향이 없다.

```bash
keytool -genkeypair -keystore ~/.pjam-signing/pjam-release.jks -storetype PKCS12 -alias pjam \
  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=P-JAM"
```

## 지금 되는 것 / 안 되는 것 (M2, P0 완성본)

| 상태 | 항목 |
|---|---|
| 되는 것 | 4트랙 루프 엔진: 카운트인 → 녹음(종료 버튼까지 계속, 바퀴마다 쌓기) → 반복, 재생 중 다른 트랙 녹음(앞 트랙 유지), 트랙 뮤트(다음 스텝 경계)·지우기, 템포 70–140 BPM |
| 되는 것 | 화면 패드 연주, 핸들 대체 3버튼(BASS 1·4·5도, MELODY 음역), 입력 표시줄, 트랙 카드의 32스텝 격자와 재생 위치. **차량 입력이 하나도 없어도 화면만으로 4트랙을 완성**(자동 시험 있음) |
| 되는 것 | Vehicle SDK 입력: 브레이크(눌림 → 킥), 방향지시등(좌/우), 핸들 각도(좌/중/우 구간), **공조 4종(A/C·AUTO·내기순환·성에 제거)이 바뀔 때마다 대응 트랙 켜짐/꺼짐**. 지원 안 되는 공조 항목이 있으면 지원되는 것을 DRUM→BASS→MELODY→FX 순으로 배정 |
| 되는 것 | "내 차 장비" 화면: 항목별 연결 상태·맡은 역할·조작 시 줄 반짝임·공조 전원이 꺼져 있을 때 사유·기어 판정 방식 |
| 되는 것 | 기어 잠금: P가 아니면(SDK `Gear` + `CarUxRestrictions`) 약 30ms 안에 소리를 멈추고 잠금 화면. P로 돌아오면 내용 그대로, 재생은 정지 상태(▶로 다시 시작). 앱 전환(백그라운드)도 같은 경로 |
| 되는 것 | 방향지시등을 켜면 플랫폼이 카메라 창을 앱 **왼쪽 위**에 띄운다. 넓은 화면 배치는 그 자리를 비워 두어 조작이 가려지지 않는다 |
| 되는 것 | 3분 연속 재생(스텝 호출 오차 p95 < 1ms, 누락 0), 창 크기·밀도 변경 후에도 루프 유지, SDK 초기화 실패·콜백 예외에도 앱이 죽지 않음(그 항목만 비활성) |
| 되는 것 | 서명된 release APK(`./gradlew assembleRelease`)가 에뮬레이터에서 설치·실행되고 Virtual Console 브레이크·기어가 동작함 |
| 확인 안 됨(사람이 귀·눈으로) | 핸들 좌/우 방향(SDK 음수 = 왼쪽으로 구현), 모든 샘플의 소리, BASS/MELODY 연주감, 새 녹음 방식의 손맛, 줄 반짝임 모양, 잠금 화면 모양, 소리가 스피커로 나오기까지의 출력 지연, 오디오 포커스 이동(T0-12), 진짜 멀티 윈도우 분할 크기(T0-11) |
| 아직 안 됨 | 풍량→템포·온도→조·창문→이펙트·와이퍼→이펙트 단계(와이퍼는 꺼짐→켜짐마다 없음→약→강 순환으로 만들 예정), 드라이브 모드(에뮬레이터 미지원, 사운드팩은 화면 버튼으로만), 보관함(저장)·설정·조용한 연주 모드·첫 실행 안내 |
| 알려진 제약 | 좁은 창(< 1400dp)은 한 열 + 세로 스크롤이고 카메라 창을 피하지 않는다(좁은 창에서 카메라 창 크기 미측정) |

## 테스트 환경

- 개발 PC: macOS (Apple Silicon), Android Studio
- 에뮬레이터: Pleos Connect Emulator `Pleos_Connect_v2.1.0`, Android 14 (API 34), 2560×1440 @160dpi, Vehicle Service 2.3.22
- 빌드: AGP 9.5.1, Kotlin 2.4.0, Jetpack Compose, minSdk 28 / compileSdk 36 / targetSdk 34
- 사용 SDK: Pleos Vehicle SDK `ai.pleos.playground:Vehicle:2.3.2`
- 권한(읽기만, 제어 권한·`distractionOptimized` 없음): release 매니페스트는 `pleos.car.permission.CAR_DRIVE`(브레이크·핸들·기어), `CAR_LIGHTS`(방향지시등), `CAR_CLIMATE`(공조 4종·전원)만 선언한다. 디버그 매니페스트에만 `android.car.permission.CAR_POWERTRAIN`, `pleos.car.permission.CAR_WINDOWS`·`CAR_ENERGY`가 더 있다(측정용). 기어는 SDK `Gear`(CAR_DRIVE)로 읽는다.

## 사운드 출처

모든 샘플은 `tools/soundgen/generate.py`로 직접 합성했다(외부 샘플 없음). 자세한 내용은 `assets/soundpacks/LICENSES.md`.

## 문서

- 진행 상태: `docs/PROGRESS.md`
- 검증 결과·에뮬레이터 관찰: `docs/FINDINGS.md`
- 계획서와 달라진 점: `docs/PLAN_CHANGES.md`
