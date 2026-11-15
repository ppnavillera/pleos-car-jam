#!/usr/bin/env python3
"""assets/soundpacks/<팩>의 WAV를 검증한다 (인자: 팩 이름, 기본 basic; 모두: all). 실패하면 종료 코드 1."""
import math
import os
import sys
import wave
import struct

HERE = os.path.dirname(os.path.abspath(__file__))
PACKS_DIR = os.path.normpath(os.path.join(HERE, "..", "..", "assets", "soundpacks"))
PACK = os.path.join(PACKS_DIR, "basic")

EXPECTED = (
    ["drum_kick", "drum_snare", "drum_hihat", "drum_clap"]
    + ["bass_%d_o%d" % (d, o) for d in (1, 4, 5) for o in (1, 2, 3)]
    + ["mel_%d" % i for i in range(8)]
    + ["fx_impact", "fx_riser", "fx_sweep", "fx_zap"]
    + ["metro_hi", "metro_lo"]
)
PITCH = {  # 기대 기본음 (Hz)
    "bass_1_o1": 65.41, "bass_4_o1": 87.31, "bass_5_o1": 98.00,
    "bass_1_o2": 130.81, "bass_4_o2": 174.61, "bass_5_o2": 196.00,
    "bass_1_o3": 261.63, "bass_4_o3": 349.23, "bass_5_o3": 392.00,
    "mel_0": 261.63, "mel_1": 293.66, "mel_2": 329.63, "mel_3": 392.00,
    "mel_4": 440.00, "mel_5": 523.25, "mel_6": 587.33, "mel_7": 659.25,
    "metro_hi": 1500.0, "metro_lo": 1000.0,
}
LIMIT_PEAK = 0.9 * 32767


def power_at(x, freq, sr):
    """Goertzel 유사 단일 주파수 파워 (Hann 창)."""
    n = len(x)
    re = im = 0.0
    for i, v in enumerate(x):
        w = 0.5 - 0.5 * math.cos(2 * math.pi * i / (n - 1))
        a = 2 * math.pi * freq * i / sr
        re += v * w * math.cos(a)
        im += v * w * math.sin(a)
    return re * re + im * im


# 팩마다 악기가 다르다: 차량 팩의 메트로놈은 방향지시등 릴레이 "딱/똑"(음높이가 없는 클릭)이다.
NO_PITCH = {"car": {"metro_hi", "metro_lo"}}
# 귀에 거슬리지 않게 짧고 부드럽게 다듬은 소리의 상한 (차량 팩 경적 계열)
SOFT_MAX_DUR = {"car": {**{"bass_%d_o%d" % (d, o): 0.6 for d in (1, 4, 5) for o in (1, 2, 3)}, **{"mel_%d" % i: 0.6 for i in range(8)}, "fx_impact": 0.7}}
SOFT_MAX_PEAK = {"car": {**{"bass_%d_o%d" % (d, o): 0.7 for d in (1, 4, 5) for o in (1, 2, 3)}, **{"mel_%d" % i: 0.7 for i in range(8)}, "fx_impact": 0.7}}


def check(pack_name):
    global PACK
    PACK = os.path.join(PACKS_DIR, pack_name)
    print("== 팩: %s" % pack_name)
    return run(pack_name)


def main():
    names = sys.argv[1:] or ["basic"]
    if names == ["all"]:
        names = sorted(d for d in os.listdir(PACKS_DIR) if os.path.isdir(os.path.join(PACKS_DIR, d)))
    return 1 if any(check(n) for n in names) else 0


def run(pack_name):
    errors = []
    if not os.path.isdir(PACK):
        print("FAIL: 폴더 없음 " + PACK)
        return 1
    files = sorted(f for f in os.listdir(PACK) if not f.startswith("."))
    names = sorted(f[:-4] for f in files if f.endswith(".wav"))
    others = [f for f in files if not f.endswith(".wav")]
    missing = sorted(set(EXPECTED) - set(names))
    extra = sorted(set(names) - set(EXPECTED))
    if missing:
        errors.append("누락: %s" % missing)
    if extra:
        errors.append("초과: %s" % extra)
    if others:
        errors.append("wav 아닌 파일: %s" % others)

    for name in names:
        if name not in EXPECTED:
            continue
        with wave.open(os.path.join(PACK, name + ".wav"), "rb") as w:
            ch, sw, sr, nf = w.getnchannels(), w.getsampwidth(), w.getframerate(), w.getnframes()
            raw = w.readframes(nf)
        x = struct.unpack("<%dh" % nf, raw)
        dur = nf / sr
        peak = max(abs(v) for v in x)
        rms = math.sqrt(sum(v * v for v in x) / nf)
        bad = []
        if ch != 1: bad.append("channels=%d" % ch)
        if sw != 2: bad.append("sampwidth=%d" % sw)
        if sr != 48000: bad.append("rate=%d" % sr)
        if dur > 1.0: bad.append("dur=%.3f>1.0" % dur)
        if peak > LIMIT_PEAK: bad.append("peak=%d>%d" % (peak, LIMIT_PEAK))
        if rms <= 0: bad.append("silent")
        if x[0] != 0 or abs(x[-1]) > 0: bad.append("edge nonzero %d/%d" % (x[0], x[-1]))
        if dur > SOFT_MAX_DUR.get(pack_name, {}).get(name, 99): bad.append("dur=%.3f 너무 김(부드럽게 짧게)" % dur)
        if peak / 32767 > SOFT_MAX_PEAK.get(pack_name, {}).get(name, 99): bad.append("peak=%.3f 너무 큼(부드럽게)" % (peak / 32767))
        if name in PITCH and name not in NO_PITCH.get(pack_name, set()):
            f0 = PITCH[name]
            seg = x[: min(nf, int(0.25 * sr))]
            p0 = power_at(seg, f0, sr)
            if not (p0 > 4 * power_at(seg, f0 * 1.12, sr) and p0 > 4 * power_at(seg, f0 / 1.12, sr)):
                bad.append("pitch %.2fHz 불일치" % f0)
        status = "FAIL" if bad else "ok"
        print("%-4s %-10s dur=%.3fs peak=%.3f rms=%.0f %s" % (status, name, dur, peak / 32767, rms, " ".join(bad)))
        if bad:
            errors.append("%s: %s" % (name, bad))

    print("파일 %d개 확인 (기대 %d)" % (len(names), len(EXPECTED)))
    if errors:
        for e in errors:
            print("FAIL " + e)
        return 1
    print("PASS %s" % pack_name)
    return 0


if __name__ == "__main__":
    sys.exit(main())
