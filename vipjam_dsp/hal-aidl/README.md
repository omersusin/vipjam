# VipJam AIDL system-effect track (skeleton — UNCOMPILED)

Plan refs: `docs/VIPJAM_ROADMAP.md` §§ 2.2, 2.4, 4.4, 16.11.
Scope: a second effect driver next to the HIDL `vipjam_dsp/hal/`
(`effect_interface_s`) one. New files live ONLY in `vipjam_dsp/hal-aidl/`.
Nothing here builds until a Soong/AOSP-tree build exists (see §6).

Identity (approved plan):

- Type UUID (impersonated): `7261676f-6d75-7369-6364-28e2fd3ac39e`
  (`DynamicsProcessing` / `DYNAMICS_PROCESSING`).
- Impl UUID (ours): `90380da3-8536-4744-a6a3-5731970e640f`
  (same value the HIDL track uses for `v4a_standard_re`).
- AIDL-track lib/effect names (plan §2.2): lib `v4a_aidl`,
  effect `v4a_standard_aidl`.

## 1. Exact `IEffect` interface surface to implement

Source of truth: AOSP tree `hardware/interfaces/audio/aidl/`:

- `aidl/android/hardware/audio/effect/IEffect.aidl`
- `aidl/android/hardware/audio/effect/IFactory.aidl`
- `aidl/android/hardware/audio/effect/Descriptor.aidl`,
  `Capability.aidl`, `Parameter.aidl`, `State.aidl`, `CommandId.aidl`,
  `AudioChannelLayout.aidl`, `AudioDeviceDescription.aidl`, plus
  `DynamicsProcessing.aidl` (the `DP` union: channel Volt/float params
  the framework sends when it thinks we are a DynamicsProcessing).

Methods on `IEffect` (one Binder object per opened effect instance):

| Method | Purpose for VipJam |
|---|---|
| `open(Common, Specific)` | Validate `Common` (session, ioHandle, i/o `AudioChannelLayout`, `PcmType.FLOAT_32_BIT` only, sample rate, frame count). `setSamplingRate()` on `VipJamChain`; reject anything else with `EX_ILLEGAL_ARGUMENT` and stay bypassed. Store `Specific` union; we only accept the `dynamicsProcessing` arm, ignore/zero the vendor-extension arm. |
| `close()` | Stop FMQ worker, release SHM maps, reset `VipJamChain`. |
| `command(CommandId)` | Map at minimum: `START`→master on + `chain.reset()`; `STOP`→master off/bypass; `RESET`→`chain.reset()`; `SET_VOLUME_STEREO`→`setLoudnessVolume()`. Everything else → `EX_UNSUPPORTED_OPERATION`. (Legacy `EFFECT_CMD_*` codes do NOT exist here.) |
| `getState()` | Return `State`: `INIT` (constructed), `IDLE` (open), `PROCESSING` (started). |
| `setParameter(Parameter)` | Accept `Parameter.common` (large-buffer tuning) + `Parameter.specific.dynamicsProcessing` (engine on/off, per-channel attack/release/threshold/ratio/knee, pre/post gains, limiter). Translate DP floats → `VipJamChain` setters + SHM-poll resync. Persist raw `VendorExtension` blobs untouched (opaque passthrough) so a future app vendor tag survives. |
| `getParameter(Parameter.Id)` | Answer `common` (session/frames/rate) + the last-applied `dynamicsProcessing` snapshot. |
| `process` path (NOT a Binder call) | Audio flows over the FMQ pair from `open` (`DataMQDesc` in/out + `EventFlagGroup`). A worker thread does `readBlocking` → `VipJamChain::process` (interleaved stereo float) → `writeBlocking`. NEVER block Binder threads on DSP. |

`IFactory` surface (the `.so` registers into one running factory process):

- `queryEffects()` → returns exactly one `Descriptor` (see §3).
- `createEffect(implUuid)` → new `VipJamAidlEffect` Binder object iff
  uuid == ours, else `EX_ILLEGAL_ARGUMENT`.

## 2. Required C exports (effect-factory loader contract)

The AIDL effect loader `dlopen`s the lib and `dlsym`s entry points named by
convention (legacy name was `AUDIO_EFFECT_LIBRARY_INFO_SYM`; AIDL uses the
`createEffect`/`queryEffect`/`destroyEffect` trio — VERIFY against the
concrete AOSP version before shipping):

