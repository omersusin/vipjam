#!/usr/bin/env python3
"""Universal preset converter: ViPER v1 / legacy XML / v2, JamesDSP, APO, Wavelet -> VipJam v3 bank.

Provenance: v1/XML field tables, ranges and defaults come from
upstream-hv-app-viper4android/tools/convert_preset.py (itself derived from
EffectPrefs.kt / EffectGroups.kt / EffectStates.kt); JamesDSP stage mapping
comes from tools/convert_v2_to_v3.py; APO ParametricEQ and GraphicEQ patterns
come from app AutoEq.kt / GraphicEq.kt. Both upstream converters are loaded at
runtime when present; embedded fallbacks cover standalone use. Legacy XML
numeric ids are routed with the classic/new id ranges documented in
vipjam_dsp/include/VipJamParams.h. Unknown fields are preserved verbatim.

Field shapes, required keys and ranges are defined in
presets/preset.schema.json (loaded at runtime when present); validation here
mirrors that file. Sparse presets validate; normalize_v2() densifies to the
canonical bank form (every viper group incl. loudnessComp/liveprog).

Usage:
    convert_universal.py INPUT [-o OUT.json] [--source TAG] [--name NAME]
    convert_universal.py --bank STAGING_DIR BANK_DIR
    convert_universal.py --self-test
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import math
import re
import sys
import tarfile
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
UPSTREAM_CONVERT = REPO_ROOT / "upstream-hv-app-viper4android" / "tools" / "convert_preset.py"
UPSTREAM_ALT = REPO_ROOT / "upstream-viper-app" / "tools" / "convert_preset.py"
V23_CONVERT = Path(__file__).resolve().parent / "convert_v2_to_v3.py"

SCHEMA_VERSION = 3
ORIGINS = ("viper", "james", "vipjam")

SCHEMA_PATH = Path(__file__).resolve().parent.parent / "presets" / "preset.schema.json"


def _load_schema():
    try:
        return json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


SCHEMA = _load_schema()

VIPER_GROUPS = set(SCHEMA.get("properties", {}).keys()) - {
    "schemaVersion", "origin", "name", "masterEnable", "route",
    "source", "sourceName", "createdAt", "sourceText", "sourcePreampDb",
    "sourceFilters", "sourceGraphicPoints", "james",
} if SCHEMA else {
    "masterLimiter", "playbackGainControl", "lufs", "fetCompressor",
    "multibandCompressor", "ddc", "spectrumExtension", "equalizer",
    "dynamicEq", "convolver", "fieldSurround", "diffSurround",
    "stereoImager", "headphoneSurround", "reverb", "dynamicSystem",
    "psychoacousticBass", "bass", "bassMono", "clarity", "cure",
    "tubeSimulator", "analogX", "speakerCorrection", "loudnessComp",
    "liveprog",
}

CANONICAL_LOUDNESS = "loudnessComp"

JAMES_STAGES = {
    "masterswitch", "compression", "bass", "tone", "streq", "convolver",
    "ddc", "liveprog", "tube", "stereowide", "bs2b", "headphone",
}

JAMES_KNOWN_KEYS = {
    "dsp.masterswitch.enable", "dsp.masterswitch.limthreshold",
    "dsp.masterswitch.limrelease", "dsp.masterswitch.postgain",
    "dsp.compression.enable", "dsp.compression.timeconstant",
    "dsp.compression.granularity", "dsp.compression.tfresolution",
    "dsp.compression.eq", "dsp.compression.eq.custom",
    "dsp.bass.enable", "dsp.bass.maxgain",
    "dsp.tone.enable", "dsp.tone.filtertype", "dsp.tone.interpolation",
    "dsp.tone.eq", "dsp.tone.eq.custom",
    "dsp.streq.enable", "dsp.streq.stringp",
    "dsp.convolver.enable", "dsp.convolver.files",
    "dsp.ddc.enable", "dsp.ddc.files",
    "dsp.liveprog.enable", "dsp.liveprog.files",
    "dsp.analogmodelling.enable", "dsp.analogmodelling.tubedrive",
    "dsp.stereowide.enable", "dsp.stereowide.mode",
    "dsp.bs2b.enable", "dsp.bs2b.mode",
    "dsp.headphone.enable", "dsp.headphone.preset",
}

STD10 = [31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0]
GRAPHIC_MAX_DB = 12.0

PREAMP_RE = re.compile(r"^Preamp:\s*(-?\d+(?:\.\d+)?)\s*dB\s*$", re.MULTILINE)
FILTER_RE = re.compile(
    r"^Filter\s+(\d+):\s+(ON|OFF)\s+(LSC|HSC|PK|LS|HS|LP|HP|NOTCH|AP)\s+Fc\s+(\d+(?:\.\d+)?)\s*Hz\s+Gain\s+(-?\d+(?:\.\d+)?)\s*dB\s+Q\s+(\d+(?:\.\d+)?)\s*$",
    re.IGNORECASE | re.MULTILINE,
)


def _load_module(tag, path):
    spec = importlib.util.spec_from_file_location(tag, path)
    if spec is None or spec.loader is None:
        return None
    mod = importlib.util.module_from_spec(spec)
    try:
        spec.loader.exec_module(mod)
    except Exception:
        return None
    return mod


_HV = None
for _cand in (UPSTREAM_CONVERT, UPSTREAM_ALT):
    if _cand.exists():
        _HV = _load_module("hv_convert_preset", _cand)
        if _HV is not None:
            break

_V23 = _load_module("vj_convert_v2_to_v3", V23_CONVERT) if V23_CONVERT.exists() else None


def _to_bool(v):
    if isinstance(v, bool):
        return v
    return str(v).strip().lower() == "true"


def _to_int(v, default=0):
    try:
        return int(float(str(v).strip()))
    except (ValueError, TypeError):
        return default


def _to_float(v, default=0.0):
    try:
        r = float(str(v).strip())
        return r if math.isfinite(r) else default
    except (ValueError, TypeError):
        return default


def _clamp(n, lo, hi):
    return max(lo, min(hi, n))


def detect_format(text, filename=""):
    s = text.lstrip()
    low = filename.lower()
    if low.endswith(".eel"):
        return "james-eel"
    if s.startswith("<"):
        if 'name="dsp.' in text or "dsp.masterswitch" in text:
            return "james-xml"
        return "legacy-xml"
    if low.endswith((".tar", ".tgz", ".tar.gz")):
        return "james-tar"
    if "GraphicEQ:" in text:
        return "apo-graphic"
    if PREAMP_RE.search(text) or FILTER_RE.search(text):
        if "fixedband" in low:
            return "apo-fixedband"
        return "apo-parametric"
    if s.startswith("{"):
        try:
            obj = json.loads(text)
        except ValueError:
            return "wavelet-plain"
        if isinstance(obj, dict):
            if obj.get("schemaVersion") == 2 or (
                "schemaVersion" not in obj
                and any(k in VIPER_GROUPS for k in obj)
                and not any(k in (_HV.PREF_TABLE if _HV else {}) for k in obj)
            ):
                return "v2-grouped"
            if _V23 is not None:
                try:
                    _V23.parse_james_prefs(text)
                    if any(k.startswith("dsp.") for k in obj):
                        return "james-json"
                except Exception:
                    pass
            if any(k.startswith("dsp.") for k in obj):
                return "james-json"
            return "v1-flat"
    lines = [ln.strip() for ln in text.splitlines() if ln.strip()]
    if lines and all(re.match(r"^-?\d+(?:\.\d+)?\s+(-?\d+(?:\.\d+)?)$", ln) for ln in lines[:8]):
        return "wavelet-plain"
    if ";" in text and re.search(r"\d\s+(-?\d+(?:\.\d+)?)", text):
        return "wavelet-plain"
    return "wavelet-plain"


def v2_neutral_group(group):
    if _HV is not None:
        out = {}
        for (g, field), v1key in _HV.SLOT_TO_V1.items():
            if g != group:
                continue
            pref = _HV.PREF_TABLE[v1key]
            dflt = _v1_default(pref)
            if isinstance(dflt, list):
                out[field] = _coerce_list_field(group, field, dflt)
            else:
                out[field] = _coerce_field(group, field, dflt)
        if out:
            if "enable" in out:
                out["enable"] = False
            return out
    return {"enable": False}


def _v1_default(pref):
    t = pref["type"]
    if t == "BoolPref":
        return bool(pref["default"])
    if t == "IntPref":
        return int(pref["default"])
    if t == "StringPref":
        return str(pref["default"])
    if t == "NullableLongPref":
        return None
    if t in ("IntListPref", "BoolListPref", "DoubleListPref"):
        return list(pref["default"])
    return pref["default"]


def _coerce_field(group, field, value):
    if _HV is None:
        return value
    v1key = _HV.SLOT_TO_V1.get((group, field))
    if v1key is None:
        return value
    pref = _HV.PREF_TABLE[v1key]
    return _HV._coerce_scalar(pref["type"], value, _v1_default(pref), pref.get("range"))


def _coerce_list_field(group, field, values):
    if _HV is None:
        return list(values)
    v1key = _HV.SLOT_TO_V1.get((group, field))
    if v1key is None:
        return list(values)
    pref = _HV.PREF_TABLE.get(v1key, {})
    rng = pref.get("range")
    t = pref.get("type", "")
    out = []
    for v in values:
        if t == "DoubleListPref":
            try:
                f = float(v)
            except (ValueError, TypeError):
                f = 0.0
            out.append(_clamp(f, rng[0], rng[1]) if rng else f)
        elif t == "IntListPref":
            n = _to_int(v)
            out.append(_clamp(n, rng[0], rng[1]) if rng else n)
        elif t == "BoolListPref":
            out.append(_to_bool(v))
        else:
            out.append(v)
    return out


def _group_fields(group):
    if _HV is not None:
        return [f for (g, f) in _HV.SLOT_TO_V1 if g == group]
    return []


def normalize_v2(v2, unmapped):
    out = {}
    order = list(_HV.GROUP_ORDER) if _HV is not None else sorted(VIPER_GROUPS)
    for extra in ("loudnessComp", "liveprog"):
        if extra not in order:
            order.append(extra)
    for group in order:
        if group in v2 and isinstance(v2[group], dict):
            src = v2[group]
            fields = set(_group_fields(group))
            norm = {}
            for k, val in src.items():
                if fields and k not in fields:
                    norm[k] = val
                    unmapped.append(group + "." + k)
                elif isinstance(val, list):
                    norm[k] = _coerce_list_field(group, k, val)
                else:
                    norm[k] = _coerce_field(group, k, val)
            for k in fields:
                if k not in norm:
                    v1key = _HV.SLOT_TO_V1[(group, k)]
                    dflt = _v1_default(_HV.PREF_TABLE[v1key])
                    if isinstance(dflt, list):
                        norm[k] = _coerce_list_field(group, k, dflt)
                    else:
                        norm[k] = _coerce_field(group, k, dflt)
            if "enable" in norm and group not in v2:
                norm["enable"] = False
            out[group] = norm
        else:
            out[group] = v2_neutral_group(group)
    for k, val in v2.items():
        if k in ("schemaVersion", "name", "masterEnable", "masterEnabled",
                 "createdAt", "route", "origin", "source", "sourceName"):
            continue
        if k not in out:
            out[k] = val
            unmapped.append(k)
    eq = out.get("equalizer")
    if isinstance(eq, dict) and isinstance(eq.get("bands"), list):
        eq["bands"] = [_clamp(_to_float(b), -GRAPHIC_MAX_DB, GRAPHIC_MAX_DB) for b in eq["bands"]]
        eq["bandCount"] = len(eq["bands"])
    return out


def read_v1_flat(obj):
    if _HV is not None:
        consumed = set(_HV.PREF_TABLE)
        for info in _HV.PREF_TABLE.values():
            if isinstance(info, dict) and "spk" in info:
                consumed.add(info["spk"])
        consumed |= {"masterEnabled", "spkMasterEnabled"}
        unmapped = [k for k in obj if k not in consumed]
        is_spk = "spkMasterEnabled" in obj
        v2 = _HV.v1_to_v2(obj, is_spk, fill_defaults=True)
    else:
        unmapped = list(obj)
        v2 = {}
    extra = {k: obj[k] for k in unmapped}
    return v2, extra, unmapped


XML_ALIASES = (
    {"65589", "65591;65592;65593", "65594", "65595", "65596", "65597",
     "65598", "65599", "65600", "65601", "65602"}
    | {str(65627 + j) for j in range(17)}
)


def read_legacy_xml(text):
    if _HV is None:
        raise ValueError("legacy XML needs upstream convert_preset.py")
    raw = _HV._xml_parse(text)
    consumed = set(_HV._BOOL_MAP) | set(_HV._INT_MAP) | set(_HV._STR_MAP) | set(_HV._MODE_MAP)
    consumed |= {"65577", "65580", "65549;65550", "65570;65571;65572"}
    consumed |= XML_ALIASES
    unmapped = sorted(k for k in raw if k not in consumed)
    v1 = _HV.xml_to_v1(text)
    v2 = _HV.v1_to_v2(v1, False, fill_defaults=True)
    extra = {k: raw[k] for k in unmapped}
    return v2, extra, unmapped


def read_v2_grouped(obj):
    unmapped = []
    v2 = {k: (dict(v) if isinstance(v, dict) else v) for k, v in obj.items()
          if k not in ("schemaVersion", "name", "createdAt")}
    norm = normalize_v2(v2, unmapped)
    return norm, {}, unmapped


def parse_james_prefs(text):
    s = text.lstrip()
    if s.startswith("<"):
        root = ET.fromstring(s)
        prefs = {}
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


def read_james(prefs):
    unmapped = sorted(k for k in prefs if k not in JAMES_KNOWN_KEYS)
    if _V23 is not None:
        stages = _V23.james_to_v3(prefs)
    else:
        stages = {s: {} for s in JAMES_STAGES}
    for k in unmapped:
        stages[k] = prefs[k]
    return stages, unmapped


def read_james_tar(path):
    members = []
    with tarfile.open(path, "r:*") as tf:
        for m in tf.getmembers():
            if m.isfile() and m.name.endswith(".xml"):
                members.append(m.name)
        if not members:
            raise ValueError("tar holds no dsp xml")
        members.sort(key=lambda n: ("headset" not in n.lower(), n))
        picked = members[0]
        with tarfile.open(path, "r:*") as tf2:
            f = tf2.extractfile(picked)
            if f is None:
                raise ValueError("cannot extract " + picked)
            text = f.read().decode("utf-8", "replace")
    prefs = parse_james_prefs(text)
    stages, unmapped = read_james(prefs)
    return stages, unmapped, picked


def parse_parametric(text):
    preamp = None
    filters = []
    for raw in text.splitlines():
        line = raw.strip()
        if not line:
            continue
        pm = PREAMP_RE.match(line)
        if pm is not None:
            preamp = float(pm.group(1))
            continue
        fm = FILTER_RE.match(line)
        if fm is None:
            raise ValueError("malformed APO line: " + line)
        if not fm.group(2).upper() == "ON":
            continue
        filters.append({
            "type": fm.group(3).upper(),
            "fcHz": float(fm.group(4)),
            "gainDb": float(fm.group(5)),
            "q": float(fm.group(6)),
        })
    if preamp is None:
        raise ValueError("missing Preamp line")
    if not filters:
        raise ValueError("no ON filters")
    return preamp, filters


def parse_graphic(text):
    body = text.split("GraphicEQ:", 1)[1]
    flat = body.replace("\n", ";").replace("\r", ";")
    points = []
    for raw in flat.split(";"):
        tok = raw.strip()
        if not tok:
            continue
        parts = [p for p in re.split(r"[\s,]+", tok) if p]
        if len(parts) != 2:
            raise ValueError("malformed GraphicEQ pair: " + tok)
        f = float(parts[0])
        g = float(parts[1])
        if not (math.isfinite(f) and f > 0 and math.isfinite(g)):
            raise ValueError("bad GraphicEQ pair: " + tok)
        points.append((f, g))
    if not points:
        raise ValueError("no GraphicEQ pairs")
    points.sort(key=lambda p: p[0])
    return points


def graphic_sample(points, freq):
    if freq <= points[0][0]:
        return _clamp(points[0][1], -GRAPHIC_MAX_DB, GRAPHIC_MAX_DB)
    if freq >= points[-1][0]:
        return _clamp(points[-1][1], -GRAPHIC_MAX_DB, GRAPHIC_MAX_DB)
    lo, hi = 0, len(points) - 1
    while hi - lo > 1:
        mid = (lo + hi) // 2
        if points[mid][0] < freq:
            lo = mid
        else:
            hi = mid
    f0, g0 = points[lo]
    f1, g1 = points[hi]
    t = (math.log(freq) - math.log(f0)) / (math.log(f1) - math.log(f0))
    return _clamp(g0 + t * (g1 - g0), -GRAPHIC_MAX_DB, GRAPHIC_MAX_DB)


def _rbj_coeffs(ftype, f0, gain_db, q, fs):
    qq = q if q > 0 else 0.7071
    a = 10.0 ** (gain_db / 40.0)
    w0 = 2.0 * math.pi * f0 / float(fs)
    cosw = math.cos(w0)
    sinw = math.sin(w0)
    if ftype in ("PK", "NOTCH", "AP"):
        alpha = sinw / (2.0 * qq)
        b0 = 1.0 + alpha * a
        b1 = -2.0 * cosw
        b2 = 1.0 - alpha * a
        a0 = 1.0 + alpha / a
        a1 = -2.0 * cosw
        a2 = 1.0 - alpha / a
    else:
        s = 1.0
        alpha = sinw / 2.0 * math.sqrt((a + 1.0 / a) * (1.0 / s - 1.0) + 2.0)
        beta = 2.0 * math.sqrt(a) * alpha
        if ftype in ("LSC", "LS", "LP"):
            b0 = a * ((a + 1.0) - (a - 1.0) * cosw + beta)
            b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosw)
            b2 = a * ((a + 1.0) - (a - 1.0) * cosw - beta)
            a0 = (a + 1.0) + (a - 1.0) * cosw + beta
            a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cosw)
            a2 = (a + 1.0) + (a - 1.0) * cosw - beta
        else:
            b0 = a * ((a + 1.0) + (a - 1.0) * cosw + beta)
            b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosw)
            b2 = a * ((a + 1.0) + (a - 1.0) * cosw - beta)
            a0 = (a + 1.0) - (a - 1.0) * cosw + beta
            a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cosw)
            a2 = (a + 1.0) - (a - 1.0) * cosw - beta
    return (b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)


def _filter_db_at(ftype, f0, gain_db, q, freq, fs=48000):
    if ftype in ("LP", "HP"):
        return 0.0
    b0, b1, b2, a1, a2 = _rbj_coeffs(ftype, f0, gain_db, q, fs)
    w = 2.0 * math.pi * freq / float(fs)
    cw = math.cos(w)
    sw = math.sin(w)
    cw2 = math.cos(2 * w)
    sw2 = math.sin(2 * w)
    nr = b0 + b1 * cw + b2 * cw2
    ni = -(b1 * sw + b2 * sw2)
    dr = 1.0 + a1 * cw + a2 * cw2
    di = -(a1 * sw + a2 * sw2)
    mag = math.hypot(nr, ni) / max(math.hypot(dr, di), 1e-12)
    return 20.0 * math.log10(max(mag, 1e-12))


def parametric_to_bands(preamp, filters):
    bands = []
    for f in STD10:
        total = sum(_filter_db_at(fl["type"], fl["fcHz"], fl["gainDb"], fl["q"], f) for fl in filters)
        bands.append(_clamp(total + preamp, -GRAPHIC_MAX_DB, GRAPHIC_MAX_DB))
    return bands


def parse_wavelet(text):
    if "GraphicEQ:" in text:
        return [graphic_sample(parse_graphic(text), f) for f in STD10]
    pairs = []
    for raw in text.replace(";", "\n").splitlines():
        tok = raw.strip().lstrip("#")
        if not tok:
            continue
        parts = [p for p in re.split(r"[\s,]+", tok) if p]
        if len(parts) == 2:
            try:
                f = float(parts[0])
                g = float(parts[1])
            except ValueError:
                continue
            if f > 0 and math.isfinite(f) and math.isfinite(g):
                pairs.append((f, g))
    if len(pairs) >= 3:
        pairs.sort(key=lambda p: p[0])
        return [graphic_sample(pairs, f) for f in STD10]
    gains = []
    for raw in re.split(r"[\s,;]+", text.strip()):
        try:
            gains.append(float(raw))
        except ValueError:
            continue
    if len(gains) >= 5:
        pts = list(zip(STD10[:len(gains)], gains)) if len(gains) <= 10 else None
        if pts is not None:
            return [_clamp(g, -GRAPHIC_MAX_DB, GRAPHIC_MAX_DB) for _, g in pts]
    raise ValueError("unrecognised Wavelet text preset")


def detect_route(filename, text=""):
    low = (filename + " " + text[:500]).lower()
    for route in ("bluetooth", "speaker", "usb"):
        if route in low or "bt_a2dp" in low:
            return route
    return "headset"


def build_v3(name, source, source_name, route, v2=None, james=None, master=True, extra=None, fidelity=None):
    out = {
        "schemaVersion": SCHEMA_VERSION,
        "origin": "james" if james is not None and v2 is None else "viper",
        "name": name,
        "masterEnable": bool(master),
        "source": source,
        "sourceName": source_name,
    }
    if route:
        out["route"] = route
    if v2 is not None:
        for k, val in v2.items():
            out[k] = val
    if james is not None:
        out["james"] = james
    if extra:
        for k, val in extra.items():
            if k not in out:
                out[k] = val
    if fidelity:
        for k, val in fidelity.items():
            out[k] = val
    return out


def validate_v3(p):
    errs = []
    if p.get("schemaVersion") != 3:
        errs.append("schemaVersion must be 3")
    if p.get("origin") not in ORIGINS:
        errs.append("origin must be viper|james|vipjam")
    if not p.get("name"):
        errs.append("name must be non-empty")
    if p.get("source") is not None and not p["source"]:
        errs.append("source must be non-empty when present")
    eq = p.get("equalizer")
    if isinstance(eq, dict) and isinstance(eq.get("bands"), list):
        if eq.get("bandCount") != len(eq["bands"]):
            errs.append("equalizer bandCount != len(bands)")
        for b in eq["bands"]:
            if isinstance(b, (int, float)) and not (-12 <= b <= 12):
                errs.append(f"equalizer band {b} out of range (-12,12)")
                break
    ddc = p.get("ddc")
    if isinstance(ddc, dict) and ddc.get("enable"):
        if isinstance(ddc.get("device"), str) and ddc["device"]:
            pass
        else:
            for sr in ("sr44100", "sr48000"):
                coeffs = ddc.get(sr)
                if not isinstance(coeffs, list) or not coeffs:
                    errs.append(f"ddc enabled but {sr} missing/empty (need SR coeffs or a device .vdc ref)")
                elif len(coeffs) % 5 != 0:
                    errs.append(f"ddc {sr} length % 5 != 0")
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
    conv = p.get("convolver")
    if isinstance(conv, dict):
        cc = conv.get("crossChannel")
        if isinstance(cc, (int, float)) and not (0 <= cc <= 100):
            errs.append(f"convolver.crossChannel {cc} out of range (0,100)")
    return errs


def convert_file(path, source, name=None):
    pname = name or Path(path).stem
    suffix = Path(path).suffix.lower()
    if suffix in (".tar", ".tgz", ".gz"):
        stages, unmapped, picked = read_james_tar(str(path))
        route = detect_route(Path(path).name + " " + picked)
        master = stages.get("masterswitch", {}).get("postGain") is not None
        out = build_v3(pname, source, Path(path).name, route, james=stages, master=True)
        return out, unmapped
    text = Path(path).read_text(encoding="utf-8", errors="replace")
    fmt = detect_format(text, Path(path).name)
    route = detect_route(Path(path).name, text)
    if fmt == "legacy-xml":
        v2, extra, unmapped = read_legacy_xml(text)
        unmapped2 = []
        norm = normalize_v2(v2, unmapped2)
        master = "36868" in text
        out = build_v3(pname, source, Path(path).name, route, v2=norm, master=master, extra=extra)
        return out, unmapped + unmapped2
    if fmt == "v1-flat":
        obj = json.loads(text)
        v2, extra, unmapped = read_v1_flat(obj)
        unmapped2 = []
        norm = normalize_v2(v2, unmapped2)
        master = bool(obj.get("masterEnabled", obj.get("spkMasterEnabled", True)))
        out = build_v3(pname, source, Path(path).name, route, v2=norm, master=master, extra=extra)
        return out, unmapped + unmapped2
    if fmt == "v2-grouped":
        obj = json.loads(text)
        norm, _, unmapped = read_v2_grouped(obj)
        out = build_v3(pname, source, Path(path).name, route, v2=norm,
                       master=bool(obj.get("masterEnable", True)))
        return out, unmapped
    if fmt in ("james-xml", "james-json"):
        prefs = parse_james_prefs(text)
        stages, unmapped = read_james(prefs)
        master = _to_bool(prefs.get("dsp.masterswitch.enable", False))
        out = build_v3(pname, source, Path(path).name, route, james=stages, master=master)
        return out, unmapped
    if fmt == "james-eel":
        stages, unmapped = read_james({})
        stages["liveprog"] = {"enable": True, "scripts": [Path(path).name]}
        out = build_v3(pname, source, Path(path).name, route, james=stages, master=True,
                       fidelity={"sourceText": text})
        return out, unmapped + ["sourceText", "liveprog.scripts"]
    if fmt in ("apo-parametric", "apo-fixedband", "apo-graphic"):
        unmapped = []
        if fmt == "apo-graphic":
            points = parse_graphic(text)
            bands = [graphic_sample(points, f) for f in STD10]
            fidelity = {"sourceGraphicPoints": [[f, g] for f, g in points]}
        else:
            preamp, filters = parse_parametric(text)
            bands = parametric_to_bands(preamp, filters)
            fidelity = {"sourcePreampDb": preamp, "sourceFilters": filters}
            unmapped = ["sourcePreampDb", "sourceFilters"]
        unmapped2 = []
        v2 = normalize_v2({"equalizer": {"enable": True, "bandCount": 10, "bands": bands,
                                         "presetId": None}}, unmapped2)
        fidelity["sourceText"] = text
        unmapped = unmapped + ["sourceText"] + unmapped2
        out = build_v3(pname, source, Path(path).name, route, v2=v2, master=True, fidelity=fidelity)
        return out, unmapped
    if fmt == "wavelet-plain":
        bands = parse_wavelet(text)
        unmapped2 = []
        v2 = normalize_v2({"equalizer": {"enable": True, "bandCount": 10, "bands": bands,
                                         "presetId": None}}, unmapped2)
        out = build_v3(pname, source, Path(path).name, route, v2=v2, master=True,
                       fidelity={"sourceText": text})
        return out, ["sourceText"] + unmapped2
    raise ValueError("cannot detect format: " + str(path))


def slug(name):
    s = re.sub(r"[^A-Za-z0-9]+", "-", name).strip("-").lower()
    return s or "preset"


def convert_bank(staging, bankdir):
    staging = Path(staging)
    bankdir = Path(bankdir)
    outdir = bankdir / "presets-universal"
    outdir.mkdir(parents=True, exist_ok=True)
    manifest = []
    counts = {}
    unmapped_all = {}
    taken = set()
    for src in sorted(staging.iterdir()):
        if not src.is_dir():
            continue
        source = src.name
        for path in sorted(src.rglob("*")):
            if not path.is_file():
                continue
            try:
                preset, unmapped = convert_file(str(path), source)
            except Exception as e:
                print("SKIP " + str(path) + ": " + str(e))
                continue
            errs = validate_v3(preset)
            if errs:
                print("INVALID " + str(path) + ": " + "; ".join(errs))
                continue
            base = slug(source) + "--" + slug(preset["name"])
            fname = base + ".json"
            i = 2
            while fname in taken:
                fname = base + "-" + str(i) + ".json"
                i += 1
            taken.add(fname)
            (outdir / fname).write_text(json.dumps(preset, indent=2, ensure_ascii=False) + "\n",
                                        encoding="utf-8")
            manifest.append({"name": preset["name"], "source": source,
                             "file": "presets-universal/" + fname,
                             "route": preset.get("route", "headset")})
            counts[source] = counts.get(source, 0) + 1
            for u in unmapped:
                unmapped_all[source + ":" + u] = unmapped_all.get(source + ":" + u, 0) + 1
    (bankdir / "MANIFEST.json").write_text(
        json.dumps({"presets": manifest, "counts": counts, "unmapped": unmapped_all},
                   indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return counts, unmapped_all, str(outdir)


def self_test():
    fails = 0

    def check(cond, msg):
        nonlocal fails
        print(("PASS " if cond else "FAIL ") + msg)
        if not cond:
            fails += 1

    v1 = {"masterEnabled": True, "outputVolume": 999, "eqEnabled": True,
          "eqBands": "3.0;1.5;0.0;-1.0;0.0;0.0;1.0;2.0;3.0;4.0",
          "mysteryKnob": 7}
    v2a, extra_a, un_a = read_v1_flat(dict(v1))
    ua = []
    na = normalize_v2(v2a, ua)
    pa = build_v3("V1Fix", "viper-v1", "v1.json", "headset", v2=na, master=True, extra=extra_a)
    check(pa["schemaVersion"] == 3 and pa["origin"] == "viper", "v1 envelope")
    check(pa.get("mysteryKnob") == 7 and "mysteryKnob" in un_a, "v1 unknown preserved")
    v1s = {"spkMasterEnabled": True, "spkOutputVolume": 80, "spkExtra": 1}
    _, _, un_as = read_v1_flat(dict(v1s))
    check(un_as == ["spkExtra"], "v1 spk keys consumed")
    check(pa["masterLimiter"]["outputVolume"] <= 200, "v1 clamp applied")
    check(pa["equalizer"]["bandCount"] == len(pa["equalizer"]["bands"]) == 10, "v1 eq bands")

    xml = ("<map><boolean name=\"36868\" value=\"true\" />"
           "<boolean name=\"65551\" value=\"true\" />"
           "<string name=\"65552\">3.0;1.5;0.0;-1.0;0.0;0.0;1.0;2.0;3.0;4.0</string>"
           "<int name=\"65611\" value=\"-18\" />"
           "<int name=\"99999\" value=\"5\" /></map>")
    check(detect_format(xml) == "legacy-xml", "xml detected")
    v2b, extra_b, un_b = read_legacy_xml(xml)
    ub = []
    nb = normalize_v2(v2b, ub)
    pb = build_v3("XmlFix", "viper-v1", "x.xml", "headset", v2=nb, master=True, extra=extra_b)
    check(pb["schemaVersion"] == 3 and pb.get("99999") == "5", "xml unknown id preserved")
    xml_old = ("<map><boolean name=\"36868\" value=\"true\" />"
               "<boolean name=\"65627\" value=\"true\" />"
               "<int name=\"65589\" value=\"1\" /></map>")
    _, _, un_bo = read_legacy_xml(xml_old)
    check(un_bo == [], "xml aliases consumed")
    check(pb["equalizer"]["bandCount"] == 10, "xml eq bands")

    v2in = {"schemaVersion": 2, "name": "G", "masterLimiter": {"threshold": 9999},
            "equalizer": {"enable": True, "bandCount": 3, "bands": [99.0, -99.0, 0.0],
                          "presetId": None, "weird": 1}}
    norm_c, _, un_c = read_v2_grouped(dict(v2in))
    pc = build_v3("V2Fix", "viper-v2", "g.json", "speaker", v2=norm_c, master=True)
    check(30 <= pc["masterLimiter"]["threshold"] <= 100, "v2 clamp applied")
    check(pc["equalizer"]["bandCount"] == 3 and all(abs(b) <= 12 for b in pc["equalizer"]["bands"]),
          "v2 eq clamped")
    check(any(u == "equalizer.weird" for u in un_c), "v2 unknown field listed")
    check("bass" in pc and pc["bass"]["enable"] is False, "v2 missing group neutral")
    check(validate_v3(pc) == [], "v2 validates")

    jx = ("<map><boolean name=\"dsp.masterswitch.enable\" value=\"true\" />"
          "<string name=\"dsp.streq.stringp\">GraphicEQ: 25 0.0; 40 1.0;</string>"
          "<boolean name=\"dsp.streq.enable\" value=\"true\" />"
          "<string name=\"dsp.custom.future\">42</string></map>")
    check(detect_format(jx) == "james-xml", "james xml detected")
    prefs = parse_james_prefs(jx)
    stages, un_d = read_james(prefs)
    pd = build_v3("JFix", "jamesdsp", "j.xml", "headset", james=stages, master=True)
    check(pd["origin"] == "james" and pd["james"]["streq"]["enable"] is True, "james mapped")
    check(pd["james"].get("dsp.custom.future") == "42", "james unknown preserved")

    with tempfile.TemporaryDirectory() as td:
        tp = Path(td) / "dsp_headset.tar"
        with tarfile.open(tp, "w") as tf:
            xp = Path(td) / "dsp_headset.xml"
            xp.write_text(jx, encoding="utf-8")
            tf.add(xp, arcname="dsp_headset.xml")
        st, un_e, picked = read_james_tar(str(tp))
        check("headset" in picked and st["streq"]["enable"] is True, "james tar picked")

    eel = "desc:verb\nslider1:0.2<0.01,1>Base Delay (s)\n@init\nslider1=0.2;\n"
    check(detect_format(eel, "verb.eel") == "james-eel", "james eel detected")

    apo = ("Preamp: -6.1 dB\nFilter 1: ON LSC Fc 105 Hz Gain 6.4 dB Q 0.70\n"
           "Filter 2: ON PK Fc 8800 Hz Gain 5.1 dB Q 1.42\n"
           "Filter 3: OFF PK Fc 100 Hz Gain 9.9 dB Q 1.00\n")
    check(detect_format(apo) == "apo-parametric", "apo parametric detected")
    pre, flt = parse_parametric(apo)
    check(pre == -6.1 and len(flt) == 2, "apo parametric parsed, OFF skipped")
    bands = parametric_to_bands(pre, flt)
    check(len(bands) == 10 and all(abs(b) <= 12 for b in bands), "apo sampled to 10 bands")

    gfx = "GraphicEQ: 20 -0.5; 100 2.0; 1000 0.0; 16000 -3.0;"
    check(detect_format(gfx) == "apo-graphic", "apo graphic detected")
    gb = [graphic_sample(parse_graphic(gfx), f) for f in STD10]
    check(len(gb) == 10, "graphic sampled")

    fb = ("Preamp: -7.4 dB\nFilter 1: ON PK Fc 31 Hz Gain 7.0 dB Q 1.41\n"
          "Filter 2: ON PK Fc 62 Hz Gain 2.6 dB Q 1.41\n")
    check(detect_format(fb, "Model FixedBandEQ.txt") == "apo-fixedband", "fixedband detected")

    wv = "31 1.0\n62 0.5\n125 0.0\n250 -1.0\n500 0.0\n1000 1.0\n2000 2.0\n4000 1.0\n8000 0.0\n16000 -1.0\n"
    check(detect_format(wv) == "wavelet-plain", "wavelet detected")
    wb = parse_wavelet(wv)
    check(len(wb) == 10 and wb[0] == 1.0, "wavelet pairs mapped")

    rt = json.loads(json.dumps(pc))
    norm_rt, _, _ = read_v2_grouped({k: v for k, v in rt.items()
                                     if k not in ("schemaVersion", "origin", "name", "masterEnable",
                                                  "source", "sourceName", "route")})
    pc2 = build_v3(rt["name"], rt["source"], rt["sourceName"], rt.get("route"), v2=norm_rt,
                   master=rt["masterEnable"])
    check(pc2 == pc, "round-trip stable")
    return 1 if fails else 0


def _build_parser():
    p = argparse.ArgumentParser(description="Convert any supported preset to VipJam v3.")
    p.add_argument("input", type=Path, nargs="?", help="preset file; '-' for stdin")
    p.add_argument("-o", "--output", type=Path, default=None)
    p.add_argument("--source", default="viper-v2")
    p.add_argument("--name", default=None)
    p.add_argument("--bank", nargs=2, metavar=("STAGING", "BANKDIR"), default=None)
    p.add_argument("--self-test", action="store_true")
    return p


def main(argv=None):
    args = _build_parser().parse_args(argv)
    if args.self_test:
        return self_test()
    if args.bank:
        counts, unmapped, outdir = convert_bank(args.bank[0], args.bank[1])
        print("COUNTS " + json.dumps(counts, sort_keys=True))
        print("UNMAPPED " + json.dumps(unmapped, sort_keys=True, ensure_ascii=False))
        print("BANK " + outdir)
        return 0
    if args.input is None:
        print("error: input required (or --self-test / --bank)", file=sys.stderr)
        return 2
    try:
        if str(args.input) == "-":
            text = sys.stdin.read()
            tmp = Path(tempfile.mkdtemp()) / "stdin.txt"
            tmp.write_text(text, encoding="utf-8")
            preset, _ = convert_file(str(tmp), args.source, args.name or "stdin")
        else:
            preset, _ = convert_file(str(args.input), args.source, args.name)
    except (ValueError, json.JSONDecodeError, ET.ParseError, OSError) as e:
        print("error: " + str(e), file=sys.stderr)
        return 2
    errs = validate_v3(preset)
    if errs:
        for e in errs:
            print("invalid: " + e, file=sys.stderr)
        return 3
    rendered = json.dumps(preset, indent=2, ensure_ascii=False)
    if args.output is not None:
        args.output.write_text(rendered + "\n", encoding="utf-8")
        print("wrote " + str(args.output), file=sys.stderr)
    else:
        print(rendered)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
