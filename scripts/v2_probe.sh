#!/usr/bin/env bash
# V2 점검 (T0-3): Virtual Console 값을 바꿔 가며 디버그 빌드의 SignalProbe 콜백(PJAM_SIG)이 오는지 확인한다.
# 출력: 항목별로 "조작 → 도착한 콜백 값 / 지연(ms) / 스레드" 한 줄. 도착 안 하면 NO CALLBACK.
# 사전 조건: 디버그 APK 설치, 앱 실행 중(기어 P). usage: v2_probe.sh [출력 파일]
set -u
cd "$(dirname "$0")/.."
OUT="${1:-build/check-artifacts/v2-probe.txt}"; mkdir -p "$(dirname "$OUT")"; : >"$OUT"
VC="bash scripts/vc.sh"
log() { echo "$*" | tee -a "$OUT"; }

now_ms() { python3 -c 'import time;print(int(time.time()*1000))'; }

# row: SIGITEM | 조작 인자 (vc.sh put ...) | 되돌리기 인자
step() { # label sigitem args...
  local label="$1" item="$2"; shift 2
  adb logcat -c
  local t0; t0=$(now_ms)
  "$@" >/dev/null 2>&1
  sleep 1.2
  local line; line=$(adb logcat -d -v epoch -s PJAM_SIG:I | grep "item=$item " | head -1)
  if [ -z "$line" ]; then log "NO CALLBACK  $label (item=$item)"; return 1; fi
  local ms val th
  ms=$(echo "$line" | awk '{printf "%d",$1*1000}'); val=$(echo "$line" | sed 's/.*value=\([^ ]*\).*/\1/'); th=$(echo "$line" | sed 's/.*thread=//')
  log "CALLBACK     $label → item=$item value=$val  +$((ms - t0))ms  thread=$th"
}

log "# V2 probe $(date '+%F %T')"
# 점검 전제: HVAC Power On, HVAC Dual Off (계획서 1.3)
$VC put /vehicle/hvac power=true dual=false >/dev/null 2>&1
sleep 1
step "AC off"                 AC          $VC put /vehicle/hvac ac=false
step "AC on"                  AC          $VC put /vehicle/hvac ac=true
step "AUTO on"                AUTO        $VC put /vehicle/hvac auto=true
step "AUTO off"               AUTO        $VC put /vehicle/hvac auto=false
step "RECIRC on"              RECIRC      $VC put /vehicle/hvac recirc=true
step "RECIRC off"             RECIRC      $VC put /vehicle/hvac recirc=false
step "DEFROSTER front on"     DEFROSTER   $VC put /vehicle/hvac front_defroster=true
step "DEFROSTER front off"    DEFROSTER   $VC put /vehicle/hvac front_defroster=false
step "FAN_SPEED 6"            FAN_SPEED   $VC put /vehicle/hvac fan_speed=6
step "FAN_SPEED 3"            FAN_SPEED   $VC put /vehicle/hvac fan_speed=3
step "TEMPERATURE driver 24.5" TEMPERATURE $VC put /vehicle/hvac driver_temp=24.5
step "TEMPERATURE driver 22"  TEMPERATURE $VC put /vehicle/hvac driver_temp=22
step "WINDOW front-left 50"   WINDOW_FL   $VC put /vehicle/body window_fl=50
step "WINDOW front-right 80"  WINDOW_FR   $VC put /vehicle/body window_fr=80
step "WINDOW front-left 0"    WINDOW_FL   $VC put /vehicle/body window_fl=0
step "WINDOW front-right 0"   WINDOW_FR   $VC put /vehicle/body window_fr=0
step "WIPER front Low"        WIPER       $VC put /vehicle/body front_wiper=Low
step "WIPER front High"       WIPER       $VC put /vehicle/body front_wiper=High
step "WIPER front Off"        WIPER       $VC put /vehicle/body front_wiper=Off
step "AMBIENT brightness 40"  AMBIENT     $VC put /vehicle/body ambient_brightness=40
step "AMBIENT brightness 70"  AMBIENT     $VC put /vehicle/body ambient_brightness=70
step "STEERING 100"           STEERING    $VC put /vehicle/controls speed=0 accelerator=0 brake=0 steering=100
step "STEERING -100"          STEERING    $VC put /vehicle/controls speed=0 accelerator=0 brake=0 steering=-100
step "STEERING 0"             STEERING    $VC put /vehicle/controls speed=0 accelerator=0 brake=0 steering=0
step "EV charge port open"    EV_CHARGE_PORT $VC put /vehicle/ev-battery field=charge_port_open value=true
step "EV charge port closed"  EV_CHARGE_PORT $VC put /vehicle/ev-battery field=charge_port_open value=false
log "# DRIVE_MODE: Virtual Console 카드 없음 (조작 불가). capability·조회 결과는 PJAM_V2 로그"
