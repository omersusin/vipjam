# VipJam Roadmap — ViPER4AndroidFX-RE + JamesDSP, One App, One DSP

> Goal: VipJam is the baby/mix of ViPER4AndroidFX-RE and JamesDSP — a single
> Android app driving a single fused DSP library (`libvipjam.so`).
> Decisions: fused single `.so` (direct, license risk accepted) · chain
> James → ViPER · HIDL + AIDL dual · greenfield `com.vipjam` app ·
> TR + EN · 3 device profiles · presets file + link · wide root v1 ·
> HiRes/USB in v1. Sonara is fully out of scope — nothing from it is used.

---

## 0. Decisions log (everything the user locked)

| # | Question | Answer |
|---|----------|--------|
| 1 | GitHub repo visibility | Public — `https://github.com/omersusin/vipjam` |
| 2 | Which ViPER sources | ALL kept (likelikeslike DSP + app, WSTxda RE + releases) |
| 3 | DSP architecture | Single fused DSP (tek birleşmiş `.so`), not side-by-side |
| 4 | Chain order | James first (correct), ViPER second (color) |
| 5 | HAL generations | HIDL + AIDL dual (like ViPER v2.0) |
| 6 | App base | Greenfield `com.vipjam` UI (forked patterns from viper-app) |
| 7 | Languages | TR + EN first (ViPER has no TR; ZH/RU later) |
| 8 | Profiles | 3 per-device profiles (headset / speaker / bluetooth, James-style) |
| 9 | Preset sharing | BOTH file import/export AND share links |
| 10 | v1 scope | Wide root v1 (root + AutoEq + reorder + 446 DDC + link) |
| 11 | HiRes/USB DAC | IN v1 (follow-source-frequency + oversampling) |
| 12 | License conflict (GPLv2-only vs GPLv3) | Direkt tek dosya — risk accepted, ship all sources + notices |
| 13 | Sonara | IGNORED completely — no AI/brain, no code, no docs, no DB |

---

## 1. Repos & sources on disk (`~/vipjam/`)

| Dir | Source | Size | Role |
|-----|--------|------|------|
| `upstream-viperfx-re` | `likelikeslike/ViPERFX_RE` (fork of `AndroidAudioMods/ViPERFX_RE`), v2.0.x | 470K | DSP engine + dual (legacy/AIDL) Magisk modules |
| `upstream-viper-app` | `likelikeslike/ViPER4Android`, v2.0.5, `com.llsl.viper4android` | 1.7M | Modern Kotlin/Compose app (base patterns) |
| `upstream-jamesdsp` | `james34602/JamesDSPManager` | 34M | JamesDSP core + old Java app + CLI + ViPERToolBox |
| `upstream-wstxda-re` | `WSTxda/ViPERFX_RE` | 2.0M | Monolithic ViPER DSP source (real code, incl. `src/viper/`) |
| `upstream-wstxda-app` | `WSTxda/ViperFX-RE-Releases` | 7.4M | Releases only (M3 Expressive redesign, archived v8.0, NO code) |
| `docs/VIPJAM_ROADMAP.md` | this file | — | Master plan (all plans merged) |

Notes:
- `upstream-viperfx-re/ViPERDSP/` is an EMPTY submodule (`likelikeslike/ViPERDSP`
  via `.gitmodules`) — real ViPER code lives in `upstream-wstxda-re/src/viper/`.
- `upstream-wstxda-app` is code-less (README + Images only); UI reference = screenshots.
- `upstream-viperfx-re/.gitmodules` also references `src/viper/ffts`
  (`anthonix/ffts`, commented out in CMake) — not needed, James FFT code is used.

---

## 2. ViPER deep-dive

### 2.1 DSP engine structure
- WSTxda (monolithic, the reference):
  `src/ViPER4Android.{cpp,h}` (HAL entry) + `src/ViperContext.{h,cpp}` (command
  dispatch, PCM convert) + `src/viper/ViPER.{h,cpp}` (chain) +
  `src/viper/effects/` (~18: AnalogX, Convolver, VHE (+`VHE_L0..L4.h` tables),
  ViPERDDC, SpectrumExtend, IIRFilter, ColorfulMusic, DiffSurround,
  Reverberation, SpeakerCorrection, PlaybackGain, FETCompressor, DynamicSystem,
  ViPERBass, ViPERClarity, Cure, TubeSimulator, SoftwareLimiter) +
  `src/viper/utils/` (~25: AdaptiveBuffer, WaveBuffer, Biquad, CRevModel,
  FIR, PConvSingle, Polyphase, Crossfeed, DynamicBass, Harmonic, HiFi,
  NoiseSharpening, HighShelf, IIR_1st/NOrder, MinPhaseIIRCoeffs, MultiBiquad,
  PolesFilter, PassFilter, Stereo3DSurround, Subwoofer, TimeConstDelay,
  DepthSurround, CAllpassFilter, CCombFilter).
- likelikeslike: `src/{ViPER4Android.cpp,ViperContext.{h,cpp},include/essential.h}`
  + `ViPERDSP/` submodule + `CMakeLists.txt (v4a_re <- ViPERDSP + log)`.
- Chain (`ViPER.cpp:94 process(float* interleaved stereo)`):
  `WaveBuffer(Convolver → VHE) | AdaptiveBuffer → DDC → SpectrumExtend →
  IIR(10-band) → Colorful → DiffSurround → Reverb → SpeakerCorr →
  PlaybackGain → FET → Dynamic → Bass → Clarity → Cure → Tube → AnalogX →
  scale/pan → SoftwareLimiter ×2`.
- All processing float32 (`float*`), always stereo; PCM→float→process→PCM in
  `ViperContext`; viperfx-re adds FPCR/FTZ + gap/fade-in reset.
- `ViPER.h:26` members: `samplingRate` (def 44100), `adaptiveBuffer(2,4096)`,
  `waveBuffer(2,4096)`, `iirFilter(10)`, `frameScale/leftPan/rightPan`,
  18 effect objects + 2×`SoftwareLimiter`.
- Effect signatures: all `void Process(float*, uint32_t) + SetEnable(bool) +
  SetSamplingRate(uint32_t)` except Reverb/Tube/Limiter (SR-less),
  `Convolver/VHE: uint32_t Process(src,dst,frames)`,
  `FETCompressor: SetParameter(ENABLE/THRESHOLD/…​)` enum,
  `DynamicSystem: SetX/YCoeffs/SetSideGain/SetBassGain`,
  `ViPERBass: SetProcessMode(NATURAL/PURE_PLUS/SUBWOOFER)/SetSpeaker/SetBassFactor`,
  `ViPERClarity: SetClarity/SetMode(NATURAL/OZONE/XHIFI)`,
  `ViPERDDC: SetCoeffs(n, f44100*, f48000*)` (44100/48000 only),
  `Cure: SetPreset(cutoff, feedback)` presets `650/95, 700/60, 700/45`,
  `VHE: SetEffectLevel(0-4)`, tables `VHE_Lx_{44100,48000}_{L,R}[]`,
  `Convolver: SetKernel*/SetKernelBuffer/Prepare/CommitKernelBuffer/GetKernelID`,
  `PConvSingle` segment `0x1000`, cross-channel stub.

### 2.2 HAL interface & UUIDs
- Legacy HIDL: `audio_effect_library_t AUDIO_EFFECT_LIBRARY_INFO_SYM
  {create/release/get_descriptor}` + `effect_interface_s {process, command,
  get_descriptor}` in `ViPER4Android.cpp`.
- UUIDs (both RE): impl `90380da3-8536-4744-a6a3-5731970e640f`
  (`v4a_standard_re`), NULL type `ec7178ec-e5e1-4432-a3f4-4657e6795210`,
  lib `v4a_re` / `libv4a_re.so`.
- AIDL variant: same impl UUID, type `7261676f-6d75-7369-6364-28e2fd3ac39e`
  (DynamicsProcessing), lib `v4a_aidl`, effect `v4a_standard_aidl`.
- `effect_param_t {status, psize, vsize, data[]}` — value field always 32-bit
  aligned (`audio_effect.h`).

