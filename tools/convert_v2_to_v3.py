#!/usr/bin/env python3
"""Convert ViPER v2 grouped JSON and/or JamesDSP SharedPreferences to VipJam v3.

v3 is the app's preset schema (VipJamEffects.SCHEMA_VERSION = 3): the v2
group layout passes through untouched, plus::

    schemaVersion: 3
    origin: viper | james | vipjam (merged)
    name, masterEnable
    james: { <james stage objects> }   (james/vipjam origins only)

JamesDSP input is a per-route SharedPreferences file (``james.dsp.<route>.xml``,
route auto-detected from the filename) or a flat JSON object of key->value.
File references (IR/DDC/LiveProg) are stored as basenames; stage the content
next to the preset per the roadmap layout (Kernel/, DDC/, LiveProg/).

No unit conversions are faked: each side keeps its native scales; the app and
the fused 0x200xx chain apply them per-engine.

Examples::

    convert_v2_to_v3.py viper_v2.json -o preset.v3.json
    convert_v2_to_v3.py james.dsp.headset.xml -o preset.v3.json
    convert_v2_to_v3.py viper_v2.json --merge james.dsp.headset.xml -o m.v3.json
    convert_v2_to_v3.py --self-test
"""

from __future__ import annotations

import argparse
import base64
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any

VIPER_GROUPS = {
    "masterLimiter", "playbackGainControl", "lufs", "fetCompressor",
    "multibandCompressor", "ddc", "spectrumExtension", "equalizer",
    "dynamicEq", "convolver", "fieldSurround", "diffSurround",
    "stereoImager", "headphoneSurround", "reverb", "dynamicSystem",
    "psychoacousticBass", "bass", "bassMono", "clarity", "cure",
    "tubeSimulator", "analogX", "speakerCorrection", "loudnessComp",
    "liveprog",
}

JAMES_STAGES = {
    "masterswitch", "compression", "bass", "tone", "streq", "convolver",
    "ddc", "liveprog", "tube", "stereowide", "bs2b", "headphone",
}

JAMES_DEFAULTS: dict[str, Any] = {
    "dsp.masterswitch.enable": False,
    "dsp.masterswitch.limthreshold": "-0.1",
    "dsp.masterswitch.limrelease": "60",
    "dsp.masterswitch.postgain": "0",
    "dsp.compression.enable": False,
    "dsp.compression.timeconstant": "0.22",
    "dsp.compression.granularity": "2",
    "dsp.compression.tfresolution": "0",
    "dsp.bass.enable": False,
    "dsp.bass.maxgain": "5",
    "dsp.tone.enable": False,
    "dsp.tone.filtertype": "3",
    "dsp.tone.interpolation": "0",
    "dsp.streq.enable": False,
    "dsp.streq.stringp": "GraphicEQ: 0.0 0.0;",
    "dsp.convolver.enable": False,
    "dsp.convolver.files": "",
    "dsp.ddc.enable": False,
    "dsp.ddc.files": "",
    "dsp.liveprog.enable": False,
    "dsp.liveprog.files": "",
    "dsp.analogmodelling.enable": False,
    "dsp.analogmodelling.tubedrive": "2",
    "dsp.stereowide.enable": False,
    "dsp.stereowide.mode": "60",
    "dsp.bs2b.enable": False,
    "dsp.bs2b.mode": "0",
    "dsp.headphone.enable": False,
    "dsp.headphone.preset": "15",
}


def _to_bool(v: Any) -> bool:
    if isinstance(v, bool):
        return v
    return str(v).strip().lower() == "true"


def _to_float(v: Any, default: float = 0.0) -> float:
    try:
        return float(str(v).strip())
    except (ValueError, TypeError):
        return default


def _to_int(v: Any, default: int = 0) -> int:
    try:
        return int(float(str(v).strip()))
    except (ValueError, TypeError):
        return default


def _float_list(raw: Any) -> list[float]:
    out: list[float] = []
    for tok in str(raw or "").split(";"):
        tok = tok.strip()
        if not tok:
            continue
        try:
            out.append(float(tok))
        except ValueError:
            out.append(0.0)
    return out


def _basename(p: Any) -> str:
    return Path(str(p)).name if p else ""


