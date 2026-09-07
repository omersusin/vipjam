# VipJam

Fused ViPER4AndroidFX-RE + JamesDSP audio engine: one app (`com.vipjam`),
one DSP library (`libvipjam.so`), HIDL system effect on rooted Android.

## Repo layout

- `vipjam_dsp/` — fused DSP (James chain into ViPER chain), HIDL wrapper
  in `hal/`, AIDL skeleton (uncompiled) in `hal-aidl/`
- `app-skeleton/` — greenfield Kotlin/Compose app (Effects, Presets,
  TestTone, LiveProg, AutoEq, AppProfiles, Status tabs)
- `vipjam-magisk/` — root module (id `vipjam`): installer, `vipjam-ctl`,
  `aml.sh`, KernelSU WebUI in `webroot/`
- `presets/` — v3 presets (Movie, Game, LoongFX sets)
- `tools/` — v2-to-v3 converter (`convert_v2_to_v3.py`), universal converter (`convert_universal.py`), headphonefx generator (`convert_headphonefx.py`), kernel fetcher, fixtures
- `docs/` — roadmap, device testing, release signing (`docs/RELEASE_SIGNING.md`)
- `kernels/` — IR kernels + manifest
- `docs/` — roadmap and device testing
- `upstream-*` — reference sources (research only)

## Builds

CI ("VipJam CI") uploads: `vipjam-magisk-*.zip` module,
`vipjam-debug-apk` debug app, `vipjam-release-apk` signed app.
See the Actions tab on GitHub.

## Docs

- `docs/DEVICE_TESTING.md` — flash, verify, listen, report bugs
- `docs/VIPJAM_ROADMAP.md` — master plan, status verified 2026-09-06
