#!/usr/bin/env bash
# 기어 잠금 회귀 확인 (T0-7 / T1-11, FINDINGS V3):
#   루프 재생 중 gear D → 엔진 정지·오디오 출력 정지 → 입력 무시 → gear P → 트랙 데이터 그대로·재생은 정지 상태
# usage: gear_regression.sh <adb 시리얼> <출력 폴더>     각 확인을 PASS/FAIL 한 줄로 낸다. 하나라도 FAIL이면 종료 코드 1.
set -u
DEV="$1"; OUT="$2"; PKG="app.pjam.studio"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
A() { adb -s "$DEV" "$@"; }
FAILED=0
pass() { echo "PASS $1${2:+ ($2)}"; }
fail() { echo "FAIL $1 → $2"; FAILED=1; }
# 어떤 경우에도 기어를 P로 되돌린다. 안 그러면 이후 모든 실행이 플랫폼의 가림막에 막힌다.
trap 'bash "$ROOT/scripts/vc.sh" gear P >/dev/null 2>&1' EXIT

dump() { A exec-out uiautomator dump /dev/tty 2>/dev/null | sed 's/UI hierarchy dumped to.*//'; }
dump_retry() { local i; for i in 1 2 3 4 5; do dump >"$1"; grep -q "<hierarchy" "$1" && return 0; sleep 1; done; return 1; }
center() {
  python3 -I - "$1" "$2" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding="utf-8").read()