def parse_james_prefs(text: str) -> dict[str, Any]:
    stripped = text.lstrip()
    if stripped.startswith("<"):
        root = ET.fromstring(stripped)
        prefs: dict[str, Any] = {}
        for el in root.iter():
            name = el.get("name")
            if name is None:
                continue
            if el.tag == "boolean":
                prefs[name] = el.get("value") == "true"
            elif el.tag in ("int", "long", "float"):
                prefs[name] = el.get("value") if el.get("value") is not None else (el.text or "")
            elif el.tag == "string":
                prefs[name] = el.text or ""
        return prefs
    obj = json.loads(text)
    if not isinstance(obj, dict):
        raise ValueError("james json prefs must be an object")
    return obj


def james_to_v3(prefs: dict[str, Any], route: str | None = None) -> dict[str, Any]:
    g = lambda k: prefs.get(k, JAMES_DEFAULTS[k])  # noqa: E731

    def curve(raw: Any, custom: Any) -> tuple[list[float], list[float]]:
        vals = _float_list(custom if custom else raw)
        half = len(vals) // 2
        return vals[:half], vals[half:]

    comp_f, comp_g = curve(
        prefs.get("dsp.compression.eq", ""),
        prefs.get("dsp.compression.eq.custom", ""),
    )
    tone_f, tone_g = curve(
        prefs.get("dsp.tone.eq", ""), prefs.get("dsp.tone.eq.custom", "")
    )
    return {
        "masterswitch": {
            "limThreshold": _to_float(g("dsp.masterswitch.limthreshold"), -0.1),
            "limRelease": _to_float(g("dsp.masterswitch.limrelease"), 60.0),
            "postGain": _to_float(g("dsp.masterswitch.postgain")),
        },
        "compression": {
            "enable": _to_bool(g("dsp.compression.enable")),
            "timeConstant": _to_float(g("dsp.compression.timeconstant"), 0.22),
            "granularity": _to_float(g("dsp.compression.granularity"), 2.0),
            "tfResolution": _to_float(g("dsp.compression.tfresolution")),
            "freqs": comp_f,
            "gains": comp_g,
        },
        "bass": {
            "enable": _to_bool(g("dsp.bass.enable")),
            "maxGain": _to_int(g("dsp.bass.maxgain"), 5),
        },
        "tone": {
            "enable": _to_bool(g("dsp.tone.enable")),
            "filterType": _to_int(g("dsp.tone.filtertype"), 3),
            "interpolation": _to_int(g("dsp.tone.interpolation")),
            "freqs": tone_f,
            "gains": tone_g,
        },
        "streq": {
            "enable": _to_bool(g("dsp.streq.enable")),
            "graphicEq": str(g("dsp.streq.stringp")),
        },
        "convolver": {
            "enable": _to_bool(g("dsp.convolver.enable")),
            "kernelFile": _basename(g("dsp.convolver.files")),
        },
        "ddc": {
            "enable": _to_bool(g("dsp.ddc.enable")),
            "device": _basename(g("dsp.ddc.files")) or str(g("dsp.ddc.files")),
        },
        "liveprog": {
            "enable": _to_bool(g("dsp.liveprog.enable")),
            "scripts": [
                _basename(p)
                for p in str(g("dsp.liveprog.files")).split(";")
                if p.strip()
            ],
        },
        "tube": {
            "enable": _to_bool(g("dsp.analogmodelling.enable")),
            "drive": _to_float(g("dsp.analogmodelling.tubedrive"), 2.0),
        },
        "stereowide": {
            "enable": _to_bool(g("dsp.stereowide.enable")),
            "mode": _to_int(g("dsp.stereowide.mode"), 60),
        },
        "bs2b": {
            "enable": _to_bool(g("dsp.bs2b.enable")),
            "mode": _to_int(g("dsp.bs2b.mode")),
        },
        "headphone": {
            "enable": _to_bool(g("dsp.headphone.enable")),
            "preset": _to_int(g("dsp.headphone.preset"), 15),
        },
    }


def detect_route(path: Path) -> str | None:
    for route in ("headset", "speaker", "bluetooth"):
        if route in path.name:
            return route
    return None


def v2_to_v3(v2: dict[str, Any], *, name: str | None = None) -> dict[str, Any]:
    if v2.get("schemaVersion") == 3:
        return v2
    out: dict[str, Any] = {
        "schemaVersion": 3,
        "origin": "viper",
        "name": name or v2.get("name") or "imported",
        "masterEnable": True,
    }
    for key, val in v2.items():
        if key in ("schemaVersion", "name"):
            continue
        if key not in VIPER_GROUPS:
            raise ValueError(f"unknown v2 group: {key}")
        out[key] = val
    return out


