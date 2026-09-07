#!/system/bin/sh
MODDIR=${0%/*}
LOG=/data/local/tmp/vipjam/service.log
mkdir -p /data/local/tmp/vipjam
exec >>"$LOG" 2>&1
echo "vipjam service start $(date)"

tries=0
until [ "$(getprop sys.boot_completed)" = "1" ]; do
  tries=$((tries + 1))
  if [ "$tries" -ge 60 ]; then
    echo "vipjam service: boot_completed never flipped, giving up"
    exit 0
  fi
  sleep 5
done
sleep 15

resetprop -n ro.audio.ignore_effects false 2>/dev/null || echo "resetprop ignore_effects failed"
if [ -n "$MODDIR" ] && [ -f "$MODDIR/sepolicy.rule" ]; then
  magiskpolicy --live --apply "$MODDIR/sepolicy.rule" 2>/dev/null || echo "sepolicy apply failed"
fi

if [ -f "$MODDIR/system/lib64/soundfx/libvipjam.so" ] || [ -f "$MODDIR/system/lib/soundfx/libvipjam.so" ]; then
  for svc in audioserver mediaserver; do
    if pidof "$svc" >/dev/null 2>&1; then
      killall "$svc" 2>/dev/null || echo "killall $svc failed"
    fi
  done
else
  echo "vipjam driver .so missing in module, skipping audioserver restart"
fi
echo "vipjam service done"
