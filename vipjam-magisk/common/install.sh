echo -n "$LIBPATCH" > "$MODPATH/libpatch.txt"

ui_print "    Copying VipJam driver..."
ui_print "    NOTE: AIDL mode conflicts with Audio Modification Library."
ui_print "    Disable AML if you use the AIDL driver."

cp_ch -n "$MODPATH/common/files/libvipjam_$ABI32.so" "$MODPATH$LIBDIR/lib/soundfx/libvipjam.so"
if [ "$IS64BIT" ]; then
cp_ch -n "$MODPATH/common/files/libvipjam_$ABI.so" "$MODPATH$LIBDIR/lib64/soundfx/libvipjam.so"
fi

ui_print "    Installing vipjam-ctl..."
mkdir -p "$MODPATH/system/bin"
cp_ch -n "$MODPATH/vipjam-ctl" "$MODPATH/system/bin/vipjam-ctl"
chmod 0755 "$MODPATH/system/bin/vipjam-ctl"

ui_print "    Patching audio_effects config files"
CFGS="$(find /odm /system /vendor -type f \( -name "*audio_effects*.conf" -o -name "*audio_effects*.xml" \) 2>/dev/null)"
for OFILE in ${CFGS}; do
  FILE="$MODPATH$(echo "$OFILE" | sed "s|^/vendor|/system/vendor|g")"
  cp_ch -n "$OFILE" "$FILE"
  case "$FILE" in
    *.conf)
        sed -i "/vipjam_fused {/,/}/d" "$FILE"
        sed -i "/vipjam {/,/}/d" "$FILE"
        sed -i "s/^[[:space:]]*effects {/effects {\n  vipjam_fused {\n    library vipjam\n    uuid 90380da3-8536-4744-a6a3-5731970e640f\n  }/g" "$FILE"
        sed -i "s/^[[:space:]]*libraries {/libraries {\n  vipjam {\n    path $LIBPATCH\/lib\/soundfx\/libvipjam.so\n  }/g" "$FILE"
        ;;
    *.xml)
        sed -i "/vipjam_fused/d" "$FILE"
        sed -i "/vipjam\" path=\"libvipjam/d" "$FILE"
        if ! grep -q 'library name="vipjam"' "$FILE"; then
          sed -i "/<libraries>/ a\        <library name=\"vipjam\" path=\"libvipjam.so\"\/>" "$FILE"
        fi
        if ! grep -q 'effect name="vipjam_fused"' "$FILE"; then
          sed -i "/<effects>/ a\        <effect name=\"vipjam_fused\" library=\"vipjam\" uuid=\"90380da3-8536-4744-a6a3-5731970e640f\"\/>" "$FILE"
        fi
        ;;
  esac
done

hires_unlock() {
  POLICIES="$(find /odm /vendor /system -type f -name "audio_policy_configuration.xml" 2>/dev/null)"
  for OFILE in ${POLICIES}; do
    [ -f "$OFILE" ] || continue
    FILE="$MODPATH$(echo "$OFILE" | sed "s|^/vendor|/system/vendor|g")"
    cp_ch -n "$OFILE" "$FILE"
    if grep -q "deep_buffer_24\|PCM_24_BIT_PACKED\|PCM_8_24_BIT" "$FILE"; then
      continue
    fi
    sed -i '/AUDIO_OUTPUT_FLAG_DEEP_BUFFER/a\        <profile name="deep_buffer_24" role="source" format="AUDIO_FORMAT_PCM_24_BIT_PACKED|AUDIO_FORMAT_PCM_8_24_BIT" samplingRates="44100|48000" channelMasks="AUDIO_CHANNEL_OUT_STEREO"\/>' "$FILE"
  done
  MIXERS="$(find /odm /vendor /system -type f -name "mixer_paths*.xml" 2>/dev/null)"
  for OFILE in ${MIXERS}; do
    [ -f "$OFILE" ] || continue
    FILE="$MODPATH$(echo "$OFILE" | sed "s|^/vendor|/system/vendor|g")"
    cp_ch -n "$OFILE" "$FILE"
    if grep -q 'hph-highquality-mode' "$FILE"; then
      continue
    fi
    sed -i '/<mixer>/ a\    <path name="hph-highquality-mode" \/>' "$FILE"
  done
}

if [ -f "$MODPATH/hires_enable" ]; then
  hires_unlock
fi