def validate_v3(p: dict[str, Any]) -> list[str]:
    errs: list[str] = []
    if p.get("schemaVersion") != 3:
        errs.append("schemaVersion must be 3")
    if p.get("origin") not in ("viper", "james", "vipjam"):
        errs.append("origin must be viper|james|vipjam")
    if not p.get("name"):
        errs.append("name must be non-empty")
    for key in p:
        if key in ("schemaVersion", "origin", "name", "masterEnable", "route"):
            continue
        if key == "james":
            j = p["james"]
            if not isinstance(j, dict):
                errs.append("james must be an object")
            else:
                for stage in j:
                    if stage not in JAMES_STAGES:
                        errs.append(f"unknown james stage: {stage}")
            continue
        if key not in VIPER_GROUPS:
            errs.append(f"unknown group: {key}")
    eq = p.get("equalizer")
    if isinstance(eq, dict) and isinstance(eq.get("bands"), list):
        if eq.get("bandCount") != len(eq["bands"]):
            errs.append("equalizer bandCount != len(bands)")
    ddc = p.get("ddc")
    if isinstance(ddc, dict) and ddc.get("enable"):
        for sr in ("sr44100", "sr48000"):
            coeffs = ddc.get(sr)
            if not isinstance(coeffs, list) or not coeffs:
                errs.append(f"ddc enabled but {sr} missing/empty (need SR_44100/SR_48000 coeffs)")
            elif len(coeffs) % 5 != 0:
                errs.append(f"ddc {sr} length % 5 != 0")
            elif not all(isinstance(c, (int, float)) and c == c and abs(c) != float("inf") for c in coeffs):
                errs.append(f"ddc {sr} has non-finite coeffs")
    fs = p.get("fieldSurround")
    if isinstance(fs, dict):
        w = fs.get("widening")
        if isinstance(w, (int, float)) and not (0 <= w <= 8):
            errs.append(f"fieldSurround.widening {w} out of range (0,8)")
        m = fs.get("midImage")
        if isinstance(m, (int, float)) and not (0 <= m <= 10):
            errs.append(f"fieldSurround.midImage {m} out of range (0,10)")
    fet = p.get("fetCompressor")
    if isinstance(fet, dict):
        t = fet.get("threshold")
        if isinstance(t, (int, float)) and not (-48 <= t <= 0):
            errs.append(f"fetCompressor.threshold {t} out of range (-48,0)")
    return errs


LINK_SCHEME = "vipjam://preset?c="


def pack_link(p: dict[str, Any]) -> str:
    raw = json.dumps(p, separators=(",", ":"), ensure_ascii=False).encode()
    return LINK_SCHEME + base64.urlsafe_b64encode(raw).decode()


def unpack_link(link: str) -> dict[str, Any]:
    if not link.startswith(LINK_SCHEME):
        raise ValueError("not a vipjam preset link")
    payload = link[len(LINK_SCHEME):]
    payload += "=" * (-len(payload) % 4)
    try:
        raw = base64.urlsafe_b64decode(payload)
    except Exception:
        raise ValueError("link payload is not valid base64")
    try:
        obj = json.loads(raw)
    except json.JSONDecodeError:
        raise ValueError("link payload is not valid JSON")
    if not isinstance(obj, dict):
        raise ValueError("link payload must be an object")
    return obj


