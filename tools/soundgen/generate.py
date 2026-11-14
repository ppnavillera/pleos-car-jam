#!/usr/bin/env python3
"""P-JAM basic 사운드팩 합성기.

표준 라이브러리만 사용한다. 모든 소리는 수식(오실레이터, 노이즈, 엔벨로프,
간단한 필터)으로 만들며 난수는 시드를 고정해 실행할 때마다 결과가 같다.

실행: python3 tools/soundgen/generate.py
출력: assets/soundpacks/basic/<sampleId>.wav (48kHz / 16bit / mono)
"""
import math
import os
import random
import struct
import wave

SR = 48000
PEAK = 0.85            # 정규화 목표 피크 (0.9 이하)
FADE_IN_S = 0.001      # 시작을 0에서 시작시키는 짧은 페이드인
FADE_OUT_S = 0.010     # 끝 클릭 방지 페이드아웃
TWO_PI = 2.0 * math.pi

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                       "..", "..", "assets", "soundpacks", "basic")


# ---------------------------------------------------------------- 기본 도구

def n_samples(seconds):
    return int(round(seconds * SR))


def make_rng(name):
    """샘플 이름마다 고정된 시드의 난수 생성기."""
    return random.Random(sum(ord(c) * (i + 1) for i, c in enumerate(name)) + 12345)


def noise(rng, n):
    return [rng.uniform(-1.0, 1.0) for _ in range(n)]


def exp_decay(n, tau_s):
    """exp(-t/tau) 엔벨로프."""
    return [math.exp(-(i / SR) / tau_s) for i in range(n)]


def attack_env(n, attack_s):
    """선형 어택 램프 (끝은 1)."""
    a = max(1, n_samples(attack_s))
    return [min(1.0, i / a) for i in range(n)]


def mul(a, b):
    return [x * y for x, y in zip(a, b)]


def add(*signals):
    n = max(len(s) for s in signals)
    out = [0.0] * n
    for s in signals:
        for i, v in enumerate(s):
            out[i] += v
    return out


def scale(sig, g):
    return [v * g for v in sig]


def sine_sweep(n, f_start, f_end, tau_s):
    """주파수가 f_start -> f_end 로 지수적으로 수렴하는 사인 (위상 누적)."""
    out, phase = [], 0.0
    for i in range(n):
        f = f_end + (f_start - f_end) * math.exp(-(i / SR) / tau_s)
        phase += TWO_PI * f / SR
        out.append(math.sin(phase))
    return out


def sine_tone(n, freq, amp=1.0):
    return [amp * math.sin(TWO_PI * freq * i / SR) for i in range(n)]


def lowpass(sig, cutoff):
    """1-pole 로우패스. cutoff는 Hz 숫자 또는 샘플별 리스트."""
    out, y = [], 0.0
    for i, x in enumerate(sig):
        fc = cutoff[i] if isinstance(cutoff, list) else cutoff
        a = 1.0 - math.exp(-TWO_PI * fc / SR)
        y += a * (x - y)
        out.append(y)
    return out


def highpass(sig, cutoff):
    """입력에서 로우패스 성분을 뺀 1-pole 하이패스."""
    lp = lowpass(sig, cutoff)
    return [x - l for x, l in zip(sig, lp)]


def bandpass(sig, lo, hi):
    return lowpass(highpass(sig, lo), hi)


def finalize(sig):
    """페이드인/아웃 적용 후 피크 PEAK로 정규화, int16 리스트 반환."""
    n = len(sig)
    fi, fo = n_samples(FADE_IN_S), n_samples(FADE_OUT_S)
    sig = list(sig)
    for i in range(min(fi, n)):
        sig[i] *= i / fi
    for i in range(min(fo, n)):
        sig[n - 1 - i] *= i / fo
    sig[0] = 0.0
    sig[-1] = 0.0
    peak = max(abs(v) for v in sig) or 1.0
    g = PEAK / peak
    return [max(-32767, min(32767, int(round(v * g * 32767)))) for v in sig]


def write_wav(name, pcm):
    os.makedirs(OUT_DIR, exist_ok=True)
    path = os.path.join(OUT_DIR, name + ".wav")
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(struct.pack("<%dh" % len(pcm), *pcm))


# ---------------------------------------------------------------- 드럼

def drum_kick(name):
    n = n_samples(0.5)
    body = mul(sine_sweep(n, 160.0, 48.0, 0.03), exp_decay(n, 0.13))
    rng = make_rng(name)
    click = mul(lowpass(noise(rng, n), 3000), exp_decay(n, 0.004))
    return add(body, scale(click, 0.25))


def drum_snare(name):
    n = n_samples(0.3)
    rng = make_rng(name)
    snap = mul(highpass(noise(rng, n), 1500), exp_decay(n, 0.07))
    tone = mul(add(sine_tone(n, 185, 1.0), sine_tone(n, 330, 0.4)), exp_decay(n, 0.05))
    return add(scale(snap, 0.8), scale(tone, 0.6))


def drum_hihat(name):
    n = n_samples(0.15)
    rng = make_rng(name)
    hiss = highpass(highpass(noise(rng, n), 6000), 6000)
    return mul(hiss, exp_decay(n, 0.025))


def drum_clap(name):
    n = n_samples(0.25)
    rng = make_rng(name)
    raw = bandpass(noise(rng, n), 900, 3500)
    env = [0.0] * n
    for k, t in enumerate((0.0, 0.011, 0.022)):          # 짧은 3연타
        s = n_samples(t)
        for i in range(s, min(n, s + n_samples(0.012))):
            env[i] = max(env[i], math.exp(-((i - s) / SR) / 0.004))
    tail = exp_decay(n, 0.06)
    tail_start = n_samples(0.03)
    for i in range(tail_start, n):                        # 잔향 꼬리
        env[i] = max(env[i], 0.6 * tail[i - tail_start])
    return mul(raw, env)


