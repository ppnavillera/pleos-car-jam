#!/usr/bin/env python3
"""P-JAM 차량 사운드팩 합성기 (T3-5). 차에서만 나는 소리를 악기로 쓴다.

표준 라이브러리만 사용하고 모든 소리를 수식으로 직접 합성한다. 난수 시드가 고정이라 결과가 항상 같다.
실제 경적을 울리거나 차량을 제어하지 않는다 (소리는 WAV 파일일 뿐이다). 외부 샘플을 쓰지 않는다.
sampleId는 basic 팩과 똑같다(앱 코드가 이름에 의존한다). 같은 이름의 소리가 팩마다 다른 악기가 된다.

  drum_kick   도어 닫히는 소리: 낮은 "쿵" + 래치 "탁"
  drum_snare  트렁크 닫히는 소리: 더 밝고 긴 "쾅"
  drum_hihat  방향지시등 릴레이 "똑" (짧고 밝은 클릭)
  drum_clap   안전벨트 버클 "찰칵" (금속 클릭 두 번)
  bass_*      경적 음색(두 소리가 살짝 어긋나 울리는 소리)으로 음높이만 바꾼 것. 짧고 부드럽게 다듬음
  mel_*       같은 경적 음색, 높은 음역, 더 짧고 부드럽게
  fx_impact   경적 한 번 (짧게 다듬음)
  fx_riser    시동음: 크랭킹 → 시동이 걸려 공회전
  fx_sweep    경고 차임: 도어 열림 경고 같은 "딩-동" 두 음
  fx_zap      후방 감지 경고음 "삑삑"
  metro_hi/lo 방향지시등 릴레이 "딱"(강박) / "똑"(약박)

실행: python3 tools/soundgen/generate_car.py
출력: assets/soundpacks/car/<sampleId>.wav (48kHz / 16bit / mono)
"""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import generate as g  # noqa: E402  (basic 팩 합성기의 기본 도구를 그대로 쓴다)

g.OUT_DIR = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "assets", "soundpacks", "car"))

SR, TWO_PI = g.SR, g.TWO_PI
n_samples, mul, add, scale, exp_decay, attack_env = g.n_samples, g.mul, g.add, g.scale, g.exp_decay, g.attack_env
noise, lowpass, highpass, bandpass, sine_tone, sine_sweep, make_rng = g.noise, g.lowpass, g.highpass, g.bandpass, g.sine_tone, g.sine_sweep, g.make_rng

SOFT_PEAK = 0.62   # 경적 계열은 귀에 거슬리지 않게 basic 팩(0.85)보다 작게 정규화한다


def delayed(sig, seconds, total):
    """sig를 seconds만큼 늦춰 길이 total(초)의 버퍼에 놓는다."""
    n = n_samples(total)
    s = n_samples(seconds)
    out = [0.0] * n
    for i, v in enumerate(sig):
        if s + i < n:
            out[s + i] = v
    return out


# ---------------------------------------------------------------- 드럼

def drum_kick(name):
    """도어 닫힘: 낮은 몸통 + 약간 늦게 오는 래치 클릭."""
    n = n_samples(0.4)
    rng = make_rng(name)
    body = mul(sine_sweep(n, 130.0, 58.0, 0.025), exp_decay(n, 0.09))
    thud = mul(lowpass(noise(rng, n), 450), exp_decay(n, 0.05))
    latch = delayed(mul(bandpass(noise(rng, n_samples(0.05)), 1800, 4200), exp_decay(n_samples(0.05), 0.006)), 0.028, 0.4)
    return add(body, scale(thud, 0.55), scale(latch, 0.5))


def drum_snare(name):
    """트렁크 닫힘: 더 밝은 몸통 + 넓은 대역 노이즈 꼬리."""
    n = n_samples(0.32)
    rng = make_rng(name)
    body = mul(sine_sweep(n, 220.0, 110.0, 0.03), exp_decay(n, 0.07))
    slam = mul(bandpass(noise(rng, n), 300, 3800), exp_decay(n, 0.075))
    rattle = mul(highpass(noise(rng, n), 3000), exp_decay(n, 0.03))
    return add(scale(body, 0.6), slam, scale(rattle, 0.25))


