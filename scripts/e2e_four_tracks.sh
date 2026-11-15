#!/usr/bin/env bash
# 엔드투엔드(T2-3, T2-8, 기획서 7.1 5번·7번): 차량 입력이 하나도 없는 상태(디버그 플래그 files/screen_only)에서
# 화면 패드·버튼만으로 DRUM → BASS → MELODY → FX 를 차례로 쌓아 4트랙 루프를 만든다.
# 끝나면 창 크기를 바꿔도(wm size) 루프 데이터가 그대로인지 확인한다.
# usage: e2e_four_tracks.sh <adb 시리얼> <출력 폴더>      (디버그 빌드가 설치돼 있어야 한다)
set -u
DEV="$1"; OUT="$2"; PKG="app.pjam.studio"
HERE="$(cd "$(dirname "$0")" && pwd)"
UI="python3 -I $HERE/ui_tools.py"
A() { adb -s "$DEV" "$@"; }
USR=$(adb -s "$DEV" shell am get-current-user | tr -d "\r")   # AAOS 는 앱이 사용자 10 으로 돈다. run-as 에 같은 사용자를 줘야 앱의 files 에 닿는다
dump() { A exec-out uiautomator dump /dev/tty 2>/dev/null | sed 's/UI hierarchy dumped to.*//'; }
dump_retry() { local i; for i in 1 2 3 4 5; do dump >"$1"; grep -q "<hierarchy" "$1" && return 0; sleep 1; done; return 1; }
tap() { # <xml> <desc>
  local xy; xy=$($UI center "$1" "$2") || { echo "노드 없음: $2"; return 1; }
  A shell input tap $xy
}
wait_log() { # <tag> <정규식> <초>
  local i; for i in $(seq 1 $(( $3 * 4 ))); do A logcat -d -s "$1:I" | grep -qE "$2" && return 0; sleep 0.25; done; return 1
}
fail() { echo "FAIL: $*"; exit 1; }
cleanup() {
  A shell wm size reset >/dev/null 2>&1
  A shell "run-as $PKG --user $USR rm -f files/screen_only" >/dev/null 2>&1
}
trap cleanup EXIT

bash "$HERE/vc.sh" gear P >/dev/null 2>&1
A shell am force-stop "$PKG"
A shell "run-as $PKG --user $USR mkdir -p files" >/dev/null 2>&1
A shell "run-as $PKG --user $USR touch files/screen_only" || fail "run-as 실패(디버그 빌드가 아님?)"
A logcat -c
A shell am start -W -n "$PKG/.MainActivity" >/dev/null; sleep 4
dump_retry "$OUT/e4-0.xml" || fail "UI 덤프 실패"
# 차량 신호가 없다는 안내가 화면에 있어야 한다
$UI has "$OUT/e4-0.xml" screen_only_notice || fail "화면에 차량 신호 없음 안내(screen_only_notice)가 없다(화면 조작만 상태가 아님)"
A logcat -d -s PJAM_PLEOS:W | grep -q "vehicle input unavailable" || fail "차량 입력 비활성 로그 없음"
tap "$OUT/e4-0.xml" btn_clear_all; sleep 0.5

record_track() { # <TRACK> <pad1> <pad2> [zone버튼]
  local K="$1" P1="$2" P2="$3" Z="${4:-}" f="$OUT/e4-$1.xml"
  tap "$OUT/e4-0.xml" "track_card_$K" || tap "$OUT/e4-0.xml" "track_card_${K}_selected" || fail "$K 카드 선택 실패"
  sleep 0.8
  dump_retry "$f" || fail "$K 덤프 실패"
  $UI has "$f" "track_card_${K}_selected" || fail "$K 카드가 선택되지 않음"
  A logcat -c
  tap "$f" btn_record || fail "녹음 버튼 없음"
  wait_log PJAM_ENG "REC start $K" 25 || fail "$K 녹음이 시작되지 않음"
  if [ -n "$Z" ]; then tap "$f" "$Z" || fail "$Z 없음"; sleep 0.4; fi
  tap "$f" "$P1" || fail "$P1 없음"; sleep 1.1
  tap "$f" "$P2" || fail "$P2 없음"; sleep 1.1
  tap "$f" btn_record || fail "녹음 종료 버튼 없음"
  wait_log PJAM_ENG "REC end $K notes=[2-9]" 8 || fail "$K 녹음 종료 로그 없음 또는 노트 <2: $(A logcat -d -s PJAM_ENG:I | grep 'REC end' | tail -1)"
  echo "ok $K $(A logcat -d -s PJAM_ENG:I | grep -o "REC end $K notes=[0-9]*" | tail -1)"
}

