#!/usr/bin/env python3
"""Virtual Console → SDK → 앱 지연·콜백 간격 측정 (T0-8 핸들, T0-10 지연). 디버그 빌드의 PJAM_SIG 로그를 쓴다.

  vc_measure.py brake      브레이크 20회(밟음/뗌 교대): PUT 시각 → 콜백 도착 시각 지연, 콜백 간 간격
  vc_measure.py offset     호스트-에뮬레이터 시계 차(ms)만 출력 (gear_regression.sh 가 지연 보정에 쓴다)
  vc_measure.py steering   핸들을 0 → 왼쪽 끝 → 오른쪽 끝 → 0 으로 10° 간격·100ms 마다 움직이며 SDK 값 범위·부호·콜백 간격 측정

시각 보정: 호스트 시계와 에뮬레이터 시계의 차는 `adb shell date +%s%3N`을 호스트 시각 사이에 끼워 10번 재서 중앙값으로 뺀다(오차 ±수십 ms).
VC 쪽 쓰기는 브라우저 세션이 살아 있어야 해서 하트비트를 백그라운드 스레드로 보낸다(vc.sh 와 같은 이유).
"""
import json, re, statistics, subprocess, sys, threading, time, urllib.request

BASE = "http://127.0.0.1:8088"
CID = "pjam-measure-%d" % int(time.time())
stop_beat = False

def post(path):
    try: urllib.request.urlopen(urllib.request.Request(BASE + path, method="POST"), timeout=2).read()
    except Exception: pass

def put(path):
    return urllib.request.urlopen(urllib.request.Request(BASE + path, method="PUT"), timeout=5).read()

def beat_loop():
    while not stop_beat:
        post("/browser/session?client=" + CID); time.sleep(0.5)

def adb(*a): return subprocess.run(["adb", *a], capture_output=True, text=True).stdout

def clock_offset_ms():
    """호스트-에뮬레이터 시계 차. `adb shell date`를 매번 새로 띄우면 호출 지연(수십 ms)이 섞이므로 열어 둔 셸 하나에
    명령을 흘려 왕복이 가장 짧은 표본의 중앙값을 쓴다(오차 수 ms)."""
    p = subprocess.Popen(["adb", "shell"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, bufsize=1)
    samples = []
    for _ in range(40):
        a = time.time() * 1000
        p.stdin.write("date +%s%3N\n"); p.stdin.flush()
        d = int(p.stdout.readline().strip())
        b = time.time() * 1000
        samples.append((b - a, d - (a + b) / 2))
    p.stdin.write("exit\n"); p.stdin.flush(); p.wait(timeout=5)
    samples.sort()
    best = samples[:10]
    print("clock probe: shortest round-trips %.1f..%.1f ms" % (best[0][0], best[-1][0]), file=sys.stderr)
    return statistics.median(o for _, o in best)

def sig_lines(item_prefix):
    out = adb("logcat", "-d", "-v", "epoch", "-s", "PJAM_SIG:I")
    rows = []
    for ln in out.splitlines():
        m = re.match(r"\s*(\d+\.\d+)\s.*item=(\S+) value=(\S+)", ln)
        if m and m.group(2).startswith(item_prefix):
            rows.append((float(m.group(1)) * 1000, m.group(3), 0))
    return rows

def stats(xs):
    xs = sorted(xs)
    return "n=%d mean=%.1f median=%.1f p95=%.1f max=%.1f min=%.1f (ms)" % (
        len(xs), statistics.mean(xs), statistics.median(xs), xs[min(len(xs) - 1, int(0.95 * len(xs)))], xs[-1], xs[0])

def main():
    global stop_beat
    mode = sys.argv[1]
    if mode == "offset":  # 호스트-에뮬레이터 시계 차(ms, 정수)만 출력한다
        print(int(round(clock_offset_ms()))); return
    threading.Thread(target=beat_loop, daemon=True).start(); time.sleep(1.5)
    off = clock_offset_ms()
    print("clock offset (device - host) = %.0f ms" % off)
    adb("logcat", "-c")
    if mode == "brake":
        puts = []
        for i in range(20):
            v = 100 if i % 2 == 0 else 0
            t = time.time() * 1000
            put("/vehicle/controls?speed=0&accelerator=0&brake=%d" % v)
            puts.append((t, "true" if v else "false")); time.sleep(0.6)
        time.sleep(1)
        cbs = [r for r in sig_lines("BRAKE")]
        # 값이 바뀐 콜백만(구독 직후 값 제외): PUT 순서대로 같은 값의 첫 콜백을 짝지음
        lat = []; used = 0
        for t, val in puts:
            for j in range(used, len(cbs)):
                if cbs[j][1] == val and cbs[j][0] - off >= t - 50:
                    lat.append(cbs[j][0] - off - t); used = j + 1; break
        print("PUT → SDK callback latency:", stats(lat) if lat else "no matches")
        gaps = [cbs[i + 1][0] - cbs[i][0] for i in range(len(cbs) - 1)]
        print("callbacks received:", len(cbs), "of", len(puts), "puts; gap between callbacks:", stats(gaps) if gaps else "-")
        print("raw latencies:", [round(x) for x in lat])
    elif mode == "steering":
        seq = list(range(0, -471, -10)) + list(range(-470, 501, 10)) + list(range(500, -1, -10))
        puts = []
        for v in seq:
            t = time.time() * 1000
            put("/vehicle/controls?speed=0&accelerator=0&brake=0&steering=%d" % v)
            puts.append((t, v)); time.sleep(0.1)
        time.sleep(1)
        cbs = sig_lines("STEERING")
        vals = [float(c[1]) for c in cbs]
        print("PUT steps:", len(puts), " callbacks:", len(cbs))
        print("SDK value range: min=%.1f max=%.1f" % (min(vals), max(vals)))
        print("sign check: VC -470 → SDK", [c[1] for c in cbs if abs(float(c[1]) - 470) < 1][:1], "; VC +500 → SDK", [c[1] for c in cbs if abs(float(c[1]) + 500) < 1][:1])
        gaps = [cbs[i + 1][0] - cbs[i][0] for i in range(len(cbs) - 1)]
        print("callback gap:", stats(gaps) if gaps else "-")
        # 지연: 같은 크기 값(부호 반대)을 짝지음
        lat = []; used = 0
        for t, v in puts:
            if v == 0: continue
            for j in range(used, len(cbs)):
                if abs(float(cbs[j][1]) + v) < 0.5 and cbs[j][0] - off >= t - 50:
                    lat.append(cbs[j][0] - off - t); used = j + 1; break
        print("PUT → SDK callback latency:", stats(lat) if lat else "no matches")
        # 단위 확인: 10° 단계로 보냈을 때 연속 콜백 차
        diffs = sorted(set(round(abs(float(cbs[i + 1][1]) - float(cbs[i][1])), 1) for i in range(len(cbs) - 1)))
        print("distinct step sizes between consecutive callbacks:", diffs[:8])
    stop_beat = True

main()