def relay_tick(name, freq, ring_tau, dur=0.09):
    """방향지시등 릴레이: 아주 짧은 클릭 + 공명 링."""
    n = n_samples(dur)
    rng = make_rng(name)
    click = mul(bandpass(noise(rng, n), 1200, 6000), exp_decay(n, 0.0022))
    ring = mul(sine_tone(n, freq, 1.0), exp_decay(n, ring_tau))
    return add(scale(click, 0.9), scale(ring, 0.55))


def drum_hihat(name):
    return relay_tick(name, 3100.0, 0.007, 0.08)


def drum_clap(name):
    """안전벨트 버클 찰칵: 금속 클릭이 아주 짧게 두 번."""
    n = n_samples(0.22)
    rng = make_rng(name)
    def click(seed_name):
        m = n_samples(0.05)
        r = make_rng(seed_name)
        return add(mul(bandpass(noise(r, m), 2000, 7000), exp_decay(m, 0.003)),
                   scale(mul(sine_tone(m, 3700, 1.0), exp_decay(m, 0.012)), 0.4))
    return add(delayed(click(name + "a"), 0.0, 0.22), scale(delayed(click(name + "b"), 0.045, 0.22), 0.8),
               scale(delayed(mul(lowpass(noise(rng, n_samples(0.08)), 700), exp_decay(n_samples(0.08), 0.03)), 0.05, 0.22), 0.25))


# ---------------------------------------------------------------- 경적 음색 (베이스·멜로디)

FORMANT_HZ = 720.0     # 경적의 코맹맹이 같은 공명 대역
FORMANT_WIDTH = 420.0


def horn_voice(freq, n, detune):
    """경적 한 소리: 배음이 많되 공명 대역 근처가 강한 가산 합성. detune은 두 소리가 어긋나 울리는 정도."""
    f0 = freq * (1.0 + detune)
    out = [0.0] * n
    k = 1
    while k * f0 < 4200 and k <= 24:
        fk = k * f0
        weight = (0.45 if k == 1 else 0.0) + math.exp(-(((fk - FORMANT_HZ) / FORMANT_WIDTH) ** 2)) * (1.0 / math.sqrt(k)) + 0.08 / k
        w = TWO_PI * fk / SR
        for i in range(n):
            out[i] += weight * math.sin(w * i)
        k += 1
    return out


def horn_note(freq, dur, attack, tau, soft_cut):
    """짧고 부드러운 경적 음: 두 소리(±0.6%)가 어긋나 울리고, 어택은 부드럽고, 로우패스로 날카로움을 깎는다."""
    n = n_samples(dur)
    tone = add(horn_voice(freq, n, -0.006), horn_voice(freq, n, +0.006))
    env = mul(attack_env(n, attack), exp_decay(n, tau))
    return lowpass(lowpass(mul(tone, env), soft_cut), soft_cut)


def bass_note(freq):
    return horn_note(freq, 0.5, 0.014, 0.17, 1500)


def mel_note(freq):
    return horn_note(freq, 0.42, 0.010, 0.13, 2300)


# ---------------------------------------------------------------- FX

def fx_impact(name):
    """경적 한 번 (두 음을 같이 울리되 짧게 다듬음)."""
    n = n_samples(0.6)
    tone = add(horn_voice(392.0, n, -0.004), horn_voice(494.0, n, +0.004))
    env = mul(attack_env(n, 0.02), [min(1.0, math.exp(-max(0.0, (i / SR) - 0.22) / 0.09)) for i in range(n)])
    return lowpass(lowpass(mul(tone, env), 2400), 2400)