```cpp
extern "C" {
  int32_t createEffect(const char* uuid, void** out); // uuid==impl → new instance
  int32_t queryEffect(uint32_t idx, void* descOut);   // idx 0 → our Descriptor
  int32_t destroyEffect(void* handle);                // delete instance
}
```

`VipJamAidlEffect.cpp` in this dir sketches these three symbols plus the
`IEffect` method shapes. Signatures WILL drift per AOSP version — treat them
as placeholders until compiled against the real
`hardware/interfaces/audio/aidl` headers.

## 3. Descriptor / Capability / Parameter shape

```text
Descriptor {
  common: { id: { type: 7261676f-… (DynamicsProcessing),
                  uuid: 90380da3-… (ours) },
            flags: { type: INSERT, insert: FIRST, volume: NONE },
            name: "v4a_standard_aidl", implementor: "VipJam", … },
  capability: DynamicsProcessing.Capability {
    supportedChannelLayouts: [ STEREO ],
    supportedSampleRates: [ 44100, 48000 ],
    bandCountMax / channelCountMax per AOSP header (VERIFY),
  },
  … plus legacy-compat fields the loader expects (effectMode, … — VERIFY)
}
Parameter {
  common: { session, ioHandle, iConfig/oConfig (FLOAT_32_BIT, STEREO, rate) },
  specific: { dynamicsProcessing: { enabled, channelConfig[] (attack/release/
              threshold/ratio/knee/preGain/postGain), limiterEnabled, … } },
}
```

- Insert-first, stereo float32 only; anything else → reject in `open`.
- Framework DP sliders map lossily onto `VipJamChain`; full fidelity comes
  from the SHM channel (§4), DP is a coarse fallback.

## 4. SHM protocol (SAME as `VipJamShm` — not a copy)

Reuse, do not re-specify. Layout constants come from:

- `vipjam_dsp/include/VipJamShm.h`
- `vipjam_dsp/include/VipJamParams.h`

| Item | Value (from headers) |
|---|---|
| Files | `/data/local/tmp/vipjam/shm_{params,bulk,status}.bin` = `VIPJAM_SHM_{PARAMS,BULK,STATUS}_PATH` (dir `VIPJAM_SHM_DIR`) |
| Sizes | `VIPJAM_SHM_{PARAMS,BULK,STATUS}_SIZE` = 4096/4096/256 |
| Params inner | magic `VIPJAM_SHM_MAGIC` = `0x534D3456`, ver `VIPJAM_SHM_VERSION` = 5; hdr 16 B (`magic/ver/activeSlot@8/updateCount@12`); slot A `@16`, slot B `@1160`, each `VIPJAM_SHM_SLOT_SIZE` = 1144 B; James ext `@2304` (`VIPJAM_SHM_EXT_BASE`, magic `VIPJAM_SHM_EXT_MAGIC` `0x564A4558`, ver `VIPJAM_SHM_EXT_VERSION` 1, `VIPJAM_SHM_EXT_SIZE` 1792) |
| Bulk inner | two `VIPJAM_SHM_BULK_REGION` = 2048 B regions (DDC@0, Conv@2048), each hdr `VIPJAM_SHM_BULK_HDR` = 32 B, payload max `VIPJAM_SHM_BULK_MAX`; cmds `VIPJAM_BULK_{DDC=1, CONV_PATH=2, DDC_RESET=3, CONV_RESET=4, STREQ_TEXT=5, LIVEPROG_SCRIPT=6, VIPJAM_FULL=7}` |
| Status inner | magic/ver/seq/count + `VipJamStatus` (`enabled/configured/processedFrames/sampleRate/versionCode/versionName/arch`) |

Driver role (effect side is read-mostly):

- `mmap` params (read) + bulk (read) + status (write) on `open`.
- Poll `updateCount` (params hdr@12) + bulk `seq` each process block (or ~10 ms
  timer when idle); on change `vipjam_shm_read_viper/james` → apply to
  `VipJamChain` (`setFusedParam`, `loadDDC`, `loadIR`, `loadLiveProg*`).
- `vipjam_shm_status_write` heartbeat: `processedFrames`, `sampleRate`,
  `enabled`, version/arch (mirror what `hal/VipJamEffect.cpp` reports).
