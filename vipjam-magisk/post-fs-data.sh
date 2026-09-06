#!/system/bin/sh
MODDIR=${0%/*}
LIBPATCH=$(cat "$MODDIR/libpatch.txt" 2>/dev/null)
[ -n "$LIBPATCH" ] || LIBPATCH="\/vendor"
CFGS="$(find /odm /system /vendor -type f -name "*audio_effects*.conf" -o -name "*audio_effects*.xml")"
for FILE in ${CFGS}; do
  case $FILE in
    *.conf)
        sed -i "/vipjam_fused {/,/}/d" $FILE
        sed -i "/vipjam {/,/}/d" $FILE
        sed -i "s/^effects {/effects {\n  vipjam_fused {\n    library vipjam\n    uuid 1b222930-cde3-5b6f-81a4-f67b3334a73e\n  }/g" $FILE
        sed -i "s/^libraries {/libraries {\n  vipjam {\n    path $LIBPATCH\/lib\/soundfx\/libvipjam.so\n  }/g" $FILE
        ;;
    *.xml)
        sed -i "/vipjam_fused/d" $FILE
        sed -i "/vipjam\" path=\"libvipjam/d" $FILE
        sed -i "/<libraries>/ a\        <library name=\"vipjam\" path=\"libvipjam.so\"\/>" $FILE
        sed -i "/<effects>/ a\        <effect name=\"vipjam_fused\" library=\"vipjam\" uuid=\"1b222930-cde3-5b6f-81a4-f67b3334a73e\"\/>" $FILE
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
