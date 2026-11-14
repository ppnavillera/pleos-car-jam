#!/usr/bin/env bash
# Virtual Console(에뮬레이터 웹 페이지)을 브라우저 없이 조작한다. (계획서 T0-0 ②)
#   scripts/vc.sh gear P|R|N|D
#   scripts/vc.sh brake 0..100
#   scripts/vc.sh steering -540..540
#   scripts/vc.sh turn Off|BlinkLeft|BlinkRight|FlashLeft|FlashRight
#   scripts/vc.sh state
# 원리: 쓰기 API(PUT)는 브라우저 세션이 살아 있을 때만 열린다(emulator_inactive).
#       페이지가 1초마다 보내는 하트비트 POST /browser/session 를 우리가 대신 보낸다.
set -eu
BASE="http://127.0.0.1:8088"
adb forward tcp:8088 tcp:8088 >/dev/null 2>&1 || true
CID="pjam-vc-$$"
beat() { curl -s -m 2 -X POST "$BASE/browser/session?client=$CID" >/dev/null || true; }
beat; sleep 1.2; beat   # 활성화 대기

state_field() { curl -s -m 5 "$BASE/vehicle/state" | python3 -c "import sys,json; d=json.load(sys.stdin); $1"; }

case "${1:-state}" in
  gear)
    case "$2" in P) g=Park;; R) g=Reverse;; N) g=Neutral;; D) g=Drive;; *) echo "gear P|R|N|D"; exit 2;; esac
    curl -s -m 5 -X PUT "$BASE/vehicle/gear?gear=$g" >/dev/null; sleep 0.5; beat
    state_field "print(d['gear']['actual']['value'])" ;;
  brake)
    curl -s -m 5 -X PUT "$BASE/vehicle/controls?speed=0&accelerator=0&brake=$2" >/dev/null; sleep 0.3; beat
    state_field "print('brake_pct', d['controls']['brake_pct'])" ;;
  steering)
    curl -s -m 5 -X PUT "$BASE/vehicle/controls?speed=0&accelerator=0&brake=0&steering=$2" >/dev/null; sleep 0.3; beat
    state_field "print('steering_deg', d['controls']['steering_deg'])" ;;
  turn)
    curl -s -m 5 -X PUT "$BASE/vehicle/turn-signal?mode=$2" >/dev/null; sleep 0.3; beat
    echo "turn $2" ;;
  state)
    state_field "print('gear', d['gear']['actual']['value'], 'brake', d['controls']['brake_pct'], 'steering', d['controls']['steering_deg'])" ;;
  *) echo "usage: vc.sh gear|brake|steering|state"; exit 2 ;;
esac
