#!/usr/bin/env bash
# P-JAM 검증 스크립트 (계획서 13.1). 기준 하나당 한 줄: PASS <이름> / FAIL <이름> → 로그 / SKIP <이름>
# 하나라도 FAIL이면 종료 코드 1.
set -u
cd "$(dirname "$0")/.."
ROOT="$PWD"
OUT="$ROOT/build/check-artifacts"
mkdir -p "$OUT"
PKG="app.pjam.studio"
ACT="$PKG/.MainActivity"
FAILED=0

: "${JAVA_HOME:=/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export JAVA_HOME

pass() { echo "PASS $1"; }
skip() { echo "SKIP $1 ($2)"; }
fail() { echo "FAIL $1 → $2"; FAILED=1; }

# ---------- 1. Gradle ----------
run_gradle() { # name task...
  local name="$1"; shift
  if ./gradlew "$@" --console=plain >"$OUT/$name.log" 2>&1; then pass "$name"; else fail "$name" "$OUT/$name.log"; fi
}
run_gradle lint+test+assemble lintDebug testDebugUnitTest assembleDebug

APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || { fail apk-exists "$APK"; exit 1; }

# ---------- 2. 사운드 에셋 ----------
if [ -f "$ROOT/tools/soundgen/verify.py" ]; then
  if python3 "$ROOT/tools/soundgen/verify.py" >"$OUT/soundgen-verify.log" 2>&1; then pass soundgen-verify; else fail soundgen-verify "$OUT/soundgen-verify.log"; fi
else
  skip soundgen-verify "tools/soundgen/verify.py 없음"
fi

# ---------- 3. 에뮬레이터: 설치 / 실행 / 스모크 ----------
DEV=$(adb devices | awk 'NR>1 && $2=="device"{print $1; exit}')
if [ -z "${DEV:-}" ]; then
  skip install "연결된 에뮬레이터 없음"; skip smoke "연결된 에뮬레이터 없음"; skip e2e-record-loop "연결된 에뮬레이터 없음"
  [ $FAILED -eq 0 ] && exit 0 || exit 1
fi
A() { adb -s "$DEV" "$@"; }

if A install -r -t "$APK" >"$OUT/install.log" 2>&1; then pass install; else fail install "$OUT/install.log"; fi

dump_ui() { A exec-out uiautomator dump /dev/tty 2>/dev/null | sed 's/UI hierarchy dumped to.*//' ; }

# 앱은 주차 전용이라 기어가 P가 아니면 플랫폼이 가린다. Virtual Console API로 P로 맞춘다(T0-0).
if bash "$ROOT/scripts/vc.sh" gear P 2>/dev/null | grep -q Park; then pass gear-park; else skip gear-park "Virtual Console 조작 실패. 기어가 P인지 확인"; fi

A shell am force-stop "$PKG" >/dev/null 2>&1
A logcat -c
if A shell am start -W -n "$ACT" >"$OUT/start.log" 2>&1 && ! grep -q -i "error" "$OUT/start.log"; then pass launch; else fail launch "$OUT/start.log"; fi
sleep 4

# 프로세스 생존
if [ -n "$(A shell pidof "$PKG" | tr -d '\r')" ]; then pass process-alive; else fail process-alive "프로세스 없음"; fi

# 화면 노드 (contentDescription 기준). 재생 중에는 idle 대기 때문에 덤프가 실패할 수 있어 재시도한다.
dump_with_retry() {
  local f="$1" i
  for i in 1 2 3; do
    dump_ui >"$f"
    grep -q "<hierarchy" "$f" && return 0
    sleep 1
  done
  return 1
}
has_node() { grep -q "content-desc=\"$1\"" "$2"; }

dump_with_retry "$OUT/ui-main.xml"
if has_node pjam_root "$OUT/ui-main.xml"; then pass smoke-root-node; else fail smoke-root-node "$OUT/ui-main.xml"; fi
# 스튜디오 화면 요소 (P6에서 추가됨; 아직 없으면 SKIP)
if has_node pjam_root "$OUT/ui-main.xml" && grep -q 'content-desc="studio_screen"' "$OUT/ui-main.xml"; then
  pass smoke-studio-screen
  for n in btn_play btn_record pad_drum_kick input_bar; do
    if has_node "$n" "$OUT/ui-main.xml"; then pass "node:$n"; else fail "node:$n" "$OUT/ui-main.xml"; fi
  done
else
  skip smoke-studio-screen "studio_screen 노드 없음(P6 전)"
fi

# 사운드 로드 로그 (P3)
if A logcat -d -s PJAM_SOUND:I 2>/dev/null | grep -q "loaded"; then
  if A logcat -d -s PJAM_SOUND:I | grep -E "loaded ([0-9]+)/\1( |$)" >/dev/null; then pass sound-load-all; else fail sound-load-all "$(A logcat -d -s PJAM_SOUND:I | tail -3 | tr '\n' ' ')"; fi
else
  skip sound-load-all "PJAM_SOUND 로그 없음(P3 전)"
fi

# ---------- 4. 엔드투엔드: 녹음 → 자동 반복 (P6) ----------
if [ -f "$ROOT/scripts/e2e_record_loop.sh" ] && grep -q 'content-desc="studio_screen"' "$OUT/ui-main.xml"; then
  if bash "$ROOT/scripts/e2e_record_loop.sh" "$DEV" "$OUT" >"$OUT/e2e-record-loop.log" 2>&1; then pass e2e-record-loop; else fail e2e-record-loop "$OUT/e2e-record-loop.log"; fi
else
  skip e2e-record-loop "스튜디오 화면 또는 e2e 스크립트 없음"
fi

# ---------- 5. 크래시 검사 ----------
A logcat -d >"$OUT/logcat.txt" 2>&1
if grep -E "FATAL EXCEPTION" -A3 "$OUT/logcat.txt" | grep -q "$PKG"; then fail no-fatal-exception "$OUT/logcat.txt"; else pass no-fatal-exception; fi
if A logcat -d -b crash 2>/dev/null | grep -q "$PKG"; then fail no-crash-buffer "adb logcat -b crash"; else pass no-crash-buffer; fi

[ $FAILED -eq 0 ] && exit 0 || exit 1
