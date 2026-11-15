#!/usr/bin/env bash
# 4트랙이 꽉 찬 곡(assets/songs/example_full.json, 노트 57개)을 재생하며 스텝 호출 오차를 잰다 (T2-9 보강, 최대 지연 조사).
# 디버그 빌드 전용 훅(files/load_example)을 쓴다. 결과: PJAM_LAG 요약, 10ms 넘은 스텝 목록, 같은 시각대의 art(GC) 로그, 엔진 스레드 우선순위.
# usage: lag_full.sh <adb 시리얼> [초=190] [출력 폴더]
set -u
DEV="$1"; SECS="${2:-190}"; OUT="${3:-build/check-artifacts}"; PKG="app.pjam.studio"
A() { adb -s "$DEV" "$@"; }
USR=$(A shell am get-current-user | tr -d "\r")   # AAOS 는 앱이 사용자 10 으로 돈다. run-as 에 같은 사용자를 줘야 앱의 files 에 닿는다
cd "$(dirname "$0")/.."
mkdir -p "$OUT"
bash scripts/vc.sh gear P >/dev/null 2>&1
A shell am force-stop "$PKG"
A shell "run-as $PKG --user $USR sh -c 'mkdir -p files; touch files/load_example'"
A logcat -c
A shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 6
PID=$(A shell pidof "$PKG" | tr -d '\r')
echo "pid=$PID"
echo "--- engine thread (ps -T)"
A shell ps -T -p "$PID" -o TID,NAME,NI,PRI,CMD 2>/dev/null | grep -i "pjam-engine\|NAME" || A shell ps -T -p "$PID" | grep -i "pjam-engine\|NAME"
sleep "$SECS"
A logcat -d -v epoch -b main,system >"$OUT/lag-full-all.log"
A shell "run-as $PKG --user $USR rm -f files/load_example"
echo "--- summary"
grep "PJAM_LAG" "$OUT/lag-full-all.log" | grep -E "n=" | tail -1 | sed 's/.*PJAM_LAG *: //'
echo "--- slow steps (>10ms)"
grep "PJAM_LAG" "$OUT/lag-full-all.log" | grep "slow step" | sed 's/^\([0-9.]*\) .*PJAM_LAG *: /\1 /'
echo "--- GC lines (art)"
grep -E " art |dalvik" "$OUT/lag-full-all.log" | grep -i "GC" | sed 's/^\([0-9.]*\) .*I art *: /\1 /' | tail -40
echo "--- SoundPool/audio underrun-ish"
grep -iE "underrun|AudioTrack.*(fail|error)|SoundPool.*(error|fail)" "$OUT/lag-full-all.log" | tail -10
