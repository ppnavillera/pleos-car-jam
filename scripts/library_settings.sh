#!/usr/bin/env bash
# T3-2 곡 저장·보관함, T3-3 조용한 연주 모드·설정 (디버그 빌드, Virtual Console 사용).
# 예시 곡 불러오기 → 저장 → 앱을 껐다 켜도 남음 → 손상 파일 무시 → 삭제, 설정 유지, 조용한 연주 모드가 방향지시등만 막는지.
# usage: library_settings.sh <adb 시리얼> <출력 폴더>     기준 하나당 한 줄 PASS/FAIL, 하나라도 FAIL이면 종료 코드 1
set -u
DEV="$1"; OUT="$2"; PKG="app.pjam.studio"
HERE="$(cd "$(dirname "$0")" && pwd)"
UI="python3 -I $HERE/ui_tools.py"
A() { adb -s "$DEV" "$@"; }
VC() { bash "$HERE/vc.sh" "$@"; }
FAILED=0
pass() { echo "PASS $1${2:+ ($2)}"; }
fail() { echo "FAIL $1 → $2"; FAILED=1; }
USR=$(A shell am get-current-user | tr -d "\r")
RUNAS() { A shell "run-as $PKG --user $USR sh -c '$1'"; }
dump_retry() { local i; for i in 1 2 3 4 5; do A exec-out uiautomator dump /dev/tty 2>/dev/null | sed 's/UI hierarchy dumped to.*//' >"$1"; grep -q "<hierarchy" "$1" && return 0; sleep 1; done; return 1; }
tap() { # <xml> <content-desc>
  local x y; read -r x y < <($UI center "$1" "$2") || return 1
  A shell input tap "$x" "$y"; sleep 1.2
}
screen() { dump_retry "$OUT/ls-$1.xml" || { fail "ui-dump-$1" "덤프 실패"; return 1; }; }
log_has() { A logcat -d -s "$1:I" | grep -qE "$2"; }
restart() { A shell am force-stop "$PKG"; A logcat -c; A shell am start -W -n "$PKG/.MainActivity" >/dev/null 2>&1; sleep 4; }

VC gear P >/dev/null 2>&1
A shell am force-stop "$PKG"
RUNAS "rm -rf files/songs files/settings.json; mkdir -p files/songs"
RUNAS 'echo "{ broken" > files/songs/garbage.json'          # 손상 파일은 무시되어야 한다
restart
screen 0 || exit 1

# ---- 보관함: 예시 곡은 첫 실행 여부와 무관하게 항상 있다 ----
tap "$OUT/ls-0.xml" btn_library || { fail library-button "btn_library 없음"; exit 1; }
screen 1
$UI has "$OUT/ls-1.xml" library_screen && pass library-screen || fail library-screen "library_screen 없음"
$UI has "$OUT/ls-1.xml" song_card_example-full && pass library-bundled-example-card || fail library-bundled-example-card "예시 곡 카드 없음"
grep -q "예시" "$OUT/ls-1.xml" && pass library-example-labelled || fail library-example-labelled "'예시' 표시 없음"
grep -q "4트랙\|DRUM● BASS● MELODY● FX●" "$OUT/ls-1.xml" && pass library-example-all-tracks-filled || fail library-example-all-tracks-filled "4트랙이 채워진 표시 없음"
grep -q "브레이크 6" "$OUT/ls-1.xml" && pass library-input-summary || fail library-input-summary "입력 요약(브레이크 6) 없음"
$UI has "$OUT/ls-1.xml" btn_delete_example-full && fail library-example-not-deletable "예시 곡에 삭제 버튼이 있음" || pass library-example-not-deletable
log_has PJAM_DATA "ignored unreadable song file garbage.json" && pass library-corrupt-file-ignored || { A logcat -d -s PJAM_DATA:W | grep -q garbage && pass library-corrupt-file-ignored || fail library-corrupt-file-ignored "손상 파일 무시 로그 없음"; }

# ---- 예시 곡 불러오기 → 4트랙이 채워진 채 바로 재생 ----
A logcat -c
tap "$OUT/ls-1.xml" btn_load_example-full
sleep 2
screen 2
$UI has "$OUT/ls-2.xml" studio_screen && pass load-returns-to-studio || fail load-returns-to-studio "스튜디오로 돌아오지 않음"
for t in DRUM BASS MELODY FX; do
  n=$(grep -o "content-desc=\"cell_${t}_[0-9]*_on\"" "$OUT/ls-2.xml" | wc -l | tr -d ' ')
  [ "$n" -gt 0 ] && pass "load-example-$t-filled" "${n}칸" || fail "load-example-$t-filled" "$t 칸이 비어 있음"
done
[ "$($UI text "$OUT/ls-2.xml" status_text)" = "재생 중" ] && pass load-example-plays || fail load-example-plays "상태: $($UI text "$OUT/ls-2.xml" status_text)"
A logcat -d -s PJAM_ENG:I | grep -q "LOOP wrap" && pass load-example-loops || fail load-example-loops "루프가 돌지 않음"