def self_test() -> int:
    base = Path(__file__).resolve().parent / "fixtures"
    fails = 0

    def check(cond: bool, msg: str) -> None:
        nonlocal fails
        print(("PASS " if cond else "FAIL ") + msg)
        if not cond:
            fails += 1

    v2 = json.loads((base / "viper_v2_demo.json").read_text(encoding="utf-8"))
    v3 = v2_to_v3(v2)
    check(v3["schemaVersion"] == 3 and v3["origin"] == "viper", "v2->v3 envelope")
    check(v3["equalizer"]["bands"][0] == 3.0, "v2 groups pass through untouched")
    check(validate_v3(v3) == [], "v2->v3 validates clean")
    check(v2_to_v3(v3) is v3, "v3 input is idempotent")

    bad = dict(v3)
    bad["equalizer"] = dict(bad["equalizer"])
    bad["equalizer"]["bandCount"] = 5
    check(validate_v3(bad) != [], "bandCount mismatch is caught")

    jprefs = parse_james_prefs(
        (base / "james_headset_demo.xml").read_text(encoding="utf-8")
    )
    check(jprefs["dsp.tone.enable"] is True, "james xml prefs parse")
    j = james_to_v3(jprefs, route="headset")
    check(len(j["tone"]["freqs"]) == 15 and len(j["tone"]["gains"]) == 15,
          "james tone curve splits 15+15")
    check(j["liveprog"]["scripts"] == ["demo.eel"], "james file refs basenamed")
    check(j["bs2b"]["mode"] == 5 and j["tube"]["drive"] == 2.0,
          "james scalars typed")
    merged = dict(v3)
    merged["origin"] = "vipjam"
    merged["james"] = j
    merged["route"] = "headset"
    merged["masterEnable"] = jprefs["dsp.masterswitch.enable"]
    check(validate_v3(merged) == [], "merged vipjam preset validates")

    link = pack_link(merged)
    check(link.startswith(LINK_SCHEME), "link packs with scheme")
    back = unpack_link(link)
    check(back == merged, "link round-trips preset exactly")
    check(validate_v3(back) == [], "unpacked link validates")
    bare = link.rstrip("=")
    check(unpack_link(bare) == merged, "unpadded app-style link unpacks")
    try:
        unpack_link("https://example.com/x")
        check(False, "bad scheme rejected")
    except ValueError:
        check(True, "bad scheme rejected")
    try:
        unpack_link(LINK_SCHEME + "!!!")
        check(False, "bad payload rejected")
    except ValueError:
        check(True, "bad payload rejected")
    return 1 if fails else 0


def _build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description="Convert ViPER v2 JSON / JamesDSP prefs to VipJam v3."
    )
    p.add_argument("input", type=Path, nargs="?",
                   help="v2 .json or james prefs (.xml/.json); '-' for stdin")
    p.add_argument("-o", "--output", type=Path, default=None)
    p.add_argument("--merge", type=Path, default=None,
                   help="james prefs file to merge (origin becomes vipjam)")
    p.add_argument("--route", default=None,
                   help="james route (default: detected from filename)")
    p.add_argument("--name", default=None)
    p.add_argument("--pack-link", action="store_true",
                   help="output vipjam://preset link instead of JSON")
    p.add_argument("--unpack-link", action="store_true",
                   help="input is a vipjam://preset link; print its JSON")
    p.add_argument("--self-test", action="store_true")
    return p


def main(argv: list[str] | None = None) -> int:
    args = _build_parser().parse_args(argv)
    if args.self_test:
        return self_test()
    if args.input is None:
        print("error: input required (or --self-test)", file=sys.stderr)
        return 2

    text = sys.stdin.read() if str(args.input) == "-" else args.input.read_text(
        encoding="utf-8"
    )
    try:
        if args.unpack_link:
            out = unpack_link(text.strip())
        elif args.input.suffix == ".xml" or text.lstrip().startswith("<"):
            prefs = parse_james_prefs(text)
            route = args.route or detect_route(args.input)
            out: dict[str, Any] = {
                "schemaVersion": 3,
                "origin": "james",
                "name": args.name or args.input.stem,
                "masterEnable": _to_bool(
                    prefs.get("dsp.masterswitch.enable", False)
                ),
                "james": james_to_v3(prefs, route=route),
            }
            if route:
                out["route"] = route
        else:
            v2 = json.loads(text)
            if not isinstance(v2, dict):
                raise ValueError("v2 json must be an object")
            out = v2_to_v3(v2, name=args.name)
            if args.merge:
                jprefs = parse_james_prefs(
                    args.merge.read_text(encoding="utf-8")
                )
                out["origin"] = "vipjam"
                out["james"] = james_to_v3(jprefs, route=args.route)
    except (ValueError, json.JSONDecodeError) as e:
        print(f"error: {e}", file=sys.stderr)
        return 2

    errs = validate_v3(out)
    if errs:
        for e in errs:
            print(f"invalid: {e}", file=sys.stderr)
        return 3
    if args.pack_link:
        rendered = pack_link(out)
    else:
        rendered = json.dumps(out, indent=2, ensure_ascii=False)
    if args.output is not None:
        args.output.write_text(rendered + "\n", encoding="utf-8")
        print(f"wrote {args.output} (origin={out['origin']})", file=sys.stderr)
    else:
        print(rendered)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
