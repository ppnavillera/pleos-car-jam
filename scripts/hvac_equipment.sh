#!/usr/bin/env bash
# T2-5 공조 4종 → 트랙 켜짐/꺼짐, T2-7 "내 차 장비" 화면, T2-6 카메라 창 겹침.
# Virtual Console(vc.sh)로 공조 값을 바꿔서 SDK → 앱까지 확인한다. 기준 하나당 한 줄 PASS/FAIL. 하나라도 FAIL이면 종료 코드 1.
# usage: hvac_equipment.sh <adb 시리얼> <출력 폴더>
set -u
DEV="$1"; OUT="$2"; PKG="app.pjam.studio"
HERE="$(cd "$(dirname "$0")" && pwd)"
UI="python3 -I $HERE/ui_tools.py"
A() { adb -s "$DEV" "$@"; }
VC() { bash "$HERE/vc.sh" "$@"; }
FAILED=0
pass() { echo "PASS $1"; }
fail() { echo "FAIL $1 → $2"; FAILED=1; }
dump_retry() { local i; for i in 1 2 3 4 5; do A exec-out uiautomator dump /dev/tty 2>/dev/null | sed 's/UI hierarchy dumped to.*//' >"$1"; grep -q "<hierarchy" "$1" && return 0; sleep 1; done; return 1; }
log_has() { A logcat -d -s "$1:I" | grep -qE "$2"; }
wait_log() { local i; for i in $(seq 1 $(( $3 * 4 ))); do log_has "$1" "$2" && return 0; sleep 0.25; done; return 1; }

VC gear P >/dev/null 2>&1
# 시작 전에 기준 상태를 만든다 (앱의 첫 값은 기준값이라 이벤트가 아니다): 전원 On, 4종 모두 꺼짐 상태
VC put /vehicle/hvac power=true dual=false ac=false auto=false recirc=false front_defroster=false >/dev/null 2>&1
sleep 1
A shell am force-stop "$PKG"; A logcat -c
A shell am start -W -n "$PKG/.MainActivity" >/dev/null 2>&1; sleep 5
dump_retry "$OUT/he-0.xml" || { fail ui-dump "$OUT/he-0.xml"; exit 1; }

# ---- 카메라 창 겹침 (FINDINGS: 앱 창 왼쪽 위 x 854~1606, y 90~513) ----
if $UI overlap "$OUT/he-0.xml" 854 90 1606 513 input_bar btn_play btn_record btn_clear_all btn_equipment pad_drum_kick pad_drum_snare pad_drum_hihat pad_drum_clap \
     track_card_DRUM_selected track_card_BASS track_card_MELODY track_card_FX switch_DRUM_on switch_BASS_on switch_MELODY_on switch_FX_on btn_bpm_up btn_bpm_down >"$OUT/he-overlap.log" 2>&1; then
  pass camera-zone-no-overlap
else
  fail camera-zone-no-overlap "$(tr '\n' ' ' <"$OUT/he-overlap.log")"
fi

# ---- 공조 4종 지원·배정 ----
for k in AC AUTO RECIRCULATION DEFROSTER; do
  if log_has PJAM_PLEOS "capability $k accessType=READ"; then pass "sdk-hvac-capability-$k"; else fail "sdk-hvac-capability-$k" "PJAM_PLEOS 에 $k capability 없음"; fi
done
if log_has PJAM_PLEOS "capability HVAC_POWER accessType=READ"; then pass sdk-hvac-power-capability; else fail sdk-hvac-power-capability "PJAM_PLEOS 에 HVAC_POWER capability 없음"; fi

if log_has PJAM_PLEOS "capability FAN_SPEED accessType=READ"; then pass sdk-fan-capability; else fail sdk-fan-capability "PJAM_PLEOS 에 FAN_SPEED capability 없음"; fi