# ---- 저장 → 앱을 껐다 켜도 남음 ----
A logcat -c
tap "$OUT/ls-2.xml" btn_save
sleep 1
SAVED=$(A logcat -d -s PJAM_DATA:I | grep -o "saved [0-9a-f-]* name=.*" | head -1)
[ -n "$SAVED" ] && pass save-logged "$SAVED" || fail save-logged "saved 로그 없음"
SID=$(echo "$SAVED" | awk '{print $2}')
echo "$SAVED" | grep -qE "name=[0-9]+월 [0-9]+일 세션 1$" && pass save-auto-name || fail save-auto-name "자동 이름 형식이 아님: $SAVED"
restart
screen 3 || exit 1
tap "$OUT/ls-3.xml" btn_library; screen 4
$UI has "$OUT/ls-4.xml" "song_card_$SID" && pass save-survives-restart || fail save-survives-restart "재시작 뒤 저장한 곡이 없음"
$UI has "$OUT/ls-4.xml" "btn_delete_$SID" && pass saved-song-deletable || fail saved-song-deletable "삭제 버튼 없음"
# 두 번째 저장은 '세션 2'
tap "$OUT/ls-4.xml" btn_page_back; screen 5
tap "$OUT/ls-5.xml" btn_library; screen 6
tap "$OUT/ls-6.xml" btn_load_example-full; sleep 1.5
screen 7; A logcat -c; tap "$OUT/ls-7.xml" btn_save; sleep 1
A logcat -d -s PJAM_DATA:I | grep -qE "saved .* name=[0-9]+월 [0-9]+일 세션 2$" && pass save-auto-name-counts-up || fail save-auto-name-counts-up "두 번째 이름이 '세션 2'가 아님"
# 삭제
tap "$OUT/ls-7.xml" btn_library; screen 8
tap "$OUT/ls-8.xml" "btn_delete_$SID"; sleep 1; screen 9
$UI has "$OUT/ls-9.xml" "song_card_$SID" && fail delete-removes-song "삭제 후에도 카드가 있음" || pass delete-removes-song
$UI has "$OUT/ls-9.xml" song_card_example-full && pass delete-keeps-bundled-example || fail delete-keeps-bundled-example "예시 곡이 사라짐"
tap "$OUT/ls-9.xml" btn_page_back; screen 10

# ---- 설정: 조용한 연주 모드·입력 지연 보정, 앱을 껐다 켜도 유지 ----
tap "$OUT/ls-10.xml" btn_settings; screen 11
$UI has "$OUT/ls-11.xml" settings_screen && pass settings-screen || fail settings-screen "settings_screen 없음"
$UI has "$OUT/ls-11.xml" switch_quiet_off && pass settings-quiet-default-off || fail settings-quiet-default-off "기본값이 꺼짐이 아님"
[ "$($UI text "$OUT/ls-11.xml" latency_text)" = "0 ms" ] && pass settings-latency-default-0 || fail settings-latency-default-0 "기본 지연 보정이 0이 아님"
tap "$OUT/ls-11.xml" switch_quiet_off; tap "$OUT/ls-11.xml" btn_latency_up; screen 12
$UI has "$OUT/ls-12.xml" switch_quiet_on && pass settings-quiet-toggles || fail settings-quiet-toggles "스위치가 켜지지 않음"
[ "$($UI text "$OUT/ls-12.xml" latency_text)" = "10 ms" ] && pass settings-latency-up || fail settings-latency-up "지연 보정이 10 ms가 아님"
restart
screen 13; tap "$OUT/ls-13.xml" btn_settings; screen 14
$UI has "$OUT/ls-14.xml" switch_quiet_on && [ "$($UI text "$OUT/ls-14.xml" latency_text)" = "10 ms" ] && pass settings-persist-after-restart || fail settings-persist-after-restart "재시작 뒤 설정이 유지되지 않음"
tap "$OUT/ls-14.xml" btn_page_back; screen 15

# ---- 조용한 연주 모드: 방향지시등은 무시, 브레이크는 그대로 ----
VC turn Off >/dev/null 2>&1; VC brake 0 >/dev/null 2>&1; sleep 0.5
A logcat -c
VC turn BlinkRight >/dev/null 2>&1; sleep 1; VC turn Off >/dev/null 2>&1; sleep 0.5
log_has PJAM_INPUT "조용한 연주 모드: 무시" && ! log_has PJAM_INPUT "방향지시등 우 →" && pass quiet-ignores-turn-signal || fail quiet-ignores-turn-signal "조용한 연주 모드인데 방향지시등이 소리를 냄"
A logcat -c
VC brake 100 >/dev/null 2>&1; sleep 0.6; VC brake 0 >/dev/null 2>&1; sleep 0.5
log_has PJAM_INPUT "브레이크 →" && pass quiet-keeps-brake || fail quiet-keeps-brake "조용한 연주 모드에서 브레이크가 막힘"
# 모드를 끄면 방향지시등이 다시 소리를 낸다 (그리고 설정을 원래대로)
tap "$OUT/ls-15.xml" btn_settings; screen 16
tap "$OUT/ls-16.xml" switch_quiet_on; tap "$OUT/ls-16.xml" btn_latency_down; screen 17
tap "$OUT/ls-17.xml" btn_page_back
A logcat -c
VC turn BlinkRight >/dev/null 2>&1; sleep 1; VC turn Off >/dev/null 2>&1; sleep 0.5
log_has PJAM_INPUT "방향지시등 우 →" && pass quiet-off-turn-signal-works-again || fail quiet-off-turn-signal-works-again "모드를 껐는데 방향지시등이 안 울림"
RUNAS "rm -rf files/songs files/settings.json"
exit $FAILED
