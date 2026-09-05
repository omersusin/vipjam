#!/system/bin/sh
# VipJam Audio Modification Library support script (aml.sh).
# Lets Audio Modification Library (AML) merge our effect into the
# framework audio_effects configs instead of our own boot-time patching.
# Self-contained: no patch_cfgs (broken on 64-bit-only ROMs).

MODID=vipjam
LIB=libvipjam.so
UUID=1b222930-cde3-5b6f-81a4-f67b3334a73e

# $1 = path to the merged audio_effects.xml being assembled by AML.
# Appends our <library> + <effect> entries if not already present.
add_to_xml() {
  FILE="$1"
  [ -f "$FILE" ] || return 0
  grep -q "$UUID" "$FILE" && return 0
  grep -q "<libraries>" "$FILE" || return 0
  sed -i "/<libraries>/ a\\        <library name=\"vipjam\" path=\"$LIB\"\/>" "$FILE"
  sed -i "/<effects>/ a\\        <effect name=\"vipjam_fused\" library=\"vipjam\" uuid=\"$UUID\"\/>" "$FILE"
}

# $1 = path to the merged audio_effects.conf being assembled by AML.
add_to_conf() {
  FILE="$1"
  [ -f "$FILE" ] || return 0
  grep -q "$UUID" "$FILE" && return 0
  grep -q "^libraries {" "$FILE" || return 0
  sed -i "s/^libraries {/libraries {\n  vipjam {\n    path \/vendor\/lib\/soundfx\/$LIB\n  }/g" "$FILE"
  sed -i "s/^effects {/effects {\n  vipjam_fused {\n    library vipjam\n    uuid $UUID\n  }/g" "$FILE"
}

case "$1" in
  xml) add_to_xml "$2" ;;
  conf) add_to_conf "$2" ;;
  *) echo "Usage: aml.sh {xml|conf} <file>" ;;
esac
