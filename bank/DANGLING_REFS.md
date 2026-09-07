# Dangling kernel refs (bank/presets-universal)

`convolver.kernelFile` (IR basenames), `ddc.device` (VDC basenames) and
`james.liveprog.scripts` (EEL basenames) below are **not bundled** in
`kernels/` (see `kernels/manifest.json`). Policy per
`presets/preset.schema.json`: the original name stays in the preset as
provenance, but the stage ships **disabled** (`enable: false`) so the engine
never resolves a missing file. `hadamVerb.eel` / `hpfloat.eel` are the
exception: their full script is embedded inline in `sourceText`, so those
stages stay enabled (self-contained, no file lookup).

## Convolver IR (.irs) — stage disabled, name kept

| kernelFile | referenced by |
|---|---|
| LoongFX-Default.irs | viper-v2--movie-v2.json |
| demo.irs | viper-v2--viper-v2-demo.json |
| Pod 4x12.irs | wstxda--60s-radio-headset-headset.json |
| Studer +1dB Updated.irs | wstxda--adi-bluetooth-bt-a2dp.json, wstxda--adi-headset-headset.json, wstxda--adi-speaker-speaker.json |
| Sony Xperia ((128K MP3)) Clear bass MAX.irs | wstxda--airblade-2-3-22-headset-headset.json |
| Samsung SoundAlive ((48k H-Edition)) 07.Clarity.irs | wstxda--akg-bass-headset.json, wstxda--akg-full-headset.json |
| More Warmth.irs | wstxda--akg-k518le-v2-headset-headset.json (already disabled) |

## DDC device (.vdc) — stage disabled, name kept

| device | referenced by |
|---|---|
| Custom_jmxc23.vdc | wstxda--60s-radio-headset-headset.json |
| JBL J22.vdc | wstxda--adi-headset-headset.json, wstxda--airblade-2-3-22-headset-headset.json |
| AKG EO-IG955.vdc | wstxda--akg-bass-headset.json, wstxda--akg-full-headset.json |

Enabled DDC entries use the device-reference shape (`{enable, device}`);
raw-coefficient shape (`sr44100`/`sr48000`, length % 5 == 0) is validated but
not shipped by any bank preset.

## LiveProg scripts (.eel)

| script | state |
|---|---|
| demo.eel (jamesdsp--james-headset-demo.json) | dangling: stage disabled, name kept |
| hadamVerb.eel (jamesdsp--hadamverb.json) | bundled inline via `sourceText`: stage enabled |
| hpfloat.eel (jamesdsp--hpfloat.json) | bundled inline via `sourceText`: stage enabled |

To re-enable a disabled stage, stage the file next to the preset per the
roadmap layout (`Kernel/`, `DDC/`, `LiveProg/`) or fetch a licensed copy, then
flip `enable` back to `true`.
