#!/usr/bin/env bash
# T2-10 / 기획서 7.1 1번·3번: 서명된 release APK 를 에뮬레이터에 설치해 실행하고, Virtual Console 브레이크가 소리(입력 표시줄)가 되는지 확인한다.
# debug 와 release 는 applicationId 가 같고 서명이 달라 서로 덮어쓸 수 없다 → 설치 전에 지우고, 끝나면 호출한 쪽이 debug 를 다시 설치한다.
# usage: release_smoke.sh <adb 시리얼> <출력 폴더>      기준 하나당 한 줄 PASS/FAIL, 하나라도 FAIL 이면 종료 코드 1
set -u
DEV="$1"; OUT="$2"; PKG="app.pjam.studio"
HERE="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(cd "$HERE/.." && pwd)"
APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
UI="python3 -I $HERE/ui_tools.py"
A() { adb -s "$DEV" "$@"; }
FAILED=0
pass() { echo "PASS $1"; }
fail() { echo "FAIL $1 → $2"; FAILED=1; }
dump_retry() { local i; for i in 1 2 3 4 5; do A exec-out uiautomator dump /dev/tty 2>/dev/null | sed 's/UI hierarchy dumped to.*//' >"$1"; grep -q "<hierarchy" "$1" && return 0; sleep 1; done; return 1; }

[ -f "$APK" ] || { fail release-apk-exists "$APK (./gradlew assembleRelease 필요)"; exit 1; }
AS=$(ls "$HOME"/Library/Android/sdk/build-tools/*/apksigner 2>/dev/null | tail -1)
if [ -n "$AS" ] && "$AS" verify "$APK" >"$OUT/release-apksigner.log" 2>&1; then pass release-apk-signed; else fail release-apk-signed "$OUT/release-apksigner.log"; fi
# debuggable 이 아니어야 한다 (aapt2 dump badging)
AAPT=$(ls "$HOME"/Library/Android/sdk/build-tools/*/aapt2 2>/dev/null | tail -1)
if [ -n "$AAPT" ]; then
  "$AAPT" dump badging "$APK" >"$OUT/release-badging.txt" 2>/dev/null
  if grep -q "application-debuggable" "$OUT/release-badging.txt"; then fail release-not-debuggable "application-debuggable 이 있음"; else pass release-not-debuggable; fi
  # 릴리스에 선언된 권한: 쓰는 도메인의 읽기 권한만 (CAR_POWERTRAIN·제어 권한 없음)
  PERMS=$(grep -o "uses-permission: name='[^']*'" "$OUT/release-badging.txt" | sed "s/.*name='//;s/'//" | sort | tr '\n' ' ')
  echo "INFO release-permissions: $PERMS"
  if echo "$PERMS" | grep -q -E "CONTROL_CAR|CAR_POWERTRAIN"; then fail release-permissions "제어 권한 또는 CAR_POWERTRAIN 이 선언됨: $PERMS"; else pass release-permissions; fi
  if grep -q "distractionOptimized" "$OUT/release-badging.txt"; then fail release-no-distraction-optimized "distractionOptimized 선언이 있음"; else pass release-no-distraction-optimized; fi
fi

bash "$HERE/vc.sh" gear P >/dev/null 2>&1
A uninstall "$PKG" >/dev/null 2>&1
if A install -r "$APK" >"$OUT/release-install.log" 2>&1 && grep -q Success "$OUT/release-install.log"; then pass release-install; else fail release-install "$OUT/release-install.log"; echo "FAIL release-smoke → 설치 실패라 이후 생략"; exit 1; fi
A logcat -c
A shell am start -W -n "$PKG/.MainActivity" >"$OUT/release-start.log" 2>&1; sleep 5
if [ -n "$(A shell pidof "$PKG" | tr -d '\r')" ]; then pass release-process-alive; else fail release-process-alive "프로세스 없음"; fi
dump_retry "$OUT/release-ui.xml" || fail release-ui-dump "$OUT/release-ui.xml"
if $UI has "$OUT/release-ui.xml" studio_screen; then pass release-studio-screen; else fail release-studio-screen "$OUT/release-ui.xml"; fi
# 릴리스는 가짜 입력 패널이 없다
if $UI has "$OUT/release-ui.xml" debug_panel; then fail release-no-debug-panel "debug_panel 노드가 있음"; else pass release-no-debug-panel; fi
# SDK 연결(릴리스에서도 capability 응답, R8 미사용이라 SDK 클래스 유지 문제 없음)
if A logcat -d -s PJAM_PLEOS:I | grep -q "capability BRAKE accessType=READ"; then pass release-sdk-brake-capability; else fail release-sdk-brake-capability "PJAM_PLEOS 로그 없음"; fi
# Virtual Console 브레이크 → 입력 표시줄 (7.1 3번)
bash "$HERE/vc.sh" brake 100 >/dev/null 2>&1; sleep 1
dump_retry "$OUT/release-brake.xml"
if grep -q 'text="브레이크 → 킥"' "$OUT/release-brake.xml"; then pass release-vc-brake-input-bar; else fail release-vc-brake-input-bar "$OUT/release-brake.xml"; fi
bash "$HERE/vc.sh" brake 0 >/dev/null 2>&1
# 기어 D → 잠금 화면, P → 복귀 (7.1 2번, release 에서도)
bash "$HERE/vc.sh" gear D >/dev/null 2>&1; sleep 1.5
if A logcat -d -s PJAM_LOCK:I | grep -q "STOP reason"; then pass release-gear-d-stops; else fail release-gear-d-stops "PJAM_LOCK STOP 로그 없음"; fi
bash "$HERE/vc.sh" gear P >/dev/null 2>&1; sleep 1.5
dump_retry "$OUT/release-back.xml"
if $UI has "$OUT/release-back.xml" studio_screen && ! $UI has "$OUT/release-back.xml" lock_screen; then pass release-gear-p-restores; else fail release-gear-p-restores "$OUT/release-back.xml"; fi
A logcat -d >"$OUT/release-logcat.txt" 2>&1
if grep -E "FATAL EXCEPTION" -A3 "$OUT/release-logcat.txt" | grep -q "$PKG"; then fail release-no-fatal "$OUT/release-logcat.txt"; else pass release-no-fatal; fi
exit $FAILED
