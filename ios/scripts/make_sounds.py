#!/usr/bin/env python3
"""Generate PyroChat terminal blips. Stdlib only. Run once at build setup,
BEFORE `xcodegen generate` (files must exist to be picked up)."""
import math, os, struct, wave

RATE = 44_100
OUT = os.path.join(os.path.dirname(__file__), "..", "PyroChat", "Resources", "Sounds")

def soft_square(phase, drive=1.6):
    """Overdriven, clamped sine: square-ish with tame harmonics."""
    return max(-1.0, min(1.0, drive * math.sin(phase)))

def tone(freq, dur_s, peak, attack_s=0.002, decay_tau=0.012, drive=1.6):
    n = int(RATE * dur_s)
    out = []
    for i in range(n):
        t = i / RATE
        env = min(1.0, t / attack_s) * math.exp(-t / decay_tau)
        out.append(peak * env * soft_square(2 * math.pi * freq * t, drive))
    return out

def silence(dur_s):
    return [0.0] * int(RATE * dur_s)

def write(name, samples):
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, name)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(
            struct.pack("<h", int(max(-1.0, min(1.0, s)) * 32_767)) for s in samples))
    print(f"wrote {path} ({len(samples)/RATE*1000:.0f} ms)")

# TX: single crisp square-ish tick — 1400 Hz, 55 ms + 5 ms tail = 60 ms
write("tx.wav", tone(1400, 0.055, peak=0.50, decay_tau=0.012) + silence(0.005))

# RX: two-tone blip — C6 then G6, 130 ms total, softer drive, longer ring
rx = (tone(1046.5, 0.055, peak=0.45, decay_tau=0.025, drive=1.3)
      + silence(0.005)
      + tone(1568.0, 0.065, peak=0.45, decay_tau=0.025, drive=1.3)
      + silence(0.005))
write("rx.wav", rx)
