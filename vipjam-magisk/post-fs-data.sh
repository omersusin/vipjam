#!/system/bin/sh
MODDIR=${0%/*}

# Apply sepolicy early - must run here, before audioserver starts.
# service.sh is too late (audioserver already running, denials already hit).
if [ -f "$MODDIR/sepolicy.rule" ]; then
  magiskpolicy --live --apply "$MODDIR/sepolicy.rule" 2>/dev/null || true
fi

# NOTE: no live sed on /odm /system /vendor here.
# Config overlays ship as $MODDIR mirrors (system/vendor/odm)
# created at install time; Magisk magic-mounts them.

if [ -f "$MODDIR/hires_enable" ]; then
  resetprop vendor.audio.capture.pcm.32bit.enable true
  resetprop persist.vendor.audio_hal.dsp_bit_width_enforce_mode 24
fi