### 2.3 Params — classic + new
- Classic (`upstream-wstxda-re/src/ViPER4Android.h`): GET 1-11
  (enabled/config/streaming/samplerate/kernelID/versionCode/versionName/
  disableReason/message/packed-config/arch); SET `0x9002` UPDATE_STATUS,
  `0x9003` RESET; `65538-65543` convolver, `65544-45` HSurr, `65546-47` DDC,
  `65548-50` SpecExt, `65551-52` FIR-EQ, `65553-56` Field, `65557-58` Diff,
  `65559-64` Reverb, `65565-68` AGC, `65569-73` DynSys, `65574-77` Bass,
  `65578-80` Clarity, `65581-82` Cure, `65583` Tube, `65584-85` AnalogX,
  `65586-88` Gate, `65603` SpkOpt, `65610-26` FET.
- New (`upstream-viper-app …/viper/ViperParams.kt`, `0x10101-0x102A8`,
  65793-66216) with `ViperParamsLayout.kt` (1144B total, auto-generated by
  `ViPERDSP/tools/viper_layout_gen.py` from `ViPERDSP/include/ViPERParams.h`
  — submodule empty locally, reconstruct from Layout+Serializer):
  `0x10101` reset; `0x10110-12` Limiter@0:12; `0x10120-23` PGC@12:16;
  `0x10130-33` LUFS@28:16; `0x10140-50` FET(17)@44:68; `0x10160-64` Bass@112:20;
  `0x10170-74` BassMono@132:20; `0x10180-84` Psycho@152:20;
  `0x10190-92` SpecExt@172:12; `0x101A0-A3` EQ@184:132 (31 floats);
  `0x101B0-B5` Conv@316:8; `0x101C0-C1` DDC@324:1; `0x101D0-D3` Field@328:16;
  `0x101E0-E4` Diff@344:20; `0x101F0-F5` StereoImg@364:24;
  `0x10200-01` HSurr@388:8; `0x10210-15` Reverb@396:24;
  `0x10220-27` DynSys@420:32; `0x10230-32` Clarity@452:12;
  `0x10240-41` Cure@464:8; `0x10250` Tube@472:1; `0x10260-61` AnalogX@476:8;
  `0x10270` SpkCorr@484:1; `0x10280-93` MBC(20)@488:368;
  `0x102A0-A8` DynEQ(9)@856:288.
- `ViperContext::handleCommand`: INIT / SET_CONFIG (validates in==out FC/SR,
  STEREO, PCM_16/32/FLOAT else disable+reason) / RESET (`resetAllEffects`) /
  ENABLE / DISABLE / SET_PARAM (`vsize 4/8/12/16 → DispatchCommand(param,
  v1..v4)`, `256/1024` arrays, `8192` kernel chunks) / GET_PARAM /
  GET_CONFIG. `ViPER::DispatchCommand` maps `val/100.0f` to setters.
- `ViperDispatcher.kt:448-745 dispatchFullState` writes all via AudioEffect
  `setParameter`; `ViperParamsSerializer.kt` writes 21 blocks LE into SHM slot.

### 2.4 AIDL SHM protocol (`…/viper/ConfigChannel.kt:26-60`)
- Files `/data/local/tmp/v4a/shm_status.bin` (256B), `shm_params.bin` (4096B),
  `shm_bulk.bin` (4K = 2K DDC@0 + 2K Conv@2048). MAGIC `0x534D3456` VER 5.
- Status@20: enabled + config + frames + rate + verCode + verName64 + arch32.
- Params hdr16: magic/ver/active@8/count@12 + slotA/B (`ViperParamsLayout.SIZE`
  each; `16+2*1144=2304 ≤ 4096`); `writeFullState` flips slot + releaseFence.
- Bulk hdr32: seq@8/cmd@12/size@16, cmds 1=DDC 2=ConvPath 3=DDCreset 4=ConvReset;
  `writeBulkDdc(perRateFloats, coeffs)` payload `8+N*4`;
  `writeBulkConvolverPath(pathUTF8)`; init via `ensureInitialized/mapShm/
  createShmViaSu (mkdir+dd+chmod 666+chcon shell_data_file)`.
- VipJam: same protocol under `/data/local/tmp/vipjam/`.

### 2.5 Magisk module patching (both RE, identical)
- `module.prop` id `ViPER4Android-RE[-Fork]`; `customize.sh: DYNLIB=true`;
  `functions.sh`: API<26 → `DYNLIB=false`; DYNLIB → `LIBPATCH=/vendor,
  LIBDIR=/system/vendor`.
- `install.sh`: `echo -n $LIBPATCH > $MODPATH/libpatch.txt` (`post-fs-data.sh`
  reads it); copy `libv4a_re_$ABI32.so → $LIBDIR/lib/soundfx/` (+lib64 if 64bit).
- `.conf`: delete `v4a_standard_re/v4a_re` blocks, inject
  `effects { v4a_standard_re { library v4a_re uuid 90380da3-…​ } }` +
  `libraries { v4a_re { path $LIBPATCH/lib/soundfx/libv4a_re.so } }`.
- `.xml`: delete lines, add `<library name="v4a_re" path="libv4a_re.so"/>` after
  `<libraries>`, `<effect name="v4a_standard_re" library="v4a_re"
  uuid="90380da3-…​"/>` after `<effects>`.
- `post-fs-data.sh:20-22` binds `…/ViPER4Android-RE/odm/etc/audio_effects.xml →
  /odm/etc/audio_effects.xml` (STALE ID breaks Fork — fix in VipJam:
  use own module id).
- `VRE/customize.sh` sets perms + `chcon vendor_file:s0`; WST stub is no-op.
- No `sepolicy.rule`; AML-compatible for HIDL. AIDL module confirmed
  NOT compatible with AML — VipJam installer warns.

### 2.6 ViPER app (`com.llsl.viper4android`, v2.0.5)
- 100% Kotlin + Compose Material3 (NOT Expressive) + Hilt/Room/DataStore/
  Coroutines/OkHttp; min28/target37/compile37; Java/Kotlin 17; AGP 9.3.1,
  Kotlin 2.4.10, KSP 2.3.11, BOM 2026.08.00, Hilt 2.60.1, Room 2.8.4,
  nav 2.9.8, lifecycle 2.11.0, OkHttp 5.4.0, Gradle 9.7.0.
- Single-Activity, no fragments: `MainActivity` (starts service, edge-to-edge)
  → `ViperNavigation` (single route `main`) → `MainScreen + MainViewModel +
  EffectSections`; dialogs: Device/Preset/Settings(+Update)/Status/DebugLog.
- Sections order: MasterLimiter, PlaybackGain(AGC), LUFS, MultibandComp(5 tabs),
  FetComp, DDC, SpectrumExt, Equalizer(10/15/25/31 + curve graph + edit dialog),
  DynEQ(≤10 bands, tabs, Peak/LowShelf/HighShelf), Convolver, FieldSurround,
  DiffSurround(+reverse), StereoImager, HeadphoneSurround, Reverb, DynamicSystem,
  TubeSim(toggle), PsychoBass, ViperBass(+antiPop), ViperBassMono, ViPERClarity
  (Natural/Ozone/XHiFi), Cure, AnalogX(Mild/Med/Strong), SpeakerOpt(toggle).
- Widgets to copy verbatim: `EffectSection` (Switch+expand+long-press help +
  `AlertDialog` description), `LabeledSlider` (+`NumberInputDialog` with range
  validation, `Locale.US` format), `LabeledSwitch`, `LabeledDropdown`
  (+long-press delete), `EqCurveGraph` (spline tension .3, 180dp, grid
  -12..12dB), `EqEditDialog` (per-band slider + preset save/delete/reset),
  `RichText`, `resolvePresetName`.
- Service: `ViperService` LifecycleService, `START_STICKY`, FGS `specialUse`
  (`audio_effect_processing`) + `FOREGROUND_SERVICE_SPECIAL_USE`,
  `ACTION_START/STOP/TOGGLE_MASTER`; global effect (session 0) vs per-session
  `SparseArray<ViperEffect>`; `AudioSessionMonitor` (playback callback +
  privileged `activePlaybackConfigurations` reflection else root
  `dumpsys audio | grep -E 'state:started|new player'`, regex
  piid/sessionId/package, 5s timeout); `AudioOutputDetector`
  (`AudioDeviceCallback` + `getAudioDevicesForAttributes`, Tiramisu+ fallback);
  `BootCompletedReceiver` (BOOT_COMPLETED + auto_start → startForegroundService;
  NOTE: Android 15+ forbids `mediaPlayback` FGS from BOOT — keep `specialUse`);
  `MasterTileService` (QS tile toggles master).