# ---------------------------------------------------------------- 베이스

BASS_FREQS = {  # (도수, 옥타브) -> Hz
    (1, 1): 65.41, (4, 1): 87.31, (5, 1): 98.00,
    (1, 2): 130.81, (4, 2): 174.61, (5, 2): 196.00,
    (1, 3): 261.63, (4, 3): 349.23, (5, 3): 392.00,
}


def bass_note(freq, dur=0.85):
    """일렉 베이스 느낌: 사인 + 약한 2,3배음, 짧은 어택, 감쇠."""
    n = n_samples(dur)
    tone = add(sine_tone(n, freq, 1.0),
               sine_tone(n, freq * 2, 0.30),
               sine_tone(n, freq * 3, 0.12))
    env = mul(attack_env(n, 0.006), exp_decay(n, 0.30))
    # 배음은 더 빨리 사라지도록 전체 감쇠에 약한 로우패스를 얹는다.
    return lowpass(mul(tone, env), freq * 6)


# ---------------------------------------------------------------- 멜로디

MEL_FREQS = [261.63, 293.66, 329.63, 392.00, 440.00, 523.25, 587.33, 659.25]


def mel_note(freq, dur=0.9):
    """벨/마림바 느낌: 사인 + 비조화 배음(각자 다른 감쇠)."""
    n = n_samples(dur)
    partials = ((1.0, 1.00, 0.35), (2.76, 0.40, 0.18), (5.40, 0.18, 0.08))
    parts = []
    for ratio, amp, tau in partials:
        f = freq * ratio
        if f < SR / 2 * 0.9:
            parts.append(mul(sine_tone(n, f, amp), exp_decay(n, tau)))
    return mul(add(*parts), attack_env(n, 0.002))


# ---------------------------------------------------------------- FX

def fx_impact(name):
    n = n_samples(0.9)
    rng = make_rng(name)
    boom = mul(sine_sweep(n, 110.0, 38.0, 0.12), exp_decay(n, 0.28))
    burst = mul(lowpass(noise(rng, n), 1800), exp_decay(n, 0.08))
    return add(boom, scale(burst, 0.5))


def fx_riser(name):
    n = n_samples(0.98)
    rng = make_rng(name)
    cutoff = [300.0 * (12000.0 / 300.0) ** (i / n) for i in range(n)]
    hiss = lowpass(noise(rng, n), cutoff)
    out, phase = [], 0.0
    for i in range(n):                                    # 200 -> 1600Hz 지수 상승
        phase += TWO_PI * (200.0 * 8.0 ** (i / n)) / SR
        out.append(math.sin(phase))
    rise = [(i / n) ** 1.5 for i in range(n)]
    return mul(add(scale(hiss, 0.8), scale(out, 0.4)), rise)


def fx_sweep(name):
    n = n_samples(0.8)
    rng = make_rng(name)
    # 컷오프가 200Hz -> 8kHz -> 200Hz 로 오르내린다.
    cutoff = [200.0 * 40.0 ** math.sin(math.pi * i / n) for i in range(n)]
    sig = lowpass(lowpass(noise(rng, n), cutoff), cutoff)   # 2단으로 더 가파르게
    env = [math.sin(math.pi * i / n) ** 0.7 for i in range(n)]
    return mul(sig, env)


def fx_zap(name):
    n = n_samples(0.25)
    out, phase = [], 0.0
    for i in range(n):                                    # 2400 -> 180Hz 하강
        f = 180.0 + (2400.0 - 180.0) * math.exp(-(i / SR) / 0.05)
        phase += TWO_PI * f / SR
        out.append(math.sin(phase) + 0.4 * math.sin(2 * phase) + 0.2 * math.sin(3 * phase))
    return mul(out, exp_decay(n, 0.09))


# ---------------------------------------------------------------- 메트로놈

def metro_click(freq, dur=0.12):
    n = n_samples(dur)
    return mul(sine_tone(n, freq, 1.0), exp_decay(n, 0.018))


# ---------------------------------------------------------------- 목록 / 실행

def build_all():
    """{sampleId: float 신호} 33개를 만든다."""
    pack = {
        "drum_kick": drum_kick("drum_kick"),
        "drum_snare": drum_snare("drum_snare"),
        "drum_hihat": drum_hihat("drum_hihat"),
        "drum_clap": drum_clap("drum_clap"),
    }
    for (degree, octave), f in sorted(BASS_FREQS.items(), key=lambda kv: (kv[0][0], kv[0][1])):
        pack["bass_%d_o%d" % (degree, octave)] = bass_note(f)
    for i, f in enumerate(MEL_FREQS):
        pack["mel_%d" % i] = mel_note(f)
    pack["fx_impact"] = fx_impact("fx_impact")
    pack["fx_riser"] = fx_riser("fx_riser")
    pack["fx_sweep"] = fx_sweep("fx_sweep")
    pack["fx_zap"] = fx_zap("fx_zap")
    pack["metro_hi"] = metro_click(1500.0)
    pack["metro_lo"] = metro_click(1000.0)
    return pack


def main():
    pack = build_all()
    for name, sig in pack.items():
        write_wav(name, finalize(sig))
    print("wrote %d samples to %s" % (len(pack), os.path.normpath(OUT_DIR)))


if __name__ == "__main__":
    main()
