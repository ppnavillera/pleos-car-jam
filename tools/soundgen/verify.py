#!/usr/bin/env python3
"""assets/soundpacks/basic 의 WAV 목록의 WAV를 검증한다. 실패하면 종료 코드 1."""
import math
import os
import sys
import wave
import struct

HERE = os.path.dirname(os.path.abspath(__file__))
PACK = os.path.normpath(os.path.join(HERE, "..", "..", "assets", "soundpacks", "basic"))

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


def main():
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
        if name in PITCH:
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
    print("PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
