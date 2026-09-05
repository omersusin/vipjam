#!/usr/bin/env python3
"""Fetch HD600 kernels + synthesize CC0 IR + emit .vdc + manifest. Stdlib only."""
import hashlib
import json
import math
import os
import random
import re
import struct
import sys
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
KERNELS = os.path.join(ROOT, "kernels")
HD600 = os.path.join(KERNELS, "hd600")
PIN = "7ae0f56d53074872b028649617a22bbb4232feb7"
BASE = f"https://raw.githubusercontent.com/jaakkopasanen/AutoEq/{PIN}/results/oratory1990/over-ear/Sennheiser%20HD%20600/"
FILES = [
    "Sennheiser HD 600 ParametricEQ.txt",
    "Sennheiser HD 600 FixedBandEQ.txt",
    "Sennheiser HD 600 minimum phase 44100Hz.wav",
    "Sennheiser HD 600 minimum phase 48000Hz.wav",
]
TIMEOUT = 30
CAP = 1024 * 1024
FAIL = []

FILTER_RE = re.compile(
    r"^Filter\s+(\d+):\s+(ON|OFF)\s+(LSC|HSC|PK)\s+Fc\s+([\d.]+)\s*Hz\s+Gain\s+(-?[\d.]+)\s*dB\s+Q\s+([\d.]+)",
    re.IGNORECASE,
)


