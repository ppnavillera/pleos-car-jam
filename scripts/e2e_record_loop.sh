#!/usr/bin/env bash
# 엔드투엔드: 화면 패드로 DRUM 녹음 → 루프가 도는 동안 계속 쌓기 → 녹음 버튼으로 종료 → 자동 반복 확인.
# 녹음은 버튼을 다시 누를 때까지 이어진다 (PLAN_CHANGES 2026-11-15). usage: e2e_record_loop.sh <adb 시리얼> <출력 폴더>
# 100 BPM 기준: 카운트인 2.4s → 녹음 시작(T0+2.4s), 루프 한 바퀴 4.8s
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

# 녹음 시작(T0) → 카운트인(2.4s) 뒤 1바퀴째(2.4~7.2s)에 킥·스네어·하이햇, 2바퀴째(7.2~12s)에 클랩을 쌓고 녹음 버튼으로 종료
A shell "input tap $RX $RY; sleep 2.9; input tap $KX $KY; sleep 0.9; input tap $SX $SY; sleep 0.9; input tap $HX $HY"
# 지금 T0 ≈ 5.5s(+탭 지연). 2바퀴째에서 클랩
read -r LX LY < <(center "$OUT/e2e-0-idle.xml" pad_drum_clap) || { echo "pad_drum_clap 없음"; exit 1; }
sleep 3.2
A shell input tap "$LX" "$LY"
sleep 1.5
# 녹음이 자동으로 끝나지 않고 2바퀴째에도 이어지는지: 종료 전에는 REC end 가 없어야 한다
A logcat -d -s PJAM_ENG:I >"$OUT/e2e-engine-mid.log"
grep -q "REC end DRUM" "$OUT/e2e-engine-mid.log" && { echo "녹음 버튼을 누르기 전에 녹음이 끝났다(2마디 자동 종료가 남아 있음)"; exit 1; }
A shell input tap "$RX" "$RY"   # 녹음 종료
sleep 5.5                        # 한 바퀴 이상 자동 재생
A logcat -d -s PJAM_ENG:I >"$OUT/e2e-engine.log"

grep -q "REC start DRUM" "$OUT/e2e-engine.log" || { echo "녹음 시작 로그 없음"; exit 1; }
NOTES=$(grep -o "REC end DRUM notes=[0-9]*" "$OUT/e2e-engine.log" | head -1 | sed 's/.*=//')
# 킥·스네어·하이햇(1바퀴째) + 클랩(2바퀴째 쌓기) = 4. 앞 바퀴 노트가 지워지지 않았다는 뜻
[ -n "${NOTES:-}" ] && [ "$NOTES" -ge 4 ] || { echo "녹음 끝 로그 없음 또는 노트 <4 (notes=${NOTES:-none})"; exit 1; }
# 녹음 중 루프 wrap(절대 스텝 ≥48)이 1번 이상 있었고, 끝난 뒤에도 재생이 계속돼 wrap 이 더 있었는지
WRAPS=$(grep -o "LOOP wrap n=[0-9]*" "$OUT/e2e-engine.log" | sed 's/.*=//' | awk '$1>=48' | wc -l | tr -d ' ')
[ "$WRAPS" -ge 2 ] || { echo "루프 반복 wrap 부족: $WRAPS"; exit 1; }

# ---- P8: DRUM 이 반복되는 중에 BASS 를 녹음해도 DRUM 이 유지되는지 ----
read -r BX BY < <(center "$OUT/e2e-0-idle.xml" track_card_BASS) || { echo "track_card_BASS 없음"; exit 1; }
A shell input tap "$BX" "$BY"; sleep 0.8
# BASS 를 고르면 패드 줄 위에 핸들 대체 3버튼이 생겨 패드 위치가 바뀐다 → 다시 덤프해서 BASS 패드 좌표를 읽는다
dump_retry "$OUT/e2e-bass.xml" || { echo "BASS 선택 후 UI 덤프 실패"; exit 1; }
read -r P1X KY < <(center "$OUT/e2e-bass.xml" pad_bass_1_o2) || { echo "pad_bass_1_o2 없음"; exit 1; }
read -r P2X _ < <(center "$OUT/e2e-bass.xml" pad_bass_4_o2) || { echo "pad_bass_4_o2 없음"; exit 1; }
read -r P3X _ < <(center "$OUT/e2e-bass.xml" pad_bass_5_o2) || { echo "pad_bass_5_o2 없음"; exit 1; }
A logcat -c
A shell input tap "$RX" "$RY"
# 녹음이 실제로 시작될 때까지(최대 25초) 기다린다. 다음 루프 시작점 + 1마디 메트로놈
for i in $(seq 1 80); do A logcat -d -s PJAM_ENG:I | grep -q "REC start BASS" && break; sleep 0.3; done
A logcat -d -s PJAM_ENG:I | grep -q "REC start BASS" || { echo "BASS 녹음이 시작되지 않음"; exit 1; }
A shell "input tap $P1X $KY; sleep 0.8; input tap $P2X $KY; sleep 0.8; input tap $P3X $KY"
sleep 0.5
A shell input tap "$RX" "$RY"   # BASS 녹음 종료 (자동으로 끝나지 않는다)
for i in $(seq 1 30); do A logcat -d -s PJAM_ENG:I | grep -q "REC end BASS" && break; sleep 0.3; done
A logcat -d -s PJAM_ENG:I >"$OUT/e2e-engine2.log"
BNOTES=$(grep -o "REC end BASS notes=[0-9]*" "$OUT/e2e-engine2.log" | head -1 | sed 's/.*=//')
[ -n "${BNOTES:-}" ] && [ "$BNOTES" -ge 2 ] || { echo "BASS 녹음 끝 로그 없음 또는 노트 <2 (notes=${BNOTES:-none})"; exit 1; }
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
