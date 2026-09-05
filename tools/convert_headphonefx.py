#!/usr/bin/env python3
"""Convert ViPER4Android 2.x headphonefx.* SharedPrefs XML to VipJam v3.

Input: `<map>` XML with `viper4android.headphonefx.*` keys (e.g. LoongFX).
Output: v3 JSON (schemaVersion 3, origin viper), v2-group layout.

Scale notes (v2.7 named keys -> v2 grouped values):
- reverb roomsize/roomwidth/damp: //10 when > 10 (45 -> 4)
- fieldSurround widening: first coeff rescales like roomsize (180 -> 6)
- diffSurround delay ms: //100 clamped 1..20 (2000 -> 20)
- spectrumExtension strength: 2200 + value * 6000 (0.59 -> 5740)
- masterLimiter outputVolume: forced 100 (source outvol is a listening
  level, not preset character)
- missing kernelFile prints a warning (driver has no such kernel yet).

Examples:
    convert_headphonefx.py preset.xml -o preset.v3.json --name LoongFX-Movie
"""

from __future__ import annotations

import argparse
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any

P = "viper4android.headphonefx."


def _room(v: Any) -> int:
    n = _int(v, 0)
    return n // 10 if n > 10 else n


def _int(v: Any, default: int = 0) -> int:
    try:
        return int(float(str(v).strip()))
    except (ValueError, TypeError):
        return default


def _float(v: Any, default: float = 0.0) -> float:
    try:
        return float(str(v).strip())
    except (ValueError, TypeError):
        return default


def _bool(v: Any) -> bool:
    return str(v).strip().lower() == "true"


def parse_prefs(text: str) -> dict[str, Any]:
    root = ET.fromstring(text)
    prefs: dict[str, Any] = {}
    for el in root.iter():
        name = el.get("name")
        if name is None or el.tag not in ("int", "boolean", "string"):
            continue
        prefs[name] = el.text or "" if el.tag == "string" else el.get("value") == "true"
    return prefs


def convert(prefs: dict[str, Any], name: str) -> dict[str, Any]:
    g = lambda k, d="": prefs.get(P + k, d)  # noqa: E731
    gb = lambda k: _bool(prefs.get(P + k, False))  # noqa: E731

    widen_raw = str(g("colorfulmusic.coeffs", "0")).split(";")
    widen = _room(widen_raw[0]) if widen_raw else 0
    ds_parts = [_int(x) for x in str(g("dynamicsystem.coeffs", "")).split(";")]
    ds_parts += [0] * (6 - len(ds_parts))
    bands = [x for x in str(g("fireq.custom", "")).split(";") if x.strip() != ""]
    delay = max(1, min(20, _int(g("diffsurr.delay", 20)) // 100))

    return {
        "schemaVersion": 3,
        "origin": "viper",
        "name": name,
        "masterEnable": gb("enable"),
        "masterLimiter": {
            "threshold": _int(g("limiter", 100)),
            "outputVolume": 100,
            "channelPan": _int(g("channelpan", 0)),
        },
        "playbackGainControl": {
            "enable": gb("playbackgain.enable"),
            "strength": _int(g("playbackgain.ratio", 100)),
            "maxGain": _int(g("playbackgain.maxscaler", 100)),
            "outputThreshold": _int(g("playbackgain.volume", 100)),
        },
        "fetCompressor": {"enable": gb("fetcompressor.enable")},
        "ddc": {"enable": gb("viperddc.enable"), "device": ""},
        "spectrumExtension": {
            "enable": gb("vse.enable"),
            "strength": int(2200 + _float(g("vse.value", 0)) * 6000),
            "exciter": 0,
        },
        "equalizer": {
            "enable": gb("fireq.enable"),
            "bandCount": len(bands),
            "bands": [_float(b) for b in bands],
            "presetId": None,
        },
        "convolver": {
            "enable": gb("convolver.enable"),
            "kernelFile": Path(str(g("convolver.kernel", ""))).name,
            "crossChannel": _int(g("convolver.crosschannel", 0)),
        },
        "fieldSurround": {
            "enable": gb("colorfulmusic.enable"),
            "widening": widen,
            "midImage": _room(g("colorfulmusic.midimage", 5)),
            "depth": 0,
        },
        "diffSurround": {
            "enable": gb("diffsurr.enable"),
            "delay": delay,
            "reverse": False,
            "wetDryMix": 100,
            "lpCutoff": 0,
        },
        "headphoneSurround": {
            "enable": gb("vhs.enable"),
            "quality": _int(g("vhs.qual", 0)),
        },
        "reverb": {
            "enable": gb("reverb.enable"),
            "roomSize": _room(g("reverb.roomsize", 0)),
            "width": _room(g("reverb.roomwidth", 0)),
            "damp": _room(g("reverb.damp", 0)),
            "wet": _int(g("reverb.wet", 0)),
            "dry": _int(g("reverb.dry", 50)),
        },
        "dynamicSystem": {
            "enable": gb("dynamicsystem.enable"),
            "presetId": None,
            "device": 0,
            "strength": _int(g("dynamicsystem.bass", 50)),
            "xLow": ds_parts[0],
            "xHigh": ds_parts[1],
            "yLow": ds_parts[2],
            "yHigh": ds_parts[3],
            "sideGainLow": ds_parts[4],
            "sideGainHigh": ds_parts[5],
        },
        "bass": {
            "enable": gb("fidelity.bass.enable"),
            "mode": _int(g("fidelity.bass.mode", 0)),
            "frequency": _int(g("fidelity.bass.freq", 55)),
            "gain": _int(g("fidelity.bass.gain", 50)),
            "antiPop": True,
        },
        "clarity": {
            "enable": gb("fidelity.clarity.enable"),
            "mode": _int(g("fidelity.clarity.mode", 0)),
            "gain": _int(g("fidelity.clarity.gain", 50)),
        },
        "cure": {
            "enable": gb("cure.enable"),
            "crossfeedPreset": _int(g("cure.crossfeed", 0)),
        },
        "tubeSimulator": {"enable": False},
        "analogX": {
            "enable": gb("analogx.enable"),
            "mode": _int(g("analogx.mode", 0)),
        },
        "speakerCorrection": {"enable": _bool(prefs.get("viper4android.speakerfx.spkopt.enable", False))},
    }


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(
        description="Convert V4A 2.x headphonefx XML presets to VipJam v3."
    )
    p.add_argument("input", type=Path)
    p.add_argument("-o", "--output", type=Path, default=None)
    p.add_argument("--name", default=None)
    args = p.parse_args(argv)

    text = args.input.read_text(encoding="utf-8")
    out = convert(
        parse_prefs(text), args.name or args.input.stem
    )
    kernel = out["convolver"]["kernelFile"]
    if out["convolver"]["enable"] and kernel:
        print(f"warning: kernel '{kernel}' not bundled", file=sys.stderr)
    rendered = json.dumps(out, indent=2, ensure_ascii=False)
    if args.output is not None:
        args.output.write_text(rendered + "\n", encoding="utf-8")
        print(f"wrote {args.output}", file=sys.stderr)
    else:
        print(rendered)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