- Data: `ViperRepository` (DAO + DataStore `viper_preferences`); Room
  `viper4android.db` v6 (`Preset[id,name,settings_json,…​]`,
  `EqPreset[name,name_key,band_count,bands]`, `DsPreset[…]`,
  `DeviceSettings[device_id,settings_json,…​]`); `EffectPrefs`
  serialize (`{schemaVersion=2,name,createdAt,effectKey→{jsonKey}}`,
  missing keys skipped, Int/Double clamped); DataStore key =
  `paramId` or `effectKey_jsonKey`, lists `;`-joined; storage
  `getExternalFilesDir/{Preset/*.json, Kernel/*.{wav,irs}, DDC/*.vdc}`.
- Driver I/O: HIDL `ViperDispatcher.dispatchFullState/setParameter` (+ DDC
  padded 256/1024, convolver `WavDecoder` → PREPARE/8192B chunks/COMMIT +
  CRC32); AIDL `ConfigChannel.writeFullState/writeBulkDdc/ConvolverPath`;
  `queryDriverStatus` 500ms loop (VipJam: 1000ms, only when Status open).
- Root: `RootShell` (`su` in PATH then
  `/system/{bin,xbin}/su,/sbin/su,/data/adb/{ksud,ksu/bin/ksud,apd},
  /sbin/magisk`, `id|uid=0` test, `su -c`, `copyFile` cp+mv+chmod 644);
  stages kernels to `/data/local/tmp/v4a/kernel/`.
- Misc: `WavDecoder` (RIFF/WAVE, fmt 1/3, 1-2ch, 16/24/32PCM+float32 → `FloatArray`;
  add `dataSize` cap in VipJam); `UpdateChecker` (GitHub releases API, apk asset,
  download+`FileProvider` install — fork OWNER/REPO); `FileLogger`
  (`filesDir/Log/viper.log`, 2MB rotation); `ViperEffect` reflection wrapper
  (`AudioEffect` ctor/param methods, UUID consts, LE `ByteBuffer` overloads);
  manifest perms (FGS/SPECIAL_USE/MODIFY_AUDIO_SETTINGS/BOOT/POST_NOTIFS/
  MODIFY_AUDIO_ROUTING/INTERNET/REQUEST_INSTALL_PACKAGES);
  `DISPLAY_AUDIO_EFFECT_CONTROL_SESSION` + QS_TILE intents; `FileProvider`;
  strings EN + zh-rCN + ru (325 lines each); theme M3 dynamic-color, NO dark
  toggle, NO `Type.kt`.

---

## 3. JamesDSP deep-dive

### 3.1 Core (`Main/libjamesdsp/jni/jamesdsp/`)
- Entry `jamesdsp.c`: `EffectCreate/GetDescriptor`,
  `AUDIO_EFFECT_LIBRARY_INFO_SYM`; descriptor type
  `f98765f4-c321-5de6-9a45-123459495ab2`, effect
  `f27317f4-c984-4de6-9a90-545759495bf2`, `"JamesDSP v4.01"/"James Fung"`.
- Pipeline `jdsp/jdspController.c`: `JamesDSPLib` struct (enableASRC,
  asrc[2], trueSampleRate, fs; per-effect structs+flags; postGain, limiter;
  blockSize/Max, tmpBuffer[6] deinterleaved stereo, mutex, HRTF blobs,
  impulseStorage); `JamesDSPInit(lib, n, sr)` (ASRC if sr<44100||>48000 →
  fs 44100/48000, limiter init, all constructors + disables);
  `JamesDSPSetSampleRate` (re-pick fs, realloc, RefreshBlob, force re-enable);
  `JamesDSPProcess(n)`: `Tube → Compander → Bass → EQ15 → ArbEQ → lock →
  Convolver → DDC → LiveProg → unlock → Crossfeed → StereoEnh →
  Reverb → inline limiter + postGain`.
- Effects (`jdsp/Effects/`, all `Process(JamesDSPLib*, n)` on
  `float tmpBuffer[0]/[1]`, `n==blockSize`):
  `vacuumTube` (gain -3..+12dB), `dynamic` compander (tc/gran/tfres + 7+2 pts),
  `dbb` BassBoost (maxG 0-15dB), `multimodalEQ` 15-band (PCHIP/Makima interp),
  `arbEqConv` (GraphicEQ string → FIR, min-phase), `convolver1D`
  (1/2/4ch → 2x2/2x4x2 single or TwoStage partitioned FFT; auto segmenting),
  keep James uniform-partitioned path (EasyEffects lesson: single-block
  convolution spikes CPU/latency past ~8192 taps and glitches on IR switch;
  partitioned stays real-time safe with no on-the-fly artifacts),
  `vdc` DDC (44100/48000 direct else `PeakingFilterResampler`), `liveprogWrapper`
  (NSEEL EEL VM), `crossfeed` (modes 0-5: BS2B + HRTF blobs), `stereoEnhancement`
  (mix 0-1), `reverb` (presets 0-18).
- Boundaries: int16/32/float multiplexed → float deinterleaved; mono unsupported.

### 3.2 Params (`EffectDSPMainCommand`)
- GET int: `19998` initCount, `19999` blockSize, `20000` blockMax, `20001` fs,
  `20002` pid, `30000-03` hash slots (arbEq/ddc/liveprog/conv).
- SET int16: `128` reverbMode, `137` widen/100, `188` xfeed 0-5, `150`
  tube/1000, `112` bassMaxGain, `1200-1206,1208,1210,1212,1213` enables,
  `10004/10006/10009/10010` commit triggers.
- SET int32: `25000-03` hash commit. SET float[3] `1500` limiter+postgain;
  float[17] `115` compander; float[32] `116` EQ.
- Bulk: `8888` str-alloc, `9999` conv-info[4], `12000` conv 4096 floats,
  `12001` str 256B chunks.
- **No numeric collision** with ViPER IDs — only semantic overlaps
  (DDC/EQ/conv/bass/comp/reverb/widen/tube/xfeed).

### 3.3 Java layer (`Main/DSPManager/src/james/dsp/`)
- `service/HeadsetService.java` (972L): opens `AudioEffect` by UUID via
  reflection; global session 0 (`modeEffect==0`) else per-session;
  routing bluetooth > headset > speaker; prefs file
  `james.dsp.{headset|speaker|bluetooth}`; receivers for audio sessions,
  HEADSET_PLUG/DOCK, A2DP; fs==0 → toast + reopen; all `setParameter` call
  sites with transforms (`/100`, `*1000`, `;`-split custom EQ strings,
  hash-skip resend).
- `activity/DSPManager.java + DSPScreen.java`: 3 tabs (Headset/Speaker/
  Bluetooth, identical 11 sections): Master+limiter/postgain, Compander,
  Bass, Multimodal EQ (IIR 8-order/PCHIP), ArbEQ (`GraphicEQ: f g;` string),
  Convolver (mode/advimp/resampler + `.irs/.wav/.flac/.mp3` scan of
  `sdcard/JamesDSP/Convolver/`), DDC (`*.vdc` scan), LiveProg (`*.eel` scan),
  Analog (tubedrive), StereoWiden, Crossfeed (BS2B 0-5), Reverb.
- `JdspImpResToolbox` (Java + `DSPManager/jni/main/JdspImpResToolbox.c`):
  `ReadImpulseResponseToFloat` (dr_wav/dr_flac + libsamplerate SRC_SINC_BEST,
  reject 0/3/>4ch, convModes incl. min-phase MPS), OfflineResample, ComputeEq/
  Comp responses. Reuse loader logic in VipJam kernel pipeline.
- Prefs keys `dsp.*` (master/compander/bass/tone/streq/convolver/ddc/liveprog/
  analog/stereo/xfeed/reverb) — import mapping table for VipJam migration.

### 3.4 Formats
- `.vdc`: `SR_44100:<csv>\nSR_48000:<csv>`, groups of 5 `b0,b1,b2,a1,a2`
  (a negated); samples: `Beyerdynamic DT770-80-4.vdc`, `Butterworth.vdc`,
  `FrontRearContrast.vdc`. Writer ref: `ViPERToolBox/frmMain.cs`.
- `.irs/.wav/.flac`: 1 mono-duplicated / 2 stereo / 4 true-stereo.
- `.eel`: requires `@init` + `@sample`, VM vars `srate/spl0/spl1`; samples
  `hadamVerb.eel` (Hadamard reverb+chorus), `hpfloat.eel`.
