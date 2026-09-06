# VipJam Device Testing

Target tester: root user on KernelSU-Next, Android 16. HIDL track only.
AIDL (`vipjam_dsp/hal-aidl/`) is an uncompiled skeleton — not testable yet.

## 1. Get the builds

CI (`.github/workflows/android.yml`, workflow "VipJam CI") uploads:

| Artifact | Contains | Source path in CI |
|---|---|---|
| `vipjam-magisk-*.zip` (job `module-zip`) | Magisk/KernelSU module (`vipjam-magisk/`) with `libvipjam.so` per ABI | packed from `vipjam-magisk/` |
| `vipjam-debug-apk` (job `apk-debug`) | Debug app | `app-skeleton/app/build/outputs/apk/debug/*.apk` |
| `vipjam-release-apk` (job `release`) | Signed release app | `app-skeleton/app/build/outputs/apk/release/*.apk` |

Download from the GitHub repo Actions tab, run of "VipJam CI".
Use the debug APK for testing (release is signed with an ephemeral CI key).

## 2. Flash the module, reboot

1. Open the KernelSU-Next manager, Modules tab, Install from storage.
2. Select `vipjam-magisk-*.zip` (module id `vipjam`, see
   `vipjam-magisk/module.prop`).
3. Reboot when prompted. The installer (`vipjam-magisk/common/install.sh`,
   `customize.sh` with `DYNLIB=true`) places `libvipjam.so` under
   `lib/soundfx` and patches `audio_effects` config additively
   (never removes other FX).

## 3. Verify the driver: `vipjam-ctl status`

`vipjam-ctl` ships at the module root (`vipjam-magisk/vipjam-ctl`).
Run:

```
su -c vipjam-ctl status
```

Healthy output looks like this (values vary, structure does not):

```
== version ==
version=v0.1.0

== props (persist.vipjam.*) ==
(no persist.vipjam.* props set)

== audio_effects (vipjam blocks) ==
--- /vendor/etc/audio_effects.xml ---
... lines containing "vipjam" ...

== shm_status (first 64 bytes) ==
... hexdump of /data/local/tmp/vipjam/shm_status.bin ...
```

Failure shapes and what they mean:

- `(no vipjam entries in /vendor/etc/audio_effects*.xml|conf)` —
  installer did not patch. Re-flash, then check manager install log.
- `(not present: /data/local/tmp/vipjam/shm_status.bin)` — app has not
  pushed state yet. Open the app once (creates SHM) and re-run.
- `(no persist.vipjam.* props set)` — normal before first use.

Also useful:

```
su -c vipjam-ctl sessions   # dumpsys audio filtered for vipjam/effect
su -c vipjam-ctl version
su -c vipjam-ctl help       # full subcommand list
```

`status` + `diag` above are the diagnostics. `diag` prints stable
`KEY=value` lines for scripts; `status-json` is an alias.

## 4. Install the app, apply the Movie preset, toggle master

1. Install the debug APK from CI (`vipjam-debug-apk`).
2. Open VipJam. Tabs: Effects, Presets, TestTone, LiveProg, AutoEq,
   AppProfiles, Status (see `app-skeleton/.../ui/MainActivity.kt`).
3. Presets tab: apply `Movie` (ships in `presets/Movie.v3.json` and
   bundled at `app-skeleton/app/src/main/assets/presets/Movie.v3.json`).
   Or from a root shell:
   `su -c vipjam-ctl apply-preset /sdcard/Movie.v3.json`
4. Toggle master from the shell:
   `su -c vipjam-ctl toggle-master`
   (routes through the app service via `settings put global vipjam_cmd`).
   The KernelSU module WebUI (`vipjam-magisk/webroot/index.html`) has
   the same Master toggle.

What to listen for with the Movie preset: wider stereo image and
surround depth on dialogue, tighter bass without distortion at high
volume, clear center dialogue against effects. Toggle master off/on
mid-playback — the difference should be obvious within 1-2 seconds.
If toggling makes no audible difference, the effect is not attached
(see step 5).

## 5. Bug reports: what to capture

Run all of these while audio is playing with the preset applied:

```
su -c vipjam-ctl status > vipjam-status.txt
su -c vipjam-ctl sessions > vipjam-sessions.txt
logcat -d | grep -i vipjam > vipjam-logcat.txt
dumpsys media.audio_flinger | grep -i -A2 -B2 effect > vipjam-flinger.txt
getprop | grep persist.vipjam > vipjam-props.txt
```

Attach all five files plus: device model, Android version, KernelSU-Next
version, module version (`su -c vipjam-ctl version`), app version,
repro steps, and whether master toggle changes the sound.

## 6. Rollback

- Preferred: KernelSU-Next manager, Modules tab, remove `VipJam Fused
  DSP`, reboot. `vipjam-magisk/uninstall.sh` restores replaced files.
- If the device bootloops: reboot to safe mode (disables all modules),
  remove the module, reboot normally.
- App data: uninstall the app to clear presets/profiles.
