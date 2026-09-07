#!/system/bin/sh
MODDIR=${0%/*}
LOG=/data/local/tmp/vipjam/service.log
mkdir -p /data/local/tmp/vipjam
exec >>"$LOG" 2>&1
echo "vipjam service start $(date)"

until [ "$(getprop sys.boot_completed)" = "1" ]; do
  sleep 5
done

resetprop -n ro.audio.ignore_effects false 2>/dev/null
if [ -n "$MODDIR" ] && [ -f "$MODDIR/sepolicy.rule" ]; then
  magiskpolicy --live --apply "$MODDIR/sepolicy.rule" 2>/dev/null
fi

for svc in audioserver mediaserver; do
  if pidof "$svc" >/dev/null 2>&1; then
    killall "$svc" 2>/dev/null
  fi
done
echo "vipjam service done"