def fx_riser(name):
    """시동음: 크랭킹(점점 빨라지는 펄스) → 엔진이 걸려 낮게 공회전."""
    total = 0.98
    n = n_samples(total)
    rng = make_rng(name)
    out = [0.0] * n
    t, period = 0.0, 0.16
    while t < 0.62:                                   # 크랭킹: 펄스 간격이 줄어든다
        s = n_samples(t)
        m = n_samples(0.07)
        pulse = mul(add(lowpass(noise(rng, m), 500), scale(sine_tone(m, 62 + 40 * (t / 0.62), 1.0), 0.8)), exp_decay(m, 0.02))
        for i, v in enumerate(pulse):
            if s + i < n:
                out[s + i] += v * (0.55 + 0.45 * t / 0.62)
        t += period
        period = max(0.07, period * 0.86)
    idle_start = n_samples(0.6)                       # 시동 걸림: 낮은 공회전 럼블
    m = n - idle_start
    rumble = [0.0] * m
    for k, (f, a) in enumerate(((46.0, 1.0), (92.0, 0.7), (138.0, 0.35))):
        for i in range(m):
            rumble[i] += a * math.sin(TWO_PI * f * (i / SR) * (1.0 + 0.02 * math.sin(TWO_PI * 5.5 * i / SR)))
    rumble = mul(lowpass(rumble, 260), mul(attack_env(m, 0.08), exp_decay(m, 0.3)))
    for i, v in enumerate(rumble):
        out[idle_start + i] += v * 0.9
    return out


def chime_tone(freq, dur=0.5):
    n = n_samples(dur)
    parts = add(sine_tone(n, freq, 1.0), sine_tone(n, freq * 2.0, 0.25), sine_tone(n, freq * 3.01, 0.08))
    return mul(parts, mul(attack_env(n, 0.003), exp_decay(n, 0.16)))


def fx_sweep(name):
    """경고 차임: 도어 열림 경고 같은 "딩-동" (높은 음 → 낮은 음)."""
    total = 0.9
    return add(delayed(chime_tone(1175.0), 0.0, total), scale(delayed(chime_tone(880.0, 0.6), 0.28, total), 0.9))


def fx_zap(name):
    """후방 감지 경고음 "삑삑"."""
    total = 0.3
    n = n_samples(0.09)
    beep = mul(sine_tone(n, 1050.0, 1.0), [min(1.0, i / 120.0, (n - i) / 240.0) for i in range(n)])
    return add(delayed(beep, 0.0, total), delayed(beep, 0.14, total))


# ---------------------------------------------------------------- 메트로놈

def metro_hi(name):
    return relay_tick(name, 2600.0, 0.012, 0.1)      # 딱 (높고 또렷)


def metro_lo(name):
    return relay_tick(name, 1700.0, 0.014, 0.1)      # 똑 (조금 낮고 부드러움)


# ---------------------------------------------------------------- 목록 / 실행

def build_all():
    """{sampleId: (float 신호, 목표 피크)} 27개."""
    pack = {
        "drum_kick": (drum_kick("drum_kick"), g.PEAK),
        "drum_snare": (drum_snare("drum_snare"), g.PEAK),
        "drum_hihat": (drum_hihat("drum_hihat"), 0.8),
        "drum_clap": (drum_clap("drum_clap"), 0.8),
    }
    for (degree, octave), f in sorted(g.BASS_FREQS.items(), key=lambda kv: (kv[0][0], kv[0][1])):
        pack["bass_%d_o%d" % (degree, octave)] = (bass_note(f), SOFT_PEAK)
    for i, f in enumerate(g.MEL_FREQS):
        pack["mel_%d" % i] = (mel_note(f), SOFT_PEAK)
    pack["fx_impact"] = (fx_impact("fx_impact"), SOFT_PEAK)
    pack["fx_riser"] = (fx_riser("fx_riser"), 0.75)
    pack["fx_sweep"] = (fx_sweep("fx_sweep"), 0.7)
    pack["fx_zap"] = (fx_zap("fx_zap"), 0.65)
    pack["metro_hi"] = (metro_hi("metro_hi"), 0.8)
    pack["metro_lo"] = (metro_lo("metro_lo"), 0.7)
    return pack


def main():
    pack = build_all()
    for name, (sig, peak) in pack.items():
        g.PEAK = peak
        g.write_wav(name, g.finalize(sig))
    print("wrote %d samples to %s" % (len(pack), g.OUT_DIR))


if __name__ == "__main__":
    main()