# ---- 공조 변경 → 트랙 켜짐/꺼짐 (바뀔 때마다 뒤집힘) ----
flip() { # <vc 필드> <기대 로그> <트랙>
  A logcat -c
  VC put /vehicle/hvac "$1" >/dev/null 2>&1
  if wait_log PJAM_INPUT "$2" 4; then pass "vc-$3-toggle-log"; else fail "vc-$3-toggle-log" "PJAM_INPUT 에 '$2' 없음"; fi
}
flip ac=true "A/C → DRUM 켜기/끄기" DRUM
flip auto=true "AUTO → BASS 켜기/끄기" BASS
flip recirc=true "내기순환 → MELODY 켜기/끄기" MELODY
flip front_defroster=true "성에 제거 → FX 켜기/끄기" FX
sleep 0.5
dump_retry "$OUT/he-1.xml" || fail ui-dump "$OUT/he-1.xml"
for t in DRUM BASS MELODY FX; do
  if $UI has "$OUT/he-1.xml" "switch_${t}_off"; then pass "vc-hvac-mutes-$t"; else fail "vc-hvac-mutes-$t" "switch_${t}_off 노드 없음(꺼지지 않음)"; fi
done
# 같은 값으로 다시 PUT 하면 바뀐 게 아니므로 뒤집히지 않아야 한다
VC put /vehicle/hvac ac=true >/dev/null 2>&1; sleep 1
dump_retry "$OUT/he-1b.xml"
if $UI has "$OUT/he-1b.xml" switch_DRUM_off; then pass vc-hvac-same-value-ignored; else fail vc-hvac-same-value-ignored "같은 값인데 DRUM 스위치가 바뀜"; fi
# 반대로 바꾸면 다시 켜진다
for kv in ac=false auto=false recirc=false front_defroster=false; do VC put /vehicle/hvac "$kv" >/dev/null 2>&1; sleep 0.5; done
sleep 0.5; dump_retry "$OUT/he-2.xml" || fail ui-dump "$OUT/he-2.xml"
for t in DRUM BASS MELODY FX; do
  if $UI has "$OUT/he-2.xml" "switch_${t}_on"; then pass "vc-hvac-unmutes-$t"; else fail "vc-hvac-unmutes-$t" "switch_${t}_on 노드 없음"; fi
done

# ---- T3-1 풍량 → 템포: capability 1..8 을 70..140 BPM 으로. 범위 밖 값(0)은 끝 값(70) ----
fan() { # <풍량 값> <기대 BPM> <이름>
  A logcat -c
  VC put /vehicle/hvac "fan_speed=$1" >/dev/null 2>&1
  if wait_log PJAM_INPUT "풍량 → 템포 $2 BPM" 4; then
    sleep 0.5; dump_retry "$OUT/he-fan-$3.xml"
    if $UI text "$OUT/he-fan-$3.xml" bpm_text | grep -q "^$2 BPM"; then pass "vc-fan-$3-tempo-$2"; else fail "vc-fan-$3-tempo-$2" "화면 템포 표시가 $2 BPM 이 아님"; fi
  else fail "vc-fan-$3-tempo-$2" "PJAM_INPUT 에 '풍량 → 템포 $2 BPM' 없음"; fi
}
VC put /vehicle/hvac fan_speed=3 >/dev/null 2>&1; sleep 1   # 기준값을 확실히 3 으로 (앱의 첫 값은 이벤트가 아니다)
fan 8 140 max
fan 5 110 mid
fan 1 70 min
fan 8 140 max2
fan 0 70 below-range   # capability 범위(1..8) 밖 값 → 최소(70)

