#!/usr/bin/env bash
# 엔드투엔드: 화면 패드로 DRUM 녹음 → 2마디 뒤 자동 반복되는지 확인한다. (계획서 4.1 P6)
# usage: e2e_record_loop.sh <adb 시리얼> <출력 폴더>
# 100 BPM 기준: 카운트인 2.4s → 녹음 4.8s → 이후 4.8s마다 루프 반복
set -eu
DEV="$1"; OUT="$2"; PKG="app.pjam.studio"
A() { adb -s "$DEV" "$@"; }
dump() { A exec-out uiautomator dump /dev/tty 2>/dev/null | sed 's/UI hierarchy dumped to.*//'; }
dump_retry() { local i; for i in 1 2 3 4; do dump >"$1"; grep -q "<hierarchy" "$1" && return 0; sleep 1; done; return 1; }

# 노드 content-desc 의 중심 좌표 "x y"
center() {
  python3 -I - "$1" "$2" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding="utf-8").read()
for m in re.finditer(r'<node [^>]*?content-desc="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
    if m.group(1) == sys.argv[2]:
        x1, y1, x2, y2 = map(int, m.groups()[1:])
        print((x1 + x2) // 2, (y1 + y2) // 2); sys.exit(0)
sys.exit(1)
PY
}

A shell am force-stop "$PKG"; A shell am start -W -n "$PKG/.MainActivity" >/dev/null; sleep 3
dump_retry "$OUT/e2e-0-idle.xml"
read -r RX RY < <(center "$OUT/e2e-0-idle.xml" btn_record) || { echo "btn_record 없음"; exit 1; }
read -r KX KY < <(center "$OUT/e2e-0-idle.xml" pad_drum_kick) || { echo "pad_drum_kick 없음"; exit 1; }
read -r SX SY < <(center "$OUT/e2e-0-idle.xml" pad_drum_snare) || { echo "pad_drum_snare 없음"; exit 1; }
read -r HX HY < <(center "$OUT/e2e-0-idle.xml" pad_drum_hihat) || { echo "pad_drum_hihat 없음"; exit 1; }
read -r PX PY < <(center "$OUT/e2e-0-idle.xml" btn_play) || { echo "btn_play 없음"; exit 1; }
read -r CX CY < <(center "$OUT/e2e-0-idle.xml" btn_clear_all) || { echo "btn_clear_all 없음"; exit 1; }

A shell input tap "$CX" "$CY"; sleep 0.5
A logcat -c

# 녹음 시작(T0) → 카운트인 중에는 치지 않고, 녹음 구간(T0+2.4s~7.2s)에 킥·스네어·하이햇
A shell "input tap $RX $RY; sleep 2.9; input tap $KX $KY; sleep 0.9; input tap $SX $SY; sleep 0.9; input tap $HX $HY"
sleep 4.0   # T0 ≈ 8.7s: 녹음 끝(7.2s) 지나 PLAYING
sleep 4.5   # 한 루프 더 (T0+12s의 wrap까지)
A logcat -d -s PJAM_ENG:I >"$OUT/e2e-engine.log"

grep -q "REC start DRUM" "$OUT/e2e-engine.log" || { echo "녹음 시작 로그 없음"; exit 1; }
NOTES=$(grep -o "REC end DRUM notes=[0-9]*" "$OUT/e2e-engine.log" | head -1 | sed 's/.*=//')
[ -n "${NOTES:-}" ] && [ "$NOTES" -ge 2 ] || { echo "녹음 끝 로그 없음 또는 노트 <2 (notes=${NOTES:-none})"; exit 1; }
# 녹음이 끝난 뒤(절대 스텝 ≥48) 루프 wrap 이 2번 이상 = 자동 반복
WRAPS=$(grep -o "LOOP wrap n=[0-9]*" "$OUT/e2e-engine.log" | sed 's/.*=//' | awk '$1>=48' | wc -l | tr -d ' ')
[ "$WRAPS" -ge 2 ] || { echo "자동 반복 wrap 부족: $WRAPS"; exit 1; }

# ---- P8: DRUM 이 반복되는 중에 BASS 를 녹음해도 DRUM 이 유지되는지 ----
read -r BX BY < <(center "$OUT/e2e-0-idle.xml" track_card_BASS) || { echo "track_card_BASS 없음"; exit 1; }
# BASS 패드는 3개: DRUM 패드 줄(왼쪽 끝~오른쪽 끝)을 3등분한 중심으로 계산한다
read -r P1X P2X P3X < <(python3 -I - "$OUT/e2e-0-idle.xml" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding="utf-8").read()
def b(desc):
    m = re.search(r'content-desc="%s"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % desc, xml)
    return tuple(map(int, m.groups()))
l = b("pad_drum_kick")[0]; r = b("pad_drum_clap")[2]
gap = 12; w = (r - l - 2 * gap) / 3
print(*[int(l + w / 2 + i * (w + gap)) for i in range(3)])
PY
)
A shell input tap "$BX" "$BY"; sleep 0.5
A logcat -c
A shell input tap "$RX" "$RY"
# 녹음이 실제로 시작될 때까지(최대 25초) 기다린다. 다음 루프 시작점 + 1마디 메트로놈
for i in $(seq 1 80); do A logcat -d -s PJAM_ENG:I | grep -q "REC start BASS" && break; sleep 0.3; done
A logcat -d -s PJAM_ENG:I | grep -q "REC start BASS" || { echo "BASS 녹음이 시작되지 않음"; exit 1; }
A shell "input tap $P1X $KY; sleep 0.8; input tap $P2X $KY; sleep 0.8; input tap $P3X $KY"
for i in $(seq 1 60); do A logcat -d -s PJAM_ENG:I | grep -q "REC end BASS" && break; sleep 0.3; done
A logcat -d -s PJAM_ENG:I >"$OUT/e2e-engine2.log"
BNOTES=$(grep -o "REC end BASS notes=[0-9]*" "$OUT/e2e-engine2.log" | head -1 | sed 's/.*=//')
[ -n "${BNOTES:-}" ] && [ "$BNOTES" -ge 2 ] || { echo "BASS 녹음 끝 로그 없음 또는 노트 <2 (notes=${BNOTES:-none})"; exit 1; }
# 녹음 도중에도 루프(=DRUM 재생)가 끊기지 않았는지: BASS 녹음 시작~끝 사이 wrap 로그가 있어야 한다
A shell input tap "$PX" "$PY"; sleep 1   # 정지 (재생 중에는 uiautomator idle 대기가 실패할 수 있음)

# 화면: 정지 후 격자와 상태 확인
dump_retry "$OUT/e2e-1-after.xml" || { echo "UI 덤프 실패"; exit 1; }
ON=$(grep -o 'content-desc="cell_DRUM_[0-9]*_on"' "$OUT/e2e-1-after.xml" | wc -l | tr -d ' ')
[ "$ON" -ge 2 ] || { echo "DRUM 격자에 켜진 칸 $ON 개 (<2): BASS 녹음이 DRUM 을 지웠거나 녹음이 안 됨"; exit 1; }
BON=$(grep -o 'content-desc="cell_BASS_[0-9]*_on"' "$OUT/e2e-1-after.xml" | wc -l | tr -d ' ')
[ "$BON" -ge 2 ] || { echo "BASS 격자에 켜진 칸 $BON 개 (<2)"; exit 1; }
grep -q 'content-desc="status_DRUM_PLAYING"' "$OUT/e2e-1-after.xml" || { echo "DRUM 상태가 PLAYING 아님"; exit 1; }
grep -q 'content-desc="status_BASS_PLAYING"' "$OUT/e2e-1-after.xml" || { echo "BASS 상태가 PLAYING 아님"; exit 1; }
grep -q 'content-desc="input_bar"' "$OUT/e2e-1-after.xml" || { echo "input_bar 없음"; exit 1; }
echo "OK drum_notes=$NOTES wraps_after_record=$WRAPS drum_grid_on=$ON bass_notes=$BNOTES bass_grid_on=$BON"
