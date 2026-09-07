#!/system/bin/sh
MODDIR=${0%/*}
LIBPATCH=$(cat "$MODDIR/libpatch.txt" 2>/dev/null)
[ -n "$LIBPATCH" ] || LIBPATCH="\/vendor"
find /odm /system /vendor -type f \( -name "*audio_effects*.conf" -o -name "*audio_effects*.xml" \) 2>/dev/null | while IFS= read -r FILE; do
  [ -f "$FILE" ] || continue
  case "$FILE" in
    *.conf)
        sed -i "/vipjam_fused {/,/}/d" -- "$FILE"
        sed -i "/vipjam {/,/}/d" -- "$FILE"
        sed -i "s/^[[:space:]]*effects {/effects {\n  vipjam_fused {\n    library vipjam\n    uuid 90380da3-8536-4744-a6a3-5731970e640f\n  }/g" -- "$FILE"
        sed -i "s/^[[:space:]]*libraries {/libraries {\n  vipjam {\n    path $LIBPATCH\/lib\/soundfx\/libvipjam.so\n  }/g" -- "$FILE"
        ;;
    *.xml)
        sed -i "/vipjam_fused/d" -- "$FILE"
        sed -i "/vipjam\" path=\"libvipjam/d" -- "$FILE"
        if ! grep -q 'library name="vipjam"' -- "$FILE"; then
          sed -i "/<libraries>/ a\        <library name=\"vipjam\" path=\"libvipjam.so\"\/>" -- "$FILE"
        fi
        if ! grep -q 'effect name="vipjam_fused"' -- "$FILE"; then
          sed -i "/<effects>/ a\        <effect name=\"vipjam_fused\" library=\"vipjam\" uuid=\"90380da3-8536-4744-a6a3-5731970e640f\"\/>" -- "$FILE"
        fi
        ;;
  esac
done

MOD_SRC=""
for R in "$MODDIR" "${NVBASE:-/data/adb}/modules/vipjam" /data/adb/ksu/modules/vipjam /data/adb/ap/modules/vipjam /data/adb/modules/vipjam; do
  if [ -f "$R/odm/etc/audio_effects.xml" ]; then MOD_SRC="$R/odm/etc/audio_effects.xml"; break; fi
done
if [ -d "/odm/etc/" ] && [ -n "$MOD_SRC" ]; then
  mount -o bind "$MOD_SRC" /odm/etc/audio_effects.xml 2>/dev/null || true
fi

if [ -f "$MODDIR/hires_enable" ]; then
  resetprop vendor.audio.capture.pcm.32bit.enable true
  resetprop persist.vendor.audio_hal.dsp_bit_width_enforce_mode 24
fi