- DDC math (RBJ peaking, BW-octave) — `ViPERToolBox/Biquad.cs+DDCContext.cs`:
  `d=10^(g/40)`, `w=2πf/fs`, `α=sin w·sinh(ln2/2·BW·w/sin w)`,
  `B0=1+αd, B1=-2cos w, B2=1-αd, A0=1+α/d, A1=-2cos w, A2=1-α/d`,
  SOS `[B0/A0,B1/A0,B2/A0,-A1/A0,-A2/A0]`; export both SR lines.
  (Also: `timschneeb/DDCToolbox` Qt app, GPLv3 — magnitude+phase+group-delay
  plots, stability check, AutoEQ import, undo/redo — spec for VipJam DDC editor;
  companion `DDCToolbox-Android` app (basic features) proves the editor works
  on-device; desktop distribution via PPA/AUR/Snap.)

### 3.5 Build & credits
- ndk-build: `Application.mk` (`release, android-21,
  armeabi-v7a arm64-v8a x86, STL=none`); `jamesdsp/Android.mk`
  (`libjamesdsp`, ~90 `.c`, `-llog -Ofast -DJAMESDSP_REFERENCE_IMPL`,
  per-ABI NEON, `--gc-sections`).
- `Main/CLI/main.c` (585L): host-gcc offline harness (`mus.wav` →
  `mus.wav_Processed.wav`, VS2019 project only; port: stub
  `tchar/_strdup/GetCurrentDirectory`, link `jdsp/**/*.c -lm -lpthread`).
  Golden vectors: 3×`.vdc`, 2×`.eel`, `mus.wav` (+ referenced-but-missing
  IR wavs + 5 EEL scripts to recreate).
- Credits: James Fung (implementor), alankila (DSPManager base), Joseph Young,
  Christopher Blomeyer, ahrion, Zackptg5. Third-party: libsamplerate (BSD-2),
  NSEEL (Nullsoft/Cockos permissive), dr_wav/dr_flac/dr_mp3 (Unlicense/MIT-0),
  stb_sprintf (PD), WDL FFT (Cockos/Bernstein permissive), HPFloat (LGPL-2.1),
  `reverb.c` ©2016 Sean Connelly MIT.

---

## 4. Fused DSP blueprint (`vipjam_dsp/`, `libvipjam.so`)

### 4.1 Chain (James → ViPER)
```
PCM→float (ViperContext-style, FPCR/FTZ, gap/fade reset)
→ deinterleave
→ JAMES: Tube → Compander → Bass → EQ15 → ArbEQ → Convolver → DDC
         → LiveProg ×4 → Crossfeed → StereoEnh → Reverb
→ interleave
→ VIPER: Convolver → VHE → DDC → Spectrum → IIR → Colorful → Diff
         → Reverb → SpkCorr → PlaybackGain → FET → DynSys → Bass
         → Clarity → Cure → Tube → AnalogX → scale/pan → Limiter ×2
→ PCM (accumulate-aware, clamp)
```
Merges (single UI, single param): DDC (James parser+resampler wins, ViPER
`SetCoeffs` feeds it), Convolver (James loader + ViPER `PConvSingle 0x1000`),
Tube/Analog (one drive knob → both), Bass/EQ/Comp/Limiter (series, one page),
Reverb (two stages series, two tabs), XFeed (Cure presets + BS2B modes).

### 4.2 Param namespace `0x20000+` (=131072)
```
0x20001 master | 0x20010 Limiter(James1500+ViPER) | 0x20020 AGC(PGC+LUFS+comp115)
0x20030 FET+MBC | 0x20040 BASS(4 ViPER + James112) | 0x20050 EQ(EQ+DynEQ+arbEQ116)
0x20060 DDC | 0x20070 CONV | 0x20080 SPACE | 0x20090 REVERB | 0x200A0 DYNSYS
0x200B0 CLARITY+SPECEX | 0x200C0 XFEED(cure+bs2b) | 0x200D0 TUBE
0x200E0 OUT_VOL/PAN | 0x200F0 SPEAKER_CORR
```
Legacy shim: ViPER `0x101xx`/classic + James `112/1500/8888/12000…​` → `0x200xx`
(preset import compat). Bulk stays sub-commands. SHM layout regen via
`viper_layout_gen.py` pattern; keep `16+2*SIZE ≤ 4096` or bump + version.
Tube `/100` bugfix (alienware377 finding: upstream divided drive by 100 →
inaudible; fixed, range to 24dB) applied from day one.

### 4.3 Build merge (single CMake)
- Fill `ViPERDSP` sources from `upstream-wstxda-re/src/viper/` (+ fetch
  `likelikeslike/ViPERDSP` for v2.0 chain: MBC/DynEQ/LUFS/Psycho/St-Img).
- James ~90 `.c` into CMake; `ANDROID_STL=c++_static` (C files unaffected);
  global `-O3 -flto -fvisibility=hidden -fno-exceptions -fno-rtti
  -ffunction-sections/--gc-sections/--strip-all`; NEON per-ABI;
  `-DJAMESDSP_REFERENCE_IMPL`; silence `eel2/nseel` warnings.
- ABIs `arm64-v8a + armeabi-v7a` (x86 dropped); MIN_SDK DSP 21 / app 28.
- Sample-rate: native 44.1/48k; others via James ASRC; HiRes/USB:
  follow-source-frequency + kernel/DDC resample (`PeakingFilterResampler`);
  VHE tables are 44.1/48-only → bypass VHE off-rate + note; optional ×2
  oversampling toggle (Neutron pattern).
- Files: `vipjam_dsp/CMakeLists.txt`, `VipJamContext.cpp`
  (`AUDIO_EFFECT_LIBRARY_INFO_SYM` + `0x200xx` dispatch + legacy shim),
  `VipJamParams.h`, `VipJamChain.cpp` (interleave adapters + order).

### 4.5 Fused-build lessons (proven on host, Faz 2)

- `Crossfeed` name collision (James struct vs ViPER class, both global):
  split TUs — `james_bridge.c` (C, James only) + `viper_bridge.cpp`
  (C++, ViPER only) + `VipJamChain.cpp` (neither upstream header);
  shared `VipJamStages.h` (C-safe enum). Never include both in one TU.
- Upstream self-deadlock: `JamesDSPSetSampleRate(force=1)` holds the mutex
  while `ArbitraryResponseEqualizerEnable` re-locks it (non-recursive).
  VipJam calls with `force=0` + manual refresh sequence in the bridge.
- Upstream always-on path: `FETCompressor::Process` runs unconditionally
  (their `// TODO: enable check`) + software limiters always run, so
  "all off" is NOT bit-exact through `ViPER::process`. VipJam bypasses
  each block entirely when none of its stages are on (also the master-bypass
  semantic). Verified bit-exact by host test.
- JamesDSP benchmark thread sleeps 30s then burns CPU for minutes on weak
  hardware; host tests seed nothing and finish before it wakes. Keep
  `JAMESDSP_REFERENCE_IMPL` for device builds (tunes convolver partitions).
- Host build needs: `-include unistd.h` (usleep),
  `-Wno-incompatible-function-pointer-types -Wno-implicit-int` (JamesDSP),
  `-DVERSION_NAME/VERSION_CODE` (ViPER), `android/log.h` + `android/errno.h`
  stubs, `__android_log_print` definition; Termux `cc` cannot compile the C
   bridge (C++ header) — compile `.c` with clang, `.cpp` with clang++.
- Upstream `Convolver::SetKernelBuffer` segfaults on first append
  (`memcpy` to null `unknown1` — missing else-branch in RE code). VipJam
  bridge accumulates chunks itself and loads via direct `SetKernel` /
  `SetKernelStereo`, with own CRC32 + kernelId tracking (same skip-if-same
  semantics as `CommitKernelBuffer`).
- UI-scale → model-scale: ViPER setters take app units (reverb/clarity 0-100%
  → /100 like `DispatchCommand`; EQ dB, bass factor, FET params direct).
  AnalogX zeroes output for the first SR/4 frames (click-guard warmup) —
  tests must stream multiple `process()` calls like real audio.
- Status: 130 objects (85 James + 45 ViPER) in `libvipjam_engines.a`;
  `make test` 96/96 green on host (params/shim/interleave/chain/context/
  engine smoke/DDC both engines/IR both engines/LiveProg single+multi/
  parametric both engines/golden sine RMS 0.267).
### 4.4 Entry points
- HIDL: classic `effect_interface_s` (process/command/descriptor).
- AIDL: separate `libvipjam_aidl`-style driver behind `IEffect`
  (FLOAT_32 only, FMQ worker thread — NOT process callback);
  DynamicsProcessing type UUID; new impl UUID (generate v5).
