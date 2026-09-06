# Don't modify anything after this
if [ -n "$INFO" ] && [ -f "$INFO" ]; then
  while read -r LINE; do
    case "$LINE" in
      *~) continue ;;
    esac
    if [ -f "$LINE~" ]; then
      mv -f "$LINE~" "$LINE"
    else
      rm -f "$LINE"
      DIR=$(dirname "$LINE")
      while [ "$DIR" != "/" ] && [ "$DIR" != "." ] && [ -n "$DIR" ]; do
        [ "$(ls -A "$DIR" 2>/dev/null)" ] && break || rm -rf "$DIR"
        DIR=$(dirname "$DIR")
      done
    fi
  done < "$INFO"
  rm -f "$INFO"
fi
for F in /vendor/etc/audio_effects*.xml /odm/etc/audio_effects*.xml /system/etc/audio_effects*.xml; do
  [ -f "$F" ] || continue
  [ -w "$F" ] || continue
  sed -i "/vipjam_fused/d" "$F" 2>/dev/null || true
  sed -i "/vipjam\" path=\"libvipjam/d" "$F" 2>/dev/null || true
done
for F in /vendor/etc/audio_effects*.conf /odm/etc/audio_effects*.conf /system/etc/audio_effects*.conf; do
  [ -f "$F" ] || continue
  [ -w "$F" ] || continue
  sed -i "/vipjam_fused {/,/}/d" "$F" 2>/dev/null || true
  sed -i "/vipjam {/,/}/d" "$F" 2>/dev/null || true
done
if command -v resetprop >/dev/null 2>&1; then
  for K in persist.vipjam.enabled persist.vipjam.profile; do
    resetprop --delete "$K" 2>/dev/null || resetprop "$K" "" 2>/dev/null || true
  done
fi
if command -v settings >/dev/null 2>&1; then
  settings delete global vipjam_cmd 2>/dev/null || true
  settings delete global vipjam_cmd_seq 2>/dev/null || true
fi
