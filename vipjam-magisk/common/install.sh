echo -n $LIBPATCH > $MODPATH/libpatch.txt

ui_print "    Copying VipJam driver..."
ui_print "    NOTE: AIDL mode conflicts with Audio Modification Library."
ui_print "    Disable AML if you use the AIDL driver."

cp_ch -n $MODPATH/common/files/libvipjam_$ABI32.so $MODPATH$LIBDIR/lib/soundfx/libvipjam.so
if [ "$IS64BIT" ]; then
cp_ch -n $MODPATH/common/files/libvipjam_$ABI.so $MODPATH$LIBDIR/lib64/soundfx/libvipjam.so
fi

ui_print "    Installing vipjam-ctl..."
mkdir -p $MODPATH/system/bin
cp_ch -n $MODPATH/vipjam-ctl $MODPATH/system/bin/vipjam-ctl
chmod 0755 $MODPATH/system/bin/vipjam-ctl

ui_print "    Patching audio_effects config files"
CFGS="$(find /odm /system /vendor -type f -name "*audio_effects*.conf" -o -name "*audio_effects*.xml")"
for OFILE in ${CFGS}; do
  FILE="$MODPATH$(echo $OFILE | sed "s|^/vendor|/system/vendor|g")"
  cp_ch -n $OFILE $FILE
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