- Pixel wall: Pixels hardcode effects (no `audio_effects_config.xml`);
  paths: custom HAL instance + `libaudiohal@aidl.so` patch, or lib swap,
  or LD_PRELOAD hook; Qualcomm = XML path. AIDL needs Soong/AOSP build
  (not NDK); watch   `V1/V2/V3-ndk` fragmentation (static-link or old-link); Pixel vendor libs
  (`vendor.google.whitechapel.audio.hal.services/effect.so`) hardcode the
  list; universal-binary idea is `dlopen`/`dlsym` at runtime (fragile in C++,
  prefer static link).

---

## 5. Best app blueprint (`com.vipjam`, greenfield)

- Stack: Kotlin 2.4.10, AGP 9.3.1, KSP aligned to Kotlin release, Compose BOM
  2026.08.00, Hilt 2.60.1, Room 2.8.4, DataStore 1.2.1, nav 2.9.8, lifecycle
  2.11.0, OkHttp 5.4.0, Coil, NDK 28.2/CMake 3.22.1; min28/target37/compile37.
- Nav: 3 profile tabs (Headset/Speaker/Bluetooth, James keys) × grouped single
  scroll: Clean (James order) → Color (ViPER order) → Dynamics; Presets tab;
  Device/Settings/Status/Debug sheets. M3 **Expressive**, dynamic color,
  in-app dark toggle (ViPER lacks it), TR+EN (new `values-tr/`, ZH/RU later), 48dp targets,
  headings + merged semantics, reduced-motion respect, tablet 840dp cap.
- State: one `UiState` per screen (4 buckets), `Channel` effects,
  `repeatOnLifecycle`; VM as `Actions` interface; content never sees VM.
- Data: DataStore `vipjam_prefs` (`.catch` IOException-only); Room `vipjam.db`
  v1 (Preset schema **v3**: `origin viper/james/vipjam` + settings_json;
  EqPreset; DeviceSettings); storage `Preset/*.json`, `Kernel/*.{wav,irs,flac}`,
   `DDC/*.vdc`, `LiveProg/*.eel`; import ViPER v2 JSON + `dsp.*` prefs +
   EAPO `GraphicEQ:`/`Filter:` text + REW + autoeq.app/squig.link.
   (done: `PresetImporter`/`PresetStore` v3 JSON + link import over DataStore
   with JVM unit tests in CI; Room upgrade deferred).
- EQ UI: band counts 10/15/25/31 + custom range (Poweramp pattern 5-32),
  parametric mode (per-band freq/Q/type), `EqCurveGraph` + edit dialog,
  DynEQ tabs, drag-band + long-press add/delete + undo (alienware pattern),
  MBC/FET sliders + autos, DDC/Conv/DynSys pickers, curve preview.
- Chain UI: **reorderable** (up/down, limiter pinned last — EasyEffects +
  alienware double-confirmed); ViPER-only mode (authentic naming/order/limiter,
  everything else off); collapsible plain-language explainers per section.
- Kernel library: convolver/DDC with search, custom sort, hide, groups +
  repo download (skip owned); **446 official DDC bundled**, auto-install.
- LiveProg: **4 chained scripts**, multi-select picker, mini IDE (highlight,
  console output, inline errors, auto-slider UI from script — JDSP4Linux pattern).
- Loudness: ISO-226:2023 compensator (8-biquad cascade, 50ms ramp, ref
  **80 phon**, `deviceVolume × appVolume`, order EQ → compensator → limiter;
  FineTune-PR lessons: no preamp, immutable swap, no strength slider).
  (done: `VipJamLoudness.{h,cpp}`, `VJ_STAGE_LOUDNESS` pinned pre-limiter,
  default off, 12 host tests incl. measured ISO contour).
- Extras: ISO-226 needs limiter (have it); channel balance; auto-preamp;
  DVC (unity-gain, Ainur+Poweramp double-confirmed); Movie/Game presets +
  dialogue enhance + leveler (Dolby pattern); per-app `Map<package,preset>` +
  per-device `Map<device,preset>` (James `james.dsp.*` routing generalized);
  `vipjam://preset?c=` link + Wavelet/Peace/Poweramp exports; update checker
  (forked endpoint); `vipjam.log` (2MB rotation); QS Tile + app shortcuts;
  Crowdin (TR first); F-Droid fastlane (EN+TR).
  (done: link pack/unpack in `tools/convert_v2_to_v3.py`, `--pack-link` /
  `--unpack-link`, self-tested round-trip; `presets/Movie.v3.json` +
  `presets/Game.v3.json` shipped).
- Service/root: `VipJamService` (FGS **specialUse**, START_STICKY, boot-safe on
  Android 15), global vs per-session, `AudioSessionMonitor` (callback +
  privileged reflection else root dumpsys), per-app mode also via
  **privileged system app** (`MODIFY_AUDIO_ROUTING`, no root) and optional
  **LSPosed broadcaster** companion (ExoPlayer/AudioTrack hooks, MIT);
  `RootShell` (su/ksud/apd/magisk), stage to `/data/local/tmp/vipjam/kernel/`,
  SHM 666 + `shell_data_file`; slider dispatch **120ms debounce**; status
  1000ms only when Status open.
- Rootless v2 (not v1): Shizuku/capture engine + app exclusion
  (`DUMP` + `PROJECT_MEDIA` grants), Spotify-ReVanced note, capture notification,
  extra capture latency accepted (documented limitation, same as upstream).

---

## 6. Cross-platform harvest (adopted into plan)

- **Wavelet** (no root, 5000+ AutoEq/Harman, ISO-226 loudness, subwoofer bass
  tuner, limiter+auto-preamp, channel balance, legacy mode, AIDL auto-detect,
  M3 Expressive): AutoEq DB + loudness + auto-preamp + balance + legacy fallback.
- **RootlessJamesDSP** (1.7k★, Shizuku/ADB setup, per-app exclusion, ReVanced
  patch docs, Crowdin, Tachiyomi backup/theme, Play+F-Droid): rootless v2
  recipe + Crowdin/F-Droid playbook.
- **alienware377 RootlessViPER** (16 native ViPER ports, reorderable chain,
  parametric drag/undo, studio echo/delay, 4× Liveprog, 446 DDC, lib search/
  groups/repo-dl, ViPER-only mode, classic theme+limiter, tube fix): the
  closest proof the fusion works — roadmap mirrors it + adds root/HIDL/AIDL.
- **Dolby ports** (reiryuki; object virtualizer, Dialogue Enhancer, Intelligent
  EQ Music/Movie/Game/Custom, Volume Leveler, AML-safe, freeze-stock-EQ):
  Movie/Game + dialogue + leveler.
- **Ainur Silmaril** (AINULINDALE 1:1 sampling, double-precision Bessel,
  DC-block IIR, TPDF dither + noise-shape, unity-gain interception,
  2×-oversampled soft-sat limiter with lookahead + inter-sample peak tracking
  via 4-tap half-band polyphase + analog tape-style (tanh) transfer curve
  preserving transients, HW DSP offload disabler, local jitter fixes,
  SoC mixer patches Q/MTK/Tensor/Exynos,
  `silmaril_useroptions` flags, debug section): unity-gain + TPDF + DC-block
  + soft-sat limiter in fused chain; SoC-aware installer flags.
- **EasyEffects** (10.1k★, 31 LV2 effects, reorder, per-app, autogain
  libebur128, RNNoise/DeepFilterNet, SoundTouch pitch, deesser, multiband
  gate/comp, maximizer): reorder + autogain + RNNoise-mic gate (v2).
- **JDSP4Linux** (PipeWire/GStreamer wrapper, script IDE, auto-UI from script,
  app exclusion, Flatpak): LiveProg IDE + auto-sliders.
- **CamillaDSP** (Rust, YAML pipeline, FIR+IIR, mixer/router, websocket,
  REW import, `pycamilladsp-plot`, 64-bit): YAML preset alt + WebSocket debug
  + REW import.
- **EqualizerAPO+Peace** (`GraphicEQ: f g;` == James ArbEQ syntax, `Filter: ON
  PK Fc Gain Q`, `Channel:` per-channel, 31 sliders × 9 speakers, preamp,
  hotkeys/tray/MIDI, hearing test, AutoEQ UI, TR language): EAPO import/export
  + per-channel EQ (v2) + hearing test (v2).
