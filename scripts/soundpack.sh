#!/usr/bin/env bash
# T3-5 차량 사운드팩: 화면 버튼으로 팩 전환(재생 중에도 끊기지 않음), 팩별 패드 이름, 소리 출력, 저장·재시작 유지.
# 소리 자체가 어떤지는 사람이 귀로 들어야 한다([귀]). 이 스크립트는 전환·로드·출력이 되는지만 본다.
# usage: soundpack.sh <adb 시리얼> <출력 폴더>
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
tap() { local x y; read -r x y < <($UI center "$1" "$2") || return 1; A shell input tap "$x" "$y"; sleep 1.2; }
screen() { dump_retry "$OUT/sp-$1.xml" || { fail "ui-dump-$1" "덤프 실패"; return 1; }; }
standby() { A shell dumpsys media.audio_flinger 2>/dev/null | grep -A3 'name AudioOut_D' | grep -q 'Standby: yes'; }
restart() { A shell am force-stop "$PKG"; A logcat -c; A shell am start -W -n "$PKG/.MainActivity" >/dev/null 2>&1; sleep 4; }

VC gear P >/dev/null 2>&1
A shell am force-stop "$PKG"
RUNAS "rm -rf files/songs files/settings.json"
restart
LOADED=$(A logcat -d -s PJAM_SOUND:I | grep -E "loaded ([0-9]+)/\1( |$)" | tail -1)
echo "$LOADED" | grep -q "loaded 54/54" && pass packs-all-loaded "$LOADED" || fail packs-all-loaded "기대 loaded 54/54: $(A logcat -d -s PJAM_SOUND:I | tail -2 | tr '\n' ' ')"
screen 0 || exit 1
$UI has "$OUT/sp-0.xml" btn_pack_basic && pass pack-default-basic || fail pack-default-basic "기본 팩 버튼 없음"
[ "$($UI text "$OUT/sp-0.xml" pad_drum_kick 2>/dev/null)" != "" ] || true
grep -q 'text="킥"' "$OUT/sp-0.xml" && pass pad-label-basic-kick || fail pad-label-basic-kick "기본 팩 패드 이름 '킥' 없음"

# ---- 재생 중 팩 전환: 끊김 없이 루프가 계속 돈다 ----
tap "$OUT/sp-0.xml" btn_library; screen 1
tap "$OUT/sp-1.xml" btn_load_example-full; sleep 3
A logcat -c
screen 2
tap "$OUT/sp-2.xml" btn_pack_basic
sleep 6
A logcat -d -s PJAM_SOUND:I | grep -q "pack=car" && pass pack-switch-logged || fail pack-switch-logged "pack=car 로그 없음"
A logcat -d -s PJAM_ENG:I | grep -q " STOP$" && fail pack-switch-no-stop "전환하자 재생이 멈춤" || pass pack-switch-no-stop
A logcat -d -s PJAM_ENG:I | grep -q "LOOP wrap" && pass pack-switch-loop-continues || fail pack-switch-loop-continues "전환 뒤 루프가 돌지 않음"
screen 3
$UI has "$OUT/sp-3.xml" btn_pack_car && pass pack-button-shows-car || fail pack-button-shows-car "버튼이 차량 팩으로 바뀌지 않음"
grep -q 'text="도어"' "$OUT/sp-3.xml" && pass pad-label-car-door || fail pad-label-car-door "차량 팩 패드 이름 '도어' 없음"
[ "$($UI text "$OUT/sp-3.xml" status_text)" = "재생 중" ] && pass pack-switch-still-playing || fail pack-switch-still-playing "상태: $($UI text "$OUT/sp-3.xml" status_text)"

# ---- 차량 팩 패드를 누르면 소리가 난다 (출력 스레드가 깬다) ----
tap "$OUT/sp-3.xml" btn_play      # 정지
sleep 4.5
standby && pass car-silent-when-stopped || fail car-silent-when-stopped "정지했는데 출력이 standby가 아님"
read -r KX KY < <($UI center "$OUT/sp-3.xml" pad_drum_kick) || fail pad-found "pad_drum_kick 없음"
A shell input tap "$KX" "$KY"; sleep 0.4
standby && fail car-pad-makes-sound "차량 팩 도어 패드를 눌러도 출력이 깨지 않음" || pass car-pad-makes-sound
sleep 0.3; screen 4
grep -q "화면 패드 → 도어" "$OUT/sp-4.xml" && pass car-input-bar-name || fail car-input-bar-name "입력 표시줄에 '화면 패드 → 도어' 없음"

# ---- 저장 곡에 사운드팩이 남고, 재시작 뒤에도 유지 ----
A logcat -c
tap "$OUT/sp-4.xml" btn_save; sleep 1
SF=$(RUNAS 'cat files/songs/*.json' | tr -d '\r')
echo "$SF" | grep -q '"soundPackId":"car"' && pass saved-song-keeps-pack || fail saved-song-keeps-pack "저장 파일에 soundPackId car 없음"
restart; screen 5
$UI has "$OUT/sp-5.xml" btn_pack_car && pass pack-persists-after-restart || fail pack-persists-after-restart "재시작 뒤 팩이 차량이 아님"
A logcat -d -s PJAM_SOUND:I | grep -q "active=car" && pass pack-active-at-start || fail pack-active-at-start "시작할 때 차량 팩이 활성이 아님"

# ---- 설정 화면에서도 고른다 ----
tap "$OUT/sp-5.xml" btn_settings; screen 6
$UI has "$OUT/sp-6.xml" settings_pack_car_selected && pass settings-pack-selected-car || fail settings-pack-selected-car "설정 화면에 차량 팩 선택 표시 없음"
tap "$OUT/sp-6.xml" settings_pack_basic; screen 7
$UI has "$OUT/sp-7.xml" settings_pack_basic_selected && pass settings-pack-switch-basic || fail settings-pack-switch-basic "기본 팩으로 안 바뀜"

# ---- 저장된 곡의 팩으로 불러온다 (보관함에서 차량 팩 곡 → 팩이 차량으로) ----
tap "$OUT/sp-7.xml" btn_page_back; screen 8
tap "$OUT/sp-8.xml" btn_library; screen 9
SID=$(echo "$SF" | grep -o '"id":"[0-9a-f-]*"' | head -1 | sed 's/"id":"//;s/"//')
tap "$OUT/sp-9.xml" "btn_load_$SID"; sleep 1.5; screen 10
$UI has "$OUT/sp-10.xml" btn_pack_car && pass load-song-restores-pack || fail load-song-restores-pack "차량 팩 곡을 불러왔는데 팩이 차량이 아님"
RUNAS "rm -rf files/songs files/settings.json"
exit $FAILED