def sha256_file(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for c in iter(lambda: f.read(65536), b""):
            h.update(c)
    return h.hexdigest()


def fetch_one(name):
    url = BASE + urllib.parse.quote(name)
    dest = os.path.join(HD600, name)
    req = urllib.request.Request(url, headers={"User-Agent": "vipjam-fetch/1.0"})
    with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
        data = r.read(CAP + 1)
    if len(data) > CAP:
        FAIL.append(f"{name}: exceeds 1MB cap ({len(data)} bytes)")
        return None
    if not data:
        FAIL.append(f"{name}: empty response")
        return None
    with open(dest, "wb") as f:
        f.write(data)
    return dest


def validate_txt(path):
    try:
        text = open(path, "r", encoding="utf-8", errors="strict").read()
    except Exception as e:
        FAIL.append(f"{os.path.basename(path)}: unreadable: {e}")
        return
    lines = text.splitlines()
    if not any(l.startswith("Preamp:") for l in lines):
        FAIL.append(f"{os.path.basename(path)}: missing Preamp: line")
    pat = re.compile(r"^(Preamp|-?\d|Filter \d+:)")
    for i, ln in enumerate(lines, 1):
        s = ln.strip()
        if not s:
            continue
        if not pat.match(s):
            FAIL.append(f"{os.path.basename(path)}:{i}: bad line: {s[:60]!r}")


def validate_wav(path):
    try:
        with open(path, "rb") as f:
            head = f.read(12)
            f.seek(0, 2)
            size = f.tell()
    except Exception as e:
        FAIL.append(f"{os.path.basename(path)}: unreadable: {e}")
        return
    if head[:4] != b"RIFF" or head[8:12] != b"WAVE":
        FAIL.append(f"{os.path.basename(path)}: missing RIFF/WAVE header: {head[:12]!r}")
    if size >= CAP:
        FAIL.append(f"{os.path.basename(path)}: >= 1MB ({size} bytes)")


def rbj(f0, gain, q, fs, kind):
    A = 10.0 ** (gain / 40.0)
    w0 = 2.0 * math.pi * f0 / fs
    cosw = math.cos(w0)
    sinw = math.sin(w0)
    if kind == "PK":
        alpha = sinw / (2.0 * q)
        b0 = 1.0 + alpha * A
        b1 = -2.0 * cosw
        b2 = 1.0 - alpha * A
        a0 = 1.0 + alpha / A
        a1 = -2.0 * cosw
        a2 = 1.0 - alpha / A
    else:
        S = 1.0
        alpha = sinw / 2.0 * math.sqrt((A + 1.0 / A) * (1.0 / S - 1.0) + 2.0)
        beta = 2.0 * math.sqrt(A) * alpha
        if kind == "LSC":
            b0 = A * ((A + 1.0) - (A - 1.0) * cosw + beta)
            b1 = 2.0 * A * ((A - 1.0) - (A + 1.0) * cosw)
            b2 = A * ((A + 1.0) - (A - 1.0) * cosw - beta)
            a0 = (A + 1.0) + (A - 1.0) * cosw + beta
            a1 = -2.0 * ((A - 1.0) + (A + 1.0) * cosw)
            a2 = (A + 1.0) + (A - 1.0) * cosw - beta
        else:
            b0 = A * ((A + 1.0) + (A - 1.0) * cosw + beta)
            b1 = -2.0 * A * ((A - 1.0) + (A + 1.0) * cosw)
            b2 = A * ((A + 1.0) + (A - 1.0) * cosw - beta)
            a0 = (A + 1.0) - (A - 1.0) * cosw + beta
            a1 = 2.0 * ((A - 1.0) - (A + 1.0) * cosw)
            a2 = (A + 1.0) - (A - 1.0) * cosw - beta
    return [b0 / a0, b1 / a0, b2 / a0, -a1 / a0, -a2 / a0]


def parse_eq(path):
    filters = []
    for ln in open(path, encoding="utf-8").read().splitlines():
        m = FILTER_RE.match(ln.strip())
        if m and m.group(2).upper() == "ON":
            filters.append((m.group(3).upper(), float(m.group(4)), float(m.group(5)), float(m.group(6))))
    if not filters:
        FAIL.append(f"{os.path.basename(path)}: no ON LSC/PK/HSC filters parsed")
    return filters


def to_vdc(filters):
    out = {}
    for fs in (44100, 48000):
        sos = []
        for kind, f, g, q in filters:
            sos.extend(rbj(f, g, q if q > 0 else 0.7071, fs, kind))
        out[fs] = sos
    return out


def validate_vdc(path):
    try:
        text = open(path, encoding="utf-8").read()
    except Exception as e:
        FAIL.append(f"{os.path.basename(path)}: unreadable: {e}")
        return None, None
    if "SR_44100:" not in text or "SR_48000:" not in text:
        FAIL.append(f"{os.path.basename(path)}: missing SR_ tags")
        return None, None
    vals = {}
    for tag in ("SR_44100:", "SR_48000:"):
        seg = text.split(tag, 1)[1].splitlines()[0]
        seg = seg.split("SR_")[0]
        nums = [s.strip() for s in seg.replace(";", ",").split(",") if s.strip()]
        try:
            fv = [float(x) for x in nums]
        except ValueError:
            FAIL.append(f"{os.path.basename(path)}:{tag} non-float token")
            return None, None
        if not all(math.isfinite(v) for v in fv):
            FAIL.append(f"{os.path.basename(path)}:{tag} non-finite value")
        vals[tag] = fv
    a, b = vals["SR_44100:"], vals["SR_48000:"]
    if len(a) != len(b):
        FAIL.append(f"{os.path.basename(path)}: SR counts differ {len(a)} vs {len(b)}")
    if len(a) % 5 != 0 or len(a) == 0:
        FAIL.append(f"{os.path.basename(path)}: count %5 != 0 or empty ({len(a)})")
    return a, b


def final_repo_check(path):
    raw = open(path, "rb").read().decode("utf-8", errors="strict")
    i44 = raw.find("SR_44100")
    i48 = raw.find("SR_48000")
    if i44 < 0 or i48 < 0:
        return False, "missing SR tag (DDCParser rule)"
    n = raw[i48:].count(",") + 1
    sos = n // 5
    if sos <= 0 or n % 5 != 0:
        return False, f"sosCount invalid n={n}"
    return True, f"sos={sos} n={n}"


def synth_ir():
    N, seed, peak = 256, 7, 0.5
    rng = random.Random(seed)
    frames = []
    for i in range(N):
        d = math.exp(-4.0 * i / N)
        frames.append(rng.uniform(-1.0, 1.0) * d)
        frames.append(rng.uniform(-1.0, 1.0) * d)
    m = max(abs(v) for v in frames) or 1.0
    frames = [v / m * peak for v in frames]
    dest = os.path.join(KERNELS, "synth-room-cc0.irs")
    with open(dest, "wb") as f:
        f.write(struct.pack("<%df" % len(frames), *frames))
    spec = {
        "generator": "seeded exp-decay stereo noise",
        "seed": seed,
        "frames": N,
        "channels": 2,
        "layout": "interleaved stereo LRLR, raw float32 LE, no WAV header",
        "decay": "exp(-4.0*i/N)",
        "distribution": "uniform(-1,1) via random.Random(seed)",
        "normalize_peak": peak,
        "note": "Raw float32 LE samples, NOT a WAV file: no RIFF header by design.",
    }
    return dest, spec


def main():
    os.makedirs(HD600, exist_ok=True)
    for name in FILES:
        try:
            p = fetch_one(name)
        except Exception as e:
            FAIL.append(f"{name}: fetch failed: {e}")
            continue
        if p is None:
            continue
        if p.endswith(".txt"):
            validate_txt(p)
        else:
            validate_wav(p)
    vdc_map = {}
    for src, dst in [
        ("Sennheiser HD 600 ParametricEQ.txt", "hd600-parametric.vdc"),
        ("Sennheiser HD 600 FixedBandEQ.txt", "hd600-fixedband.vdc"),
    ]:
        sp = os.path.join(HD600, src)
        dp = os.path.join(HD600, dst)
        if os.path.exists(sp):
            flt = parse_eq(sp)
            v = to_vdc(flt)
            with open(dp, "w", encoding="utf-8") as f:
                f.write("SR_44100:" + ",".join(f"{x:.9g}" for x in v[44100]) + "\n")
                f.write("SR_48000:" + ",".join(f"{x:.9g}" for x in v[48000]) + "\n")
            validate_vdc(dp)
            ok, msg = final_repo_check(dp)
            if not ok:
                FAIL.append(f"{dst}: repo check failed: {msg}")
            vdc_map[dst] = msg
        else:
            FAIL.append(f"{dst}: source {src} missing, skipped")
    ir_path, spec = synth_ir()
    all_files = []
    for dp, _, fns in os.walk(KERNELS):
        for fn in fns:
            if fn == "manifest.json":
                continue
            all_files.append(os.path.join(dp, fn))
    sha = {os.path.relpath(p, KERNELS): sha256_file(p) for p in sorted(all_files)}

    def entry(name, typ, rels, lic, src):
        return {
            "name": name,
            "type": typ,
            "files": rels,
            "sha256": {r: sha[r] for r in rels if r in sha},
            "license": lic,
            "source": src,
        }

    manifest = {
        "pin": PIN,
        "base": BASE,
        "kernels": [
            entry(
                "hd600",
                "DDC",
                sorted([r for r in sha if r.startswith("hd600/")]),
                "MIT (AutoEq) + oratory1990 measurement",
                BASE,
            ),
            entry(
                "synth-room-cc0",
                "IR",
                ["synth-room-cc0.irs"],
                "CC0 (synthesized)",
                "synthesized",
            ),
        ],
        "synth_spec": spec,
    }
    with open(os.path.join(KERNELS, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2)
        f.write("\n")
    print("== kernels ==")
    for r in sorted(sha):
        sz = os.path.getsize(os.path.join(KERNELS, r))
        print(f"{'PASS' if not FAIL else 'FAIL'} {r} {sz}B sha256:{sha[r][:12]}")
    for dst, msg in vdc_map.items():
        print(f"{'PASS' if not FAIL else 'FAIL'} {dst} repo-check: {msg}")
    if FAIL:
        print("FAILURES:")
        for m in FAIL:
            print(f"  FAIL: {m}")
        sys.exit(1)
    print("ALL PASS")


if __name__ == "__main__":
    main()
