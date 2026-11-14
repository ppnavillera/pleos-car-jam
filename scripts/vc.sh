#!/usr/bin/env bash
# Virtual Console(에뮬레이터 웹 페이지)을 브라우저 없이 조작한다. (계획서 T0-0 ②)
#   scripts/vc.sh gear P|R|N|D
#   scripts/vc.sh brake 0..100
#   scripts/vc.sh steering -540..540
#   scripts/vc.sh turn Off|BlinkLeft|BlinkRight|FlashLeft|FlashRight
#   scripts/vc.sh gear-timed P|R|N|D   (PUT 직전·직후의 호스트 시각(ms)과 VC 기록 시각을 출력: 지연 측정용)
#   scripts/vc.sh put /vehicle/hvac ac=true fan_speed=5   (카탈로그 docs/vc-catalog.json 의 write_endpoint 와 control.field 로 아무 카드나 조작)
#   scripts/vc.sh get hvac            (GET /vehicle/state 의 한 키를 JSON 으로 출력)
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
  put)
    ep="$2"; shift 2; q=""; for kv in "$@"; do q="$q&$kv"; done
    # 응답은 상태 전체(수십 KB)라서 오류일 때만 앞부분을 보여 준다
    resp=$(curl -s -m 5 -X PUT "$BASE$ep?${q#&}"); case "$resp" in *'"error"'*) echo "${resp:0:200}";; *) echo "ok";; esac
    sleep 0.3; beat ;;
  get)
    state_field "print(json.dumps(d.get('$2'), ensure_ascii=False))" ;;
  gear-timed)
    case "$2" in P) g=Park;; R) g=Reverse;; N) g=Neutral;; D) g=Drive;; *) echo "gear-timed P|R|N|D"; exit 2;; esac
    python3 -I - "$BASE" "$g" <<'PY'
import sys, time, json, urllib.request
base, g = sys.argv[1], sys.argv[2]
t0 = time.time()
urllib.request.urlopen(urllib.request.Request(base + "/vehicle/gear?gear=" + g, method="PUT"), timeout=5).read()
t1 = time.time()
st = json.load(urllib.request.urlopen(base + "/vehicle/state", timeout=5))
print("PUT_START_MS=%d PUT_END_MS=%d VC_STAMP_MS=%s value=%s" % (t0 * 1000, t1 * 1000, st["gear"]["actual"]["timestamp_ms"], st["gear"]["actual"]["value"]))
PY
    beat ;;
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