- **eqMac/FineTune** (per-app volume, multi-device, AutoEQ): per-app volume (v2).
- **Poweramp** (5-32 custom bands, parametric, DVC, Player Tracking, per-output
  presets, `.milk`, 64-band player EQ, Hi-Res/USB exclusive, LDAC): custom
  bands + DVC + per-output presets + follow-source.
- **Neutron** (32/64-bit engine, 4-60 parametric, FRC 5000+, R.A.C.E.
  ambiophonics, oversampling, dither, time-delay align, sub/ultrasonic filters,
  peak/RMS norm, ReplayGain, follow-source): oversampling + follow-source +
  R.A.C.E. evaluation.
- **UAPP** (custom USB driver 32/768kHz bypass, MorphIt 600, ToneBoosters,
  BT codec switch): USB follow-source + codec switch (v2).
- **KFR** (dual GPLv2/v3 — compatible; NEON DFT +80% ARM, SRC, FIR/IIR,
  EBU R128, `target-cpu=native`): v2 fast-path for convolver/SRC.
- **AutoEq** (16.2k★, MIT; Harman targets, 10× parametric optimizer w/ shelf,
  oratory1990/crinacle/Rtings, autoeq.app, PyPi): DB + build-time preset gen.
- **DDCToolbox** (GPLv3; project files, AutoEQ import, undo/redo, table edit,
  magnitude+phase+group-delay, IIR types, stability check, libMultivariateOpt):
  DDC editor gold spec.
- **AudioEffect-Broadcaster-Xposed** (MIT; ExoPlayer+AudioTrack hooks):
  optional LSPosed companion for per-app without root.
- **james-bond** (Go+Lit REST for headless JamesDSP, JWT, IR library, Docker):
  `vipjam-ctl` HTTP + WebUI backend pattern.
- **Clarifae** (RNNoise default + DTLN/DeepFilterNet fallback, dual
  capture+playback descriptors, WebUI→ctl→persist props, MMRL WebUI): mic-side
  AI noise v2 recipe.
- **AIDL research** (EffectHalAidl, EffectFactory, DynamicsProcessing
  conversion, vendor-extension fallback, Pixel hardcoded-effects wall):
  Section 4.4.
- **Loudness impls** (foobar ISO-226:2023 partitioned-conv, natambio C,
  JUCE 8-biquad, Rust 7-band, ALSA FIR-4096): Section 5 loudness spec.

---

## 7. Magisk module spec (`vipjam-magisk/`)

- `module.prop` (`id=vipjam`, version stamping like viperfx-re CI).
- `customize.sh` (`DYNLIB=true`, perms + `chcon vendor_file:s0`).
- `install.sh`/`post-fs-data.sh`: `libpatch.txt`, copy lib/lib64 `.so`,
  `.conf` + `.xml` sed patterns (Section 2.5) with **vipjam names + new UUID**,
  odm bind with **correct module id**, AML note (HIDL ok, AIDL warn+disable),
  SoC detection shims (Ainur-inspired flags file), AIDL Pixel path note.
- `webroot/index.html` (MMRL WebUI X compatible): status, master toggle,
  profile select, strength/bass/clarity sliders, kernel upload, DDC table
  editor (ViPERToolBox math: RBJ peaking, dual-SR export) + response canvas,
  logs/reset. Backend: `vipjam-ctl` → `config.conf` → `persist.vipjam.*`
  (Clarifae/james-bond pattern, rebootless apply).
- FX Compatible Mode (run alongside player EQs), stock-EQ freeze note,
  companion pairings (Jitter Silencer, USB sample-rate changer).

---

## 8. Crowdin + F-Droid recipes

- `crowdin.yml`: sources `values/strings.xml + strings_help.xml` →
  `values-%android_code%/…​`; TR first (recruit RootlessJamesDSP TR
  translators), EN parity, ZH/RU later; skip `app_name`/notifications/proper
  nouns; `effect_desc_*` highest priority.
- `app/fastlane/metadata/android/{en-US,tr}/`: title ≤50, short ≤80,
  full ≤4000, screenshots, `changelogs/<code>.txt` ≤500.
- fdroiddata YML: `GPL-3.0-or-later`, Multimedia, `AutoUpdateMode: Version v*`,
  Gradle `Fdroid` flavor, NDK r27 + CMake 3.22.1 prebuild, arm64+v7a,
  no blobs (only `-re` GPL sources), Magisk companion linked not shipped,
  no GMS/Crashlytics (no AntiFeatures).

---

## 9. License record

- JamesDSP `Main/LICENSE` = **GPLv2, no "or later" in sources** → GPLv2-only;
  ViPER app = GPLv3; HPFloat = LGPL-2.1; permissive bits (dr_*, stb, NSEEL,
  samplerate BSD-2, reverb MIT, Faust N/A) all GPLv3-compatible.
- Fusing into one binary under GPLv2-only + GPLv3 is a conflict. User decision:
  **direct single file anyway** — mitigate by shipping complete corresponding
  source + all notices, no commercial use (viperfx-re README also says
  "Not for commercial use"), keep all credits
  (Zhuhang/ViPER520, Martmists, Iscle, llsl, james34602, alankila, Young,
  Blomeyer, ahrion, Zackptg5, Sean Connelly).
- DDCToolbox/KFR paths are GPLv3-compatible; Xposed-broadcaster MIT;
  james-bond pattern (no code copy needed).

---

## 10. Verification plan (evidence before claims)

- TDD tracer bullets: `effect_param_t` parse → `0x200xx` shim → `parseVdc` +
  `DDCParser` gold (DT770 `.vdc`) → convolver CRC-skip → EEL load
  (`@init/@sample`) → SHM round-trip → Magisk sed dry-run → preset
  v2 + `dsp.*` import.
- Host harness: port `Main/CLI/main.c` to gcc (stub Win32 calls), golden
  vectors (3 `.vdc`, 2 `.eel`, `mus.wav` + recreate missing IR/EEL vectors).
- On-device matrix: HIDL (11-13) + AIDL (14+), Magisk/KSU/APatch,
  `grep vipjam /vendor/etc/audio_effects*`, dumpsys sessions, logcat, SHM xxd.
- CI: DSP matrix (NDK 27.2, 4→2 ABIs) → app (JDK17, lint+ktlint, unsigned+debug
  APKs) → Magisk zip; `convert_preset.py`-style smoke test.
- Gates: build exit 0, tests 0 failures, red-green proven for regression tests.

---

## 11. Phase plan

- **Faz 0** (done): sources cloned, mapping complete, this roadmap.
- **Faz 1** — DSP skeleton: `vipjam_dsp/` CMake + `VipJamContext.cpp` +
  `VipJamParams.h` (`0x200xx` + shim) + chain adapter + host harness +
  gold tests. App skeleton: `com.vipjam` (theme/nav/DI/DB/prefs) + service +
  root + Single-driver shim (both effects, James→ViPER) as fallback.
  (done + extended: `hal/VipJamEffect.cpp` system-effect wrapper with
  host-tested INIT/CONFIG/PARAM/PROCESS, `libvipjam.so` built in CI per ABI
  via ndk-build, signed release APK CI with ephemeral-key fallback).
- **Faz 2** — fused core: James block + ViPER block + single DDC/Conv/Tube +
  SHM + HIDL wiring + Magisk HIDL module + preset v2→v3 migration
  (migration done: `tools/convert_v2_to_v3.py` + fixtures + CI smoke test;
  SHM done: `VipJamShm.{h,cpp}` v5 layout, ViPER-compatible params slots +
  james ext block + bulk cmds, 41 host tests; HIDL `.hal` deferred — needs
  Soong/AOSP build, host-unverifiable; replaced at runtime by ndk-build
  `hal/VipJamEffect.cpp`, host-tested INIT/CONFIG/PARAM/PROCESS).
  REMAINING: EAPO/REW/autoeq import.
  (verified 2026-09-06: module packaging zip job is DONE — `module-zip`
  job in `.github/workflows/android.yml` stages `libvipjam.so` per ABI
  and uploads `vipjam-magisk-*.zip`; struck from REMAINING.)
  Docs convention from day one: `docs/<area>/HOW_TO.md`
  (Setup → Code → Integration → Test → Verify) + `.agent/skills`
  (james-bond pattern); every implementation PR updates its HOW_TO.