- Never `*_init` from the effect side if the app owns creation; open with
  `O_RDWR` + validate magic/ver and fail-open (bypass) on mismatch.

## 5. Config under `kEffectLibPath`, VINTF, sepolicy

- Effects land under the audio HAL effect-lib dir (`kEffectLibPath`, typically
  `/vendor/lib{,64}/soundfx/`); Magisk track copies
  `libv4a_aidl.so` there per-arch (mirror `vipjam-magisk` HIDL flow, new
  lib/effect names).
- `audio_effects.xml` (and `.conf` where still read): add
  `<library name="v4a_aidl" path="libv4a_aidl.so"/>` +
  `<effect name="v4a_standard_aidl" library="v4a_aidl"
  uuid="90380da3-8536-4744-a6a3-5731970e640f"/>`.
  NOTE (plan §16.11): Pixel factory hardcodes its list — XML ignored on
  Pixels; PIXAML-class shim out of v1 scope.
- VINTF: AIDL effects are served via the audio HAL `IFactory`; the manifest
  fragment must advertise the effect HAL (`hal format="aidl" name=
  "android.hardware.audio.effect"`) — copy the `dolby.mk` Soong/copy/VINTF
  shape (plan §7), VERIFY instance name per target (`default` vs vendor).
- Sepolicy — NO static `sepolicy.rule` ships for this yet; at `post-fs-data`
  the installer live-injects (via `magiskpolicy`) allows equivalent to:
  `allow hal_audio_t <effect_so>:file { map execute open read };`
  `allow hal_audio_t vipjam_data:file { read write map };`
  plus dir search on the SHM path. Coarse `shell_data_file`/`tmpfs` allows
  are a debug-only fallback. AML is NOT compatible with the AIDL track —
  installer must warn + skip (plan §2.5).

## 6. Why a Soong / AOSP-clang build is required (no NDK)

- `IEffect`/`IFactory`/FMQ are `libbinder_ndk`-UNSTABLE C++ AIDL: the NDK
  exposes only the *stable* (mostly Java/native-C) surface; the C++ effect
  base classes, FMQ (`android::hardware::common::fmq`), and
  `audio_effect` AIDL parcels change per release and are only guaranteed
  against the matching AOSP clang/libc++/headers. Building this `.so` with
  the NDK links a foreign `libc++_shared` + mismatched binder ABI → load-time
  or silent vtable breakage inside `audioserver`.
- So: build inside (or against headers/libs of) the target AOSP tree with
  Soong (`cc_library_shared`, `shared_libs` on the exact
  `android.hardware.audio.effect@…` + `libfmq` of that tree) using that
  tree's clang. The `Android.bp` sketch here is best-effort only.
- Per-version skew note (plan §4.4): vendors ship `V1/V2/V3-ndk` variants of
  the audio-effect AIDL (new `Parameter` arms, renamed fields). Mitigation:
  statically link our DSP (`libvipjam_engines`-equivalent) into the effect
  `.so`, but dynamically resolve NOTHING across AIDL versions — ship one
  `.so` per AOSP major (or pin oldest-supported and re-verify each bump).
  Universal-binary-via-`dlopen`/`dlsym` on C++ Binder internals is fragile;
  prefer per-version builds.

## 7. Files in this dir

- `VipJamAidlEffect.cpp` — UNCOMPILED skeleton: `IEffect` method shapes +
  `createEffect/queryEffect/destroyEffect` + SHM poll → `VipJamChain`.
- `Android.bp` — best-effort Soong sketch (needs a real AOSP tree to close).
- This `README.md` — the contract above.

## 8. Bring-up checklist (needs AOSP tree + device)

1. Diff `hardware/interfaces/audio/aidl` for the target version; fix method
   signatures, `Descriptor/Capability/Parameter` fields, factory export names.
2. Close `Android.bp` `shared_libs`/`export_include_dirs` against that tree.
3. `m libv4a_aidl` with the tree's clang; `adb push` to `kEffectLibPath`.
4. VINTF fragment + `audio_effects.xml` edit; reboot; `dumpsys` effect list.
5. SHM e2e: app `writeFullState` → poll `updateCount` → `processedFrames`
   heartbeat in `shm_status.bin`.
6. Sepolicy: `dmesg | grep avc` → promote live-injected allows into a static
   rule only after measured on target.