record_track DRUM pad_drum_kick pad_drum_snare
record_track BASS pad_bass_1_o2 pad_bass_5_o2 zone_btn_RIGHT
A logcat -d -s PJAM_INPUT:I | grep -q "화면 버튼 → 5도" || fail "핸들 대체 버튼(BASS 우=5도)이 입력 표시줄에 안 뜸"
record_track MELODY pad_mel_0 pad_mel_4 zone_btn_LEFT
A logcat -d -s PJAM_INPUT:I | grep -q "화면 버튼 → 낮은 음역" || fail "핸들 대체 버튼(MELODY 좌=낮은 음역)이 입력 표시줄에 안 뜸"
record_track FX pad_fx_impact pad_fx_riser

# 재생 중인 4트랙이 모두 돌고 있었는지: 마지막 FX 녹음이 끝난 뒤 한 바퀴 더 기다렸다가 정지
sleep 5.5
A logcat -d -s PJAM_ENG:I >"$OUT/e4-engine.log"
tap "$OUT/e4-DRUM.xml" btn_play; sleep 1.5
dump_retry "$OUT/e4-1.xml" || fail "정지 후 덤프 실패"
SUMMARY=""
for K in DRUM BASS MELODY FX; do
  N=$($UI count "$OUT/e4-1.xml" "cell_${K}_" )
  ON=$(grep -o "content-desc=\"cell_${K}_[0-9]*_on\"" "$OUT/e4-1.xml" | wc -l | tr -d ' ')
  [ "$ON" -ge 2 ] || fail "$K 격자에 켜진 칸 $ON (<2) — 쌓는 동안 지워졌거나 녹음이 안 됨"
  $UI has "$OUT/e4-1.xml" "status_${K}_PLAYING" || fail "$K 상태가 PLAYING 아님"
  SUMMARY="$SUMMARY $K=$ON"
done
echo "ok 4트랙 완성(켜진 칸:$SUMMARY )"

# ---- T2-8: 창 크기 변경 후에도 루프 유지 ----
A logcat -c
A shell wm size 1600x900 >/dev/null; sleep 3
dump_retry "$OUT/e4-resized.xml" || fail "창 크기 변경 후 덤프 실패"
[ -n "$(A shell pidof "$PKG" | tr -d '\r')" ] || fail "창 크기 변경 후 프로세스가 죽음"
$UI has "$OUT/e4-resized.xml" studio_screen || fail "창 크기 변경 후 스튜디오 화면 없음"
$UI has "$OUT/e4-resized.xml" "status_DRUM_PLAYING" || fail "창 크기 변경 후 DRUM 데이터 없음"
A exec-out screencap -p >"$OUT/e4-resized.png"
A shell wm size reset >/dev/null; sleep 3
dump_retry "$OUT/e4-restored.xml" || fail "복원 후 덤프 실패"
for K in DRUM BASS MELODY FX; do
  ON=$(grep -o "content-desc=\"cell_${K}_[0-9]*_on\"" "$OUT/e4-restored.xml" | wc -l | tr -d ' ')
  [ "$ON" -ge 2 ] || fail "창 크기 복원 후 $K 칸 $ON (<2)"
done
A logcat -d -s PJAM_LIFE:I | grep -q onStop && fail "창 크기 변경 중 onStop 이 왔다(백그라운드로 처리됨)"
echo "ok 창 크기 1600x900 → 원래 크기, 4트랙 데이터 유지"
echo "OK four_tracks screen_only$SUMMARY resize_keeps_loop"