# ---- 내 차 장비 ----
A logcat -c
read -r EX EY < <($UI center "$OUT/he-2.xml" btn_equipment) || { fail equipment-button "btn_equipment 없음"; exit 1; }
A shell input tap "$EX" "$EY"; sleep 1.5
dump_retry "$OUT/he-eq.xml" || fail ui-dump "$OUT/he-eq.xml"
if $UI has "$OUT/he-eq.xml" equipment_screen; then pass equipment-screen; else fail equipment-screen "$OUT/he-eq.xml"; fi
N=$($UI count "$OUT/he-eq.xml" equip_row_)
if [ "$N" -ge 13 ]; then pass equipment-rows "$N"; else fail equipment-rows "equip_row_ 노드 $N 개 (<13)"; fi
if grep -q 'text="DRUM 트랙 켜기/끄기"' "$OUT/he-eq.xml"; then pass equipment-role-ac-drum; else fail equipment-role-ac-drum "A/C 줄에 DRUM 역할이 없음"; fi
if grep -q 'text="FX 트랙 켜기/끄기"' "$OUT/he-eq.xml"; then pass equipment-role-defroster-fx; else fail equipment-role-defroster-fx "성에 제거 줄에 FX 역할이 없음"; fi
if grep -q "이번 버전에서는 안 써요" "$OUT/he-eq.xml"; then pass equipment-unused-items; else fail equipment-unused-items "미사용 항목 안내 없음"; fi
if $UI has "$OUT/he-eq.xml" equip_gear_method; then pass equipment-gear-method; else fail equipment-gear-method "기어 판정 방식 노드 없음"; fi
# 각 조작 → 해당 줄 반짝임(원시 콜백 기준, PJAM_EQUIP 로그)
VC brake 100 >/dev/null 2>&1; sleep 0.6; VC brake 0 >/dev/null 2>&1
if wait_log PJAM_EQUIP "flash BRAKE" 3; then pass equipment-flash-brake; else fail equipment-flash-brake "flash BRAKE 로그 없음"; fi
VC steering 100 >/dev/null 2>&1; sleep 0.6; VC steering 0 >/dev/null 2>&1
if wait_log PJAM_EQUIP "flash STEERING" 3; then pass equipment-flash-steering; else fail equipment-flash-steering "flash STEERING 로그 없음"; fi
VC turn BlinkRight >/dev/null 2>&1; sleep 1; VC turn Off >/dev/null 2>&1
if wait_log PJAM_EQUIP "flash TURN_SIGNAL" 3; then pass equipment-flash-turn; else fail equipment-flash-turn "flash TURN_SIGNAL 로그 없음"; fi
for kv in "ac=true:AC" "auto=true:AUTO" "recirc=true:RECIRCULATION" "front_defroster=true:DEFROSTER"; do
  VC put /vehicle/hvac "${kv%%:*}" >/dev/null 2>&1
  if wait_log PJAM_EQUIP "flash ${kv##*:}" 3; then pass "equipment-flash-${kv##*:}"; else fail "equipment-flash-${kv##*:}" "flash ${kv##*:} 로그 없음"; fi
done
# 공조 전원이 꺼져 있으면 그 사유를 보여 준다
# (에뮬레이터는 공조 값을 모두 끄면 전원도 꺼질 수 있어, 먼저 켜서 확실히 On → Off 전환을 만든다)
VC put /vehicle/hvac power=true >/dev/null 2>&1; sleep 1.5
dump_retry "$OUT/he-eq1b.xml"
if ! grep -q "사유: 공조 전원이 꺼져" "$OUT/he-eq1b.xml"; then pass equipment-power-on-no-reason; else fail equipment-power-on-no-reason "전원이 켜졌는데 사유가 남아 있음"; fi
A logcat -c
VC put /vehicle/hvac power=false >/dev/null 2>&1
if wait_log PJAM_SIG "item=HVAC_POWER value=false src=listener" 4; then pass equipment-power-off-callback; else fail equipment-power-off-callback "전원 Off 콜백 없음"; fi
sleep 1; dump_retry "$OUT/he-eq2.xml" || fail ui-dump "$OUT/he-eq2.xml"
if grep -q "공조 전원이 꺼져 있어요" "$OUT/he-eq2.xml" && grep -q "사유: 공조 전원이 꺼져" "$OUT/he-eq2.xml"; then pass equipment-power-off-reason; else fail equipment-power-off-reason "전원 꺼짐 사유 문구 없음 $OUT/he-eq2.xml"; fi
A logcat -c
VC put /vehicle/hvac power=true >/dev/null 2>&1
wait_log PJAM_SIG "item=HVAC_POWER value=true src=listener" 4 || fail equipment-power-on-callback "전원 On 콜백 없음"
sleep 1
dump_retry "$OUT/he-eq3.xml"
if ! grep -q "사유: 공조 전원이 꺼져" "$OUT/he-eq3.xml"; then pass equipment-power-on-clears-reason; else fail equipment-power-on-clears-reason "전원을 켰는데 사유가 남아 있음"; fi
# 돌아가기
read -r BX BY < <($UI center "$OUT/he-eq3.xml" btn_equipment_back) || fail equipment-back "btn_equipment_back 없음"
A shell input tap "$BX" "$BY"; sleep 1
dump_retry "$OUT/he-3.xml"
if $UI has "$OUT/he-3.xml" studio_screen; then pass equipment-back-to-studio; else fail equipment-back-to-studio "$OUT/he-3.xml"; fi
exit $FAILED
