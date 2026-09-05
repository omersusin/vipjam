#!/system/bin/sh
MODDIR=${0%/*}
LIBPATCH=`cat $MODDIR/libpatch.txt`
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

if [ -d "/odm/etc/" ]; then
  mount -o bind /data/adb/modules/vipjam/odm/etc/audio_effects.xml /odm/etc/audio_effects.xml
fi

if [ -f "$MODDIR/hires_enable" ]; then
  resetprop vendor.audio.capture.pcm.32bit.enable true
  resetprop persist.vendor.audio_hal.dsp_bit_width_enforce_mode 24
fi
