#!/usr/bin/env bash
# One-command install onto the Pura X: prepare (download/merge/verify), push,
# drive the phone's installer, and if DroiTong blocks the package, rename +
# re-sign it and install again. No taps needed on the phone.
#
# Usage: ./install.sh <app.id | file.apk|.apks|.xapk|.apkm> [sideload.sh options]
#   e.g. ./install.sh ~/Downloads/app.apk
#        ./install.sh org.some.app --expect-cert <sha256>
# The phone must be unlocked with the screen on.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
HDC="${HDC:-$HOME/Library/OpenHarmony/sdk/toolchains/hdc}"
PHONE_DIR="/storage/media/100/local/files/Docs/Download"

[ $# -ge 1 ] || { sed -n '2,10p' "$0" >&2; exit 1; }

prepare() {
  "$HERE/sideload.sh" "$@" --no-push | tee /dev/stderr |
    sed -n 's/^==> Output: //p' | tail -1
}

install_on_phone() {
  local apk="$1" name
  name="$(basename "$apk")"
  echo "==> Pushing $name"
  "$HDC" file send "$apk" "$PHONE_DIR/$name" >/dev/null
  python3 -I "$HERE/tools/phone_install.py" "$name"
}

apk="$(prepare "$@")"
[ -n "$apk" ] || { echo "preparing the APK failed" >&2; exit 1; }

set +e
install_on_phone "$apk"; rc=$?
set -e

if [ "$rc" = 2 ]; then
  pkg="$(/opt/homebrew/opt/openjdk/bin/java -jar "$HERE/tools/APKEditor-1.4.9.jar" info -i "$apk" 2>/dev/null |
    sed -n 's/^package="\(.*\)"/\1/p' | head -1)"
  if [[ "$pkg" == *.px ]] || [[ " $* " == *" --repack "* ]]; then
    echo "Still blocked after repacking; DroiTong may be matching something other than the package name." >&2
    exit 2
  fi
  echo "==> Repacking and retrying"
  apk="$(prepare "$@" --repack)"
  install_on_phone "$apk"
else
  exit "$rc"
fi