- **Faz 3** — AIDL driver + Pixel path + WebUI + kernel library + LiveProg IDE
  + AutoEq DB + link sharing + loudness + Movie/Game + Crowdin + F-Droid.
  (done: WebUI polish, kernel fetch `tools/fetch_kernels.py` + manifest,
  LiveProg mini-IDE tab, link codec, loudness compensator, Movie/Game
  presets, app preset import/store, tabbed app shell, JNI test-tone player,
  app→driver dispatch + status probe, `vipjam-ctl` status/sessions/diagnostics,
  HAL array protocol (EQ/DDC/IR), per-app profiles + mapping UI, AutoEq
  subsystem + browser UI, per-device preset memory, Hi-Res addon
  (`hires_unlock`), LoongFX presets, `aml.sh`, EQ curve editor UI,
  route-linked profiles, KSU-Next whitelist self-extract fix, installer
  actually installs driver, AIDL track scaffold (`vipjam_dsp/hal-aidl/`,
  UNCOMPILED skeleton per its README — still needs a Soong/AOSP build).
  REMAINING: AIDL driver compile + Pixel path; kernel in-app pickers +
  staging + DDC/IR push; LiveProg content → driver; AutoEq build-time
  preset gen; Crowdin; F-Droid; update checker; `vipjam.log`; app root
  ops — RootShell, kernel staging, SHM writer, AudioSessionMonitor,
  output routing (all absent from `app-skeleton/` as of 2026-09-06);
  reorderable chain UI (absent); Device/Settings sheets; dark toggle;
  ZH/RU; tablet layout.
  (verified 2026-09-06 against `git log` + file listing: struck EQ curve
  UI, per-app/per-device maps, AutoEq UI from REMAINING — all present.)
- **Faz 4** — HiRes/USB hardening, KFR fast-path, oversampling, DVC,
  per-channel EQ, hearing test, RNNoise mic gate, rootless Shizuku,
  exciter-4band, global Poweramp-style mode (experimental).

## 12. v2 pool (explicitly NOT v1)

Rootless capture mode, KFR swap, RNNoise/DeepFilterNet mic + playback gate,
per-channel EQ, audiogram hearing test (Peace pattern), 4-band exciter,
R.A.C.E. surround eval, MorphIt-style simulation, BT codec switch, Android Auto,
EEL→next-gen script language (Faust eval).

## 13. Parked — Sonara-sourced, excluded until Sonara is back in scope

These came from Sonara docs/screens and are NOT part of the plan:
QR preset sharing; frequency test tones; sleep timer; scheduled profiles;
BT-name auto-suggest memory + pre-warm from history; media-category auto-apply
(Music/Video/Film/Podcast/Streaming + notification); streak/digest
gamification; room-correction sweep; onboarding A/B "hear the difference";
AI auto-preset/personalization/federated models/lyrics-AI/needle/voice parser.

## 14. Minor topic finds (logged, not yet assigned to a phase)

- `daredoole/audio-calibration-mcp`: REW measurements → conservative EQ →
  JamesDSP/CamillaDSP/EAPO export (+verification). Candidate: VipJam
  room/speaker calibration import path (pairs with parked room-correction).
- `NainrousRC-X/Q-BW_Calculator`: Q↔BW converter based on DDCToolbox
  `BwCalculator.cpp`. Candidate: embed in DDC editor (WebUI + in-app).
- `Andiweli/RetroidJamesDSP`: device-specific JamesDSP port (Retroid Pocket).
  Note: per-device ports are viable; VipJam stays generic + per-device presets.
- `wambugu71/sautiflow`: Flutter + miniaudio + Viper DSP, cross-platform via
  FFI. Proof ViPER DSP runs off-Android; relevant if VipJam ever goes desktop.
- `avisek/AudioMods-Android`: Dirac + DolbyDigitalPlus + JamesDSP combo
  module. Proof stacked-mods coexist via AML; VipJam declares the same
  AML-friendly behavior for HIDL.
- `urain39/cyfanFX`: movie-tuned ViPER config generator (Python). Seed for
  VipJam Movie preset values.
- `jadilson12/Viper4Android-presets`: preset collection for V4A 2.7+.
  Seed corpus for built-in presets (license-check each before bundling).
- `eedeidk/PulseAudio-IRSs`: IRS collection (works with V4A too). Seed corpus
  for kernel library (license-check each).
- `jasyscom-corp` LiveProg crossover scripts (AT-2XDSP): seed scripts for the
  4× LiveProg library.
- `qumolangmo/wecho`: Shizuku + `DUMP`/`PROJECT_MEDIA` grants, per-device
  profiles, app blacklist, C-language custom DSP. Second confirmation of the
  rootless-v2 recipe (alongside RootlessJamesDSP).

---

## 15. Harvest log — adopted external work (all approved)

### 15.1 Lunaris Dolby deep-dive (`upstream-lunaris-dolby/`, Apache-2.0 code only; blobs proprietary — never redistribute)
Vendor ODM Dolby Atmos port (OnePlus DAX v3_6, 32-bit FX) with Kotlin priv-app.
Effect UUIDs: proxy `9d4921da-…`, sw `6ab06da4-…` (`libswdap_v3_6.so`),
hw `a0c30891-…` (`libhwdap_v3_6.so`); app opens session 0, priority 100.
ADOPTED into VipJam (in priority order):
1. **Per-device memory** — snapshot all params + 20-band EQ per output key
   (`bt_MAC`/`wired_headphones`/`builtin_speaker`), versioned restore
   (`SNAPSHOT_VERSION`). App target: extend `PresetStore` with device keys.
2. **Hot re-apply** — `AudioDeviceCallback` (save old / restore new) +
   `AudioPlaybackCallback` (apply on active) + recreate-on-audio-server-death
   with saved-profile restore. Service target: `VipJamService`.
3. **Per-app profiles without root** — `UsageStatsManager` foreground poll
   (2s) + 300ms debounced switch + headphone-only gate. Needs
   `PACKAGE_USAGE_STATS` + `QUERY_ALL_PACKAGES` + notification-listener.
4. **AutoEQ subsystem** — `HttpURLConnection` downloader + metadata/index
   cache + `LruCache(50)`, pointed at the AutoEq host. Extends
   `tools/fetch_kernels.py` into in-app curves.
5. **Priv-app shell** — `sharedUserId=android.uid.system`, `BOOT_COMPLETED`
   (locked-boot aware), `DISPLAY_AUDIO_EFFECT_CONTROL_PANEL` intent,
   `SummaryProvider`, QS tile, `FileProvider` preset import/export,
   `MusicFX/AudioFX` overrides, privapp-permissions XML.
6. **Shim recipe** — stub missing symbols (`GraphicBufferSource`-style) +
   `patchelf --add-needed/--replace-needed` for old blobs on new AOSP.
   Module target for any vendored-blob future.
7. **sepolicy → magiskpolicy** — translate `hal_dms*` rules when a HAL
   service ever ships; HIDL `.so` needs none (AML-compatible as today).
8. **Bringup pattern** — `dolby.mk` Soong/copy/VINTF shape, `rootdir` init
   (`mkdir /data/vendor/…`), empty device manifest to avoid dup C2.
   Reference only (no Soong in VipJam).

### 15.2 Scene survey 2025–2026 (adopted)
- **ViPERFX_RE (likelikeslike, alive v2.0.0 2026-07)**: ship dual
  legacy-`effect_param_t` + AIDL-SHM zips with in-app HAL auto-detect
  (AIDL mandatory A15+); per-device profiles + auto-switch; per-app mode
  via priv-app `MODIFY_AUDIO_ROUTING` else `su dumpsys` fallback; in-app
  log viewer. DSP notes: DiffSurround Reverse, ViPERBass fade-in, float32
  pipeline.
- **JamesDSP (semi-dead upstream; JDSP4Linux maintenance-alive;
  RootlessJamesDSP slow-alive)**: adopt D-Bus/CLI headless pattern +
  per-device preset rules sidecar; engine notes (compander TF param,
  convolver benchmark, tanh-softclip revert).
- **Dolby ports (ReiRyuki alive; Lunaris alive 2026-04)**: KSU install
  recipe (early-init mount, unmount-module flags, `data.cleanup=1`,
  `daxService` conflicts); Lineage `libutils.so` + Magisk 30.7
  `libmagiskpolicy.so` pinning.
- **Ainur Silmaril (alive v19.61 2026-07)**: staged hw/sw/env detect
  (Qcom/MTK/Tensor), `silmaril_useroptions` reinstall-to-apply pattern;
  mods list (SFX cleanup, mixer `hph-highquality-mode`, compander remover,
  attenuated volume curve).
- **AML (upstream dead; Ryuki-Mod alive v5.1)**: ship `aml.sh`, require it
  per mod, drop `patch_cfgs()`, copy Ryuki bind-mount/NoMount/skip-spatializer
  fixes; test with Ryuki AML + ACP Reborn.
- **Wavelet (alive 26.05 2026-05)**: auto-AIDL detection, enhanced session
  detection, BLE hearing-aid detect, M3 Expressive bits; loudness already
  mirrored in `VipJamLoudness`.
- **Hi-Res (ReiRyuki alive)**: policy-only 24/32-bit + `hph-highquality-mode`,
  verify via `dumpsys audio`. Module target.
- **Poweramp DVC lesson**: direct HW volume for headroom (bass without
  distortion); per-output override. Design note for limiter/volume work.
- **cyfanFX (dead, CC BY-SA)**: bundle LoongFX movie presets/IRS as
  starters with attribution (extends `presets/`).

### 15.3 Rootless v2 track (approved, post-v1)
MediaProjection + Shizuku + `PROJECT_MEDIA` (RootlessJamesDSP pattern,
second-confirmed by wecho/Shizuku+DUMP): capture engine, A15 screen-share
protection workaround note, ReVanced gap note, per-device profiles, app
blacklist. Stays parked behind system-effect v1.

---

## 16. Harvest log wave-2 — deep-dive agents + web research (all approved)

Clones (gitignored, research-only): `upstream-viperfx-re2`, `upstream-aml-ryuki`,
`upstream-cyfanfx`, `upstream-rootlessjdsp`, `upstream-jdspmgr`,
`upstream-jdsp4linux` (60M), `upstream-audiomods` (322M, scripts/configs only).

### 16.1 ViPERFX_RE fork delta: NONE
`diff -rq` vs base is empty (same commit `306a606`). AIDL `libv4a_aidl.so`
lives in releases, not source. Lesson: ship dual legacy+AIDL zips with
in-app HAL auto-detect (adopted §15.2).

### 16.2 AML Ryuki-Mod v1.3_RM — adopt full 15-fix recipe
`/data` remount-rw; find `/system /odm /my_product /vendor`; NoMount
metamodule support; acdb post-fs-data stash/restore (ACP Reborn compat);
skip `*spatializer*/*haptic*`; `osp_detect music` strips competing OSP FX;
boot-time perms reset + `chcon vendor_file`; dual `aml.sh`/`.aml.sh`;
restart audioserver + kill MTK audio services; split-script installer;
fixed uninstall restore; `sepolicy.rule` audioserver allows. Ship `aml.sh`
(no `patch_cfgs`), keep id ≠ aml, test NoMount present/absent.

### 16.3 cyfanFX (CC BY-SA 4.0 presets, IRS excluded)
8× v2 SharedPrefs XML (Movie/Music × headset=bluetooth=usb/speaker) +
`xml2prf.py`/`prf2xml.sh` converters. Bundle the 7 unique XMLs into
`presets/` with attribution; `LoongFX-Default.irs` (149KB, Archy, no grant)
stays OUT. Confirms Movie v3 values (VSE 0.59, reverb 45/20/59/8/33,
bass 57Hz/150, clarity 50, EQ V-curve).

### 16.4 Rootless recipe (RootlessJamesDSP, confirms §15.3)
MediaProjection intent + `getMediaProjection` + `Callback.onStop`;
FGS `media_projection`; capture config `MEDIA/GAME/UNKNOWN`, rate clamp
44100–48000; perms `DUMP` + `PROJECT_MEDIA` (+ Shizuku auto-grant code);
session sources (AudioService dump / AudioPolicy dump / open-close intents /
MediaSession+NLService / polling), accept `USAGE_MEDIA/GAME/UNKNOWN`, drop
sid 0/self/excluded; ReVanced gap list (Spotify/Chrome/SoundCloud);
`PowerStateReceiver` automation; suspend-on-idle; `files/profiles/<id>/`
per-device store; UID blacklist UI.

### 16.5 JamesDSPManager app techniques — adopt
Global-only mode (`modeEffect==0` → session 0, skip session map) as VipJam
v1 default; zero-rate resurrect; `HashString` skip-if-equal for chunked
strings (saves the 8888/12001/1000x round-trip when unchanged); preset
backup/restore by copying `shared_prefs` XMLs; `IMPORTANCE_NONE` persistent
notification pattern; full-push order (limiter→compander→bass→EQ→streq→
reverb→widen→xfeed→tube→DDC/liveprog/convolver) mirrors our applier.

### 16.6 JDSP4Linux — adopt rules + benchmark, note the rest
`preset_rules.json` (`deviceName/deviceId/preset/routeName/routeId`,
exact→wildcard fallback) = format for our per-device memory (§15.1-1).
Convolver benchmark-on-boot + cache; limiter clamp `>-0.09/<0.15`;
compander `tfresolution`; EEL `tanh/atanh` (needs upstream EEL2 sync —
note only). D-Bus/CLI/HEADLESS are Linux-only — doc reference.

### 16.7 AudioMods stacking — adopt rules + helpers
Install order Dirac→DDP→JamesDSP; additive UUIDs
(`dirac e069d9e0…`, `dsplus 9d4921da…`, `jdsp f27317f4…`); keep QCOM
proxies; legacy `.conf` keeps dsplus+jdsp only; `cp_ch/mk_ch` helpers;
`libstdc++` bidirectional fix; prop-append pattern; LesserAudioSwitch BT
workaround; verify via `dumpsys media.audio_flinger | grep name`.
V1 rule: VipJam installs additively, never removes other FX.

### 16.8 Lunaris app UI — adopt pieces for Effects-tab upgrade
Band-mode selector (10/15/20) with incompatible-mode edit guard; draggable
frequency-response curve (cubic, ±15dB, tooltips); vertical per-band
sliders committing on release; preset dropdown + profile carousel;
per-app-profile screen (search + dropdown + usage-stats gate UI); nav shell
(pager + floating toolbar); `squishable` press + haptics; Expressive
shapes/motion; triple-state (Loading/Success/Error) screens.

### 16.9 Wavelet 2026 (v26.05) — adopt import stack + calibration
Strict 127-freq `GraphicEQ:` parser (reject anything else); parametric
preamp enforcement (`-maxGain`); 3-stage anti-clip (normalize import,
auto-attenuator, limiter+auto-post-gain); ISO226 volume-threshold
calibration UX (matches our `setVolume` + thresholdDb design); session
fallback chain normal→legacy→DUMP+NotificationListener; BLE hearing-aid
as BT device; per-device profiles; 9-band graphic + bass tuner reference.

### 16.10 Hi-Res — adopt as module addon
Correct repos: `reiryuki/Hi-Res-Audio-Enabler-Magisk-Module` (Qcom, v3.12)
+ `adivenxnataly/Hi-ResAudio` (MTK). Recipe: `deep_buffer_24`
(policy conf+XML), `bit_width 24` platform info, `hph-highquality-mode`
mixer path, `resetprop` bit-width props + audioserver restart, optionals
via `/data/media/*/optionals.prop`. Verify: `dumpsys media.audio_flinger`
(DIRECT 192kHz 24-bit) + `alsa hw_params`. Note: exclusive/BIT_PERFECT
USB bypasses all DSP — speaker/wired paths only.

### 16.11 AIDL verdict 2026 — start track now (approved)
Mandatory on 15+ launching devices; HIDL unreliable there. Mechanics:
impersonate `DynamicsProcessing` type (`7261676f-…`) + own UUID (Pixel
factory hardcodes its list — XML ignored on Pixels, needs PIXAML-class
shim, out of v1 scope); SHM control channel (app mmap, driver polls);
Soong-only build (NDK C++ ABI unstable — needs AOSP clang per version);
config under `kEffectLibPath` + VINTF `IFactory`; sepolicy
`hal_audio_t … map/execute + data rw/map` via post-fs-data live-inject.
Track: `hal-aidl/` Soong module + app AIDL auto-detect alongside HIDL.

### 16.12 Module tech 2026 — adopt now
Full-replacement `audio_effects.xml` (+odm/my_product/system copies,
exclude haptics) instead of boot-time sed; `webroot/` + `www/` dual for
KSU/APatch/MMRL; `ksu.exec` feature-detect (`KernelSU||ksu||mmc`),
persist in `/data/adb/modules/<id>/`; static `sepolicy.rule`
(bundle Magisk 30.7 `libmagiskpolicy.so` pattern); MMT-EX retired →
official installer template; magic-mount preferred, `service.sh`
shadow-copy + audioserver restart fallback; skip work when AIDL detected
on 15+ launchers (log it).
