#!/usr/bin/env bash
# 연속 재생 스텝 호출 오차 측정 (T0-13 / T2-9). 메트로놈이 박마다 울리는 DRUM 녹음 상태로 N초 동안 둔다.
# usage: lag_run.sh <adb 시리얼> [초=190]   결과: PJAM_LAG 요약 줄(200스텝마다, 누적)
set -u
DEV="$1"; SECS="${2:-190}"; PKG="app.pjam.studio"
A() { adb -s "$DEV" "$@"; }
cd "$(dirname "$0")/.."
bash scripts/vc.sh gear P >/dev/null 2>&1
A shell am force-stop "$PKG"; A shell am start -W -n "$PKG/.MainActivity" >/dev/null; sleep 4
XY=$(A exec-out uiautomator dump /dev/tty 2>/dev/null | sed 's/UI hierarchy dumped to.*//' | python3 -I -c '
import re,sys
xml=sys.stdin.read()
m=re.search(r"content-desc=\"btn_record\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"",xml)
a,b,c,d=map(int,m.groups()); print((a+c)//2,(b+d)//2)')
A logcat -c
A shell input tap $XY
sleep "$SECS"
A shell input tap $XY
sleep 1
A logcat -d -s PJAM_LAG:I | sed 's/.*PJAM_LAG *: //'
