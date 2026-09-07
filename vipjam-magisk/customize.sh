##########################################################################################
#
# MMT Extended Config Script - VipJam
#
##########################################################################################

##########################################################################################
# Config Flags
##########################################################################################

MINAPI=28
DYNLIB=true
#DEBUG=true

##########################################################################################
# Replace list
##########################################################################################

REPLACE="
"

##########################################################################################
# Permissions
##########################################################################################

set_permissions() {
  set_perm_recursive $MODPATH$LIBDIR/lib/soundfx 0 0 0755 0644
  chcon -R u:object_r:vendor_file:s0 $MODPATH$LIBDIR/lib/soundfx 2>/dev/null
  if [ "$IS64BIT" ]; then
    set_perm_recursive $MODPATH$LIBDIR/lib64/soundfx 0 0 0755 0644
    chcon -R u:object_r:vendor_file:s0 $MODPATH$LIBDIR/lib64/soundfx 2>/dev/null
  fi
  set_perm $MODPATH/vipjam-ctl 0 0 0755
  set_perm $MODPATH/aml.sh 0 0 0644
  set_perm $MODPATH/system/bin/vipjam-ctl 0 0 0755
}

##########################################################################################
# MMT Extended Logic - Don't modify anything after this
##########################################################################################

SKIPUNZIP=1
unzip -qjo "$ZIPFILE" 'common/functions.sh' -d $TMPDIR >&2
. $TMPDIR/functions.sh
# KSU-Next/Magisk only keep whitelisted paths in MODPATH; ensure our
# payload (install script + driver .so files + helpers) is really there.
unzip -qo "$ZIPFILE" 'common/*' 'vipjam-ctl' 'aml.sh' 'vipjam-app.apk' -d $MODPATH >&2
# VipJam driver install: copies libvipjam.so + vipjam-ctl, mirrors configs.
. "$MODPATH/common/install.sh"
LIBPATCH="$(cat "$MODPATH/libpatch.txt" 2>/dev/null)"
CFGS="$(find /odm /system /vendor -type f \( -name "*audio_effects*.conf" -o -name "*audio_effects*.xml" \) 2>/dev/null)"
for FILE in ${CFGS}; do
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
for R in "${NVBASE:-/data/adb}/modules/vipjam" /data/adb/ksu/modules/vipjam /data/adb/ap/modules/vipjam /data/adb/modules/vipjam; do
  if [ -f "$R/odm/etc/audio_effects.xml" ]; then MOD_SRC="$R/odm/etc/audio_effects.xml"; break; fi
done
if [ -d "/odm/etc/" ] && [ -n "$MOD_SRC" ]; then
  echo "Binding audio_effects.xml to odm partition..."
  mount -o bind "$MOD_SRC" /odm/etc/audio_effects.xml 2>/dev/null \
    || echo "odm bind skipped (non-fatal)"
fi

if [ "$BOOTMODE" = true ] && [ -f "$MODPATH/vipjam-app.apk" ]; then
  echo "Installing VipJam app..."
  if pm install -r "$MODPATH/vipjam-app.apk" >&2; then
    rm -f "$MODPATH/vipjam-app.apk"
  else
    echo "app install FAILED — APK kept at $MODPATH/vipjam-app.apk, install manually"
  fi
else
  echo "app install skipped (recovery mode or no APK bundled)"
fi