for m in re.finditer(r'<node [^>]*?content-desc="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
    if m.group(1) == sys.argv[2]:
        x1, y1, x2, y2 = map(int, m.groups()[1:]); print((x1 + x2) // 2, (y1 + y2) // 2); sys.exit(0)
sys.exit(1)
PY
}
cells() { grep -o 'content-desc="cell_[A-Z]*_[0-9]*_on"' "$1" | sort | tr '\n' ' '; }
standby() { A shell dumpsys media.audio_flinger 2>/dev/null | grep -A3 'name AudioOut_D' | grep -q 'Standby: yes'; }
# logcat 한 줄의 epoch 초 → ms
ms_of() { awk '{printf "%d", $1*1000}'; }

bash "$ROOT/scripts/vc.sh" gear P >/dev/null 2>&1
A shell am force-stop "$PKG"; A shell am start -W -n "$PKG/.MainActivity" >/dev/null; sleep 3
dump_retry "$OUT/gear-0-idle.xml" || { fail gear-setup "UI 덤프 실패"; exit 1; }
read -r RX RY < <(center "$OUT/gear-0-idle.xml" btn_record) || { fail gear-setup "btn_record 없음"; exit 1; }
read -r KX KY < <(center "$OUT/gear-0-idle.xml" pad_drum_kick) || { fail gear-setup "pad 없음"; exit 1; }
read -r SX SY < <(center "$OUT/gear-0-idle.xml" pad_drum_snare) || { fail gear-setup "pad 없음"; exit 1; }
read -r CX CY < <(center "$OUT/gear-0-idle.xml" btn_clear_all) || { fail gear-setup "btn 없음"; exit 1; }
read -r BX BY < <(center "$OUT/gear-0-idle.xml" dbg_brake) || { fail gear-setup "dbg_brake 없음(디버그 빌드 필요)"; exit 1; }
grep -q 'text="P단 (차량 SDK)"' "$OUT/gear-0-idle.xml" && pass gear-method-sdk "SDK Gear로 P 확인" || fail gear-method-sdk "기어 표시가 'P단 (차량 SDK)'가 아님"

# 루프 만들기: DRUM 킥·스네어를 녹음하고 녹음 버튼으로 끝낸다 → 재생이 계속된다
A shell input tap "$CX" "$CY"; sleep 0.4
A shell "input tap $RX $RY; sleep 2.9; input tap $KX $KY; sleep 0.9; input tap $SX $SY; sleep 0.8; input tap $RX $RY"
sleep 2
dump_retry "$OUT/gear-1-before.xml" || { fail gear-setup "재생 중 UI 덤프 실패"; exit 1; }
BEFORE="$(cells "$OUT/gear-1-before.xml")"
[ -n "$BEFORE" ] && pass gear-loop-recorded "$BEFORE" || { fail gear-loop-recorded "DRUM 칸이 안 켜짐"; exit 1; }
sleep 3
A logcat -c

# ---- D 전환 ----
OFF=$(python3 "$ROOT/scripts/vc_measure.py" offset 2>/dev/null || echo 0)   # 에뮬레이터 시계가 호스트보다 앞선 ms (지연 보정)
T=$(bash "$ROOT/scripts/vc.sh" gear-timed D | head -1)
PUT_MS=$(echo "$T" | sed 's/.*PUT_START_MS=\([0-9]*\).*/\1/')
sleep 1.5
A logcat -d -v epoch -s PJAM_LOCK:I PJAM_ENG:I PJAM_GEAR:I PJAM_LIFE:I >"$OUT/gear-2-d.log"
STOP_LINE=$(grep 'PJAM_ENG' "$OUT/gear-2-d.log" | grep ' STOP$' | head -1)
if [ -z "$STOP_LINE" ]; then fail gear-d-stops-engine "D 전환 뒤 엔진 STOP 로그 없음 ($OUT/gear-2-d.log)"; else
  STOP_MS=$(echo "$STOP_LINE" | ms_of)
  SRC=$(grep 'STOP reason' "$OUT/gear-2-d.log" | head -1 | sed 's/.*reason=\[\(.*\)\]/\1/')
  LAT=$((STOP_MS - PUT_MS - OFF))
  pass gear-d-stops-engine "D 전환 → 엔진 STOP ${LAT}ms (시계 오차 ${OFF}ms 보정), 신호: $SRC"
  [ $LAT -lt 1000 ] && pass gear-d-stop-within-1s || fail gear-d-stop-within-1s "${LAT}ms"
  # STOP 이후 루프가 더 돌지 않아야 한다
  sleep 5.5
  A logcat -d -v epoch -s PJAM_ENG:I >"$OUT/gear-3-after.log"
  LATE=$(grep 'LOOP wrap' "$OUT/gear-3-after.log" | awk -v s="$STOP_MS" '{ if ($1*1000 > s + 300) c++ } END { print c+0 }')
  [ "$LATE" -eq 0 ] && pass gear-d-no-more-steps || fail gear-d-no-more-steps "STOP 이후 LOOP wrap $LATE번"
fi
# 오디오 출력: 마지막 소리 뒤 3초 지나면 출력 스레드가 standby. 루프가 계속 돌고 있으면(노트 사이가 3초 넘게 비는 루프도 있다)
# standby가 잠깐 찍혔다가 다시 깨므로, standby가 된 뒤 루프 한 바퀴(4.8초)보다 길게 유지되는지 본다.
OK=0; for i in $(seq 1 10); do standby && { OK=1; break; }; sleep 1; done
if [ $OK -eq 1 ]; then
  for i in 1 2 3 4 5 6; do sleep 1; standby || OK=0; done
fi
[ $OK -eq 1 ] && pass gear-d-audio-standby "AudioOut standby 유지" || fail gear-d-audio-standby "D 전환 후 오디오 출력이 계속 standby가 아니거나 다시 깨어남"

# ---- D 에서 들어오는 차량 입력은 소리·포커스를 만들지 않는다 (플랫폼이 가려도 SDK 콜백은 계속 온다) ----
A logcat -c
bash "$ROOT/scripts/vc.sh" brake 100 >/dev/null 2>&1; sleep 0.8; bash "$ROOT/scripts/vc.sh" brake 0 >/dev/null 2>&1; sleep 0.5
A logcat -d -s PJAM_LOCK:I PJAM_FOCUS:I PJAM_ENG:I >"$OUT/gear-4-input.log"
grep -q 'input dropped' "$OUT/gear-4-input.log" && pass gear-d-input-dropped || fail gear-d-input-dropped "D 에서 브레이크 입력이 버려졌다는 로그 없음"
grep -q 'focus request' "$OUT/gear-4-input.log" && fail gear-d-no-focus-grab "D 에서 오디오 포커스를 요청함" || pass gear-d-no-focus-grab
sleep 3
standby && pass gear-d-silent-after-input || fail gear-d-silent-after-input "D 에서 브레이크 뒤 소리가 났음"

# ---- P 복귀 ----
A logcat -c
bash "$ROOT/scripts/vc.sh" gear P >/dev/null 2>&1
sleep 2
dump_retry "$OUT/gear-5-park.xml" || { fail gear-p-dump "P 복귀 후 UI 덤프 실패"; exit 1; }
AFTER="$(cells "$OUT/gear-5-park.xml")"
[ "$AFTER" = "$BEFORE" ] && pass gear-p-data-intact "$AFTER" || fail gear-p-data-intact "전: [$BEFORE] 후: [$AFTER]"
grep -q 'content-desc="lock_screen"' "$OUT/gear-5-park.xml" && fail gear-p-unlocked "P 인데 잠금 화면이 남음" || pass gear-p-unlocked
sleep 6
A logcat -d -v epoch -s PJAM_ENG:I PJAM_LOCK:I PJAM_INPUT:I PJAM_FOCUS:I PJAM_LIFE:I >"$OUT/gear-6-park-all.log"
A logcat -d -s PJAM_ENG:I >"$OUT/gear-6-park.log"
grep -q 'LOOP wrap' "$OUT/gear-6-park.log" && fail gear-p-not-autoplay "P 복귀 후 재생이 저절로 시작됨" || pass gear-p-not-autoplay "정지 상태 유지"
standby && pass gear-p-silent || fail gear-p-silent "P 복귀 후 소리가 남"

# ---- 백그라운드(onStop): 다른 앱으로 전환해도 같은 경로로 멈추고(T2-8), 돌아오면 데이터 그대로·정지 상태 ----
read -r PX PY < <(center "$OUT/gear-5-park.xml" btn_play) || { fail bg-setup "btn_play 없음"; exit 1; }
A shell input tap "$PX" "$PY"; sleep 6   # 저장된 루프 재생
A logcat -c
A shell am start -n com.android.deskclock/.DeskClock >/dev/null 2>&1; sleep 1.5
A logcat -d -v epoch -s PJAM_LOCK:I PJAM_ENG:I PJAM_LIFE:I >"$OUT/gear-7-bg.log"
grep -q 'STOP reason' "$OUT/gear-7-bg.log" && grep -q ' STOP$' "$OUT/gear-7-bg.log" \
  && pass bg-stops-engine "$(grep 'STOP reason' "$OUT/gear-7-bg.log" | head -1 | sed 's/.*reason=\[\(.*\)\]/\1/')" || fail bg-stops-engine "다른 앱으로 전환했는데 STOP 없음 ($OUT/gear-7-bg.log)"
sleep 5.5
A logcat -d -s PJAM_ENG:I | awk '/STOP$/{s=1;next} s && /LOOP wrap/{c++} END{exit c>0}' && pass bg-no-more-steps || fail bg-no-more-steps "전환 뒤 루프가 계속 돎"
OK=0; for i in $(seq 1 10); do standby && { OK=1; break; }; sleep 1; done
if [ $OK -eq 1 ]; then for i in 1 2 3 4 5 6; do sleep 1; standby || OK=0; done; fi
[ $OK -eq 1 ] && pass bg-audio-standby || fail bg-audio-standby "백그라운드에서 오디오 출력이 안 멈춤"
A logcat -c
A shell am start -W -n "$PKG/.MainActivity" >/dev/null 2>&1
sleep 2
dump_retry "$OUT/gear-8-back.xml" || { fail bg-return-dump "복귀 후 UI 덤프 실패"; exit 1; }
BACK="$(cells "$OUT/gear-8-back.xml")"
[ "$BACK" = "$BEFORE" ] && pass bg-return-data-intact || fail bg-return-data-intact "전: [$BEFORE] 후: [$BACK]"
sleep 6
A logcat -d -s PJAM_ENG:I | grep -q 'LOOP wrap' && fail bg-return-not-autoplay "복귀 후 재생이 저절로 시작됨" || pass bg-return-not-autoplay

# ---- 방향지시등: 플랫폼이 사각지대 카메라 창을 앱 위에 띄워도(onPause·포커스 손실) 앱은 백그라운드가 아니다 ----
A logcat -c
bash "$ROOT/scripts/vc.sh" turn BlinkLeft >/dev/null 2>&1; sleep 1.2
bash "$ROOT/scripts/vc.sh" turn FlashRight >/dev/null 2>&1; sleep 1.5
bash "$ROOT/scripts/vc.sh" turn Off >/dev/null 2>&1; sleep 1.5
A logcat -d -s PJAM_LOCK:I PJAM_INPUT:I PJAM_LIFE:I >"$OUT/gear-9-turn.log"
grep -q 'STOP reason' "$OUT/gear-9-turn.log" && fail turn-overlay-no-stop "방향지시등 때 사각지대 창이 떴다고 앱을 정지시킴 ($OUT/gear-9-turn.log)" || pass turn-overlay-no-stop
grep -q 'input dropped' "$OUT/gear-9-turn.log" && fail turn-overlay-input-kept "방향지시등 입력이 버려짐" || pass turn-overlay-input-kept
grep -q '방향지시등 우 → 하이햇' "$OUT/gear-9-turn.log" && pass turn-overlay-sound-path || fail turn-overlay-sound-path "방향지시등 우 → 하이햇 입력 표시 없음"

exit $FAILED
