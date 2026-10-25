#!/usr/bin/env bash
# Repackage an APK under a new package name, re-sign it with a local key,
# and optionally push it to the Pura X's Download folder over hdc.
#
# Usage: ./repack.sh <app.apk> [-s suffix] [-p new.package] [-l "label suffix"] [--push]
#   -s  suffix appended to the package name (default: px -> com.example.app.px)
#   -p  explicit new package name (overrides -s)
#   -l  text appended to the launcher label, e.g. " PX"
#   --no-rename  only re-sign (e.g. a merged split APK), keep the package name
#   --push  send the result to the phone's Download folder
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
JAVA="${JAVA:-/opt/homebrew/opt/openjdk/bin/java}"
KEYTOOL="$(dirname "$JAVA")/keytool"
SIGNER="$HERE/tools/uber-apk-signer-1.3.0.jar"
HDC="${HDC:-$HOME/Library/OpenHarmony/sdk/toolchains/hdc}"
KS="$HERE/keys/sideload.jks"
KS_PROPS="$HERE/keys/sideload.properties"
PHONE_DIR="/storage/media/100/local/files/Docs/Download"

suffix="px"; new_pkg=""; label=""; push=0; no_rename=0; apk=""
while [ $# -gt 0 ]; do
  case "$1" in
    -s) suffix="$2"; shift 2 ;;
    -p) new_pkg="$2"; shift 2 ;;
    -l) label="$2"; shift 2 ;;
    --no-rename) no_rename=1; shift ;;
    --push) push=1; shift ;;
    -h|--help) sed -n '2,11p' "$0"; exit 0 ;;
    *) apk="$1"; shift ;;
  esac
done
[ -f "$apk" ] || { echo "usage: $0 <app.apk> [-s suffix] [-p pkg] [-l label] [--push]" >&2; exit 1; }

# One signing key for every repackaged app, so repackaged updates install over each other.
if [ ! -f "$KS" ]; then
  mkdir -p "$HERE/keys"; chmod 700 "$HERE/keys"
  pass="$(openssl rand -hex 24)"
  "$KEYTOOL" -genkeypair -keystore "$KS" -storepass "$pass" -keypass "$pass" \
    -alias sideload -keyalg RSA -keysize 4096 -validity 10000 \
    -dname "CN=PuraX Sideload" >/dev/null 2>&1
  printf 'storePassword=%s\nalias=sideload\n' "$pass" >"$KS_PROPS"
  chmod 600 "$KS" "$KS_PROPS"
  echo "Created signing key $KS (back it up: updates need the same key)"
fi
pass="$(sed -n 's/^storePassword=//p' "$KS_PROPS")"

work="$(mktemp -d "${TMPDIR:-/tmp}/repack.XXXXXX")"
trap 'rm -rf "$work"' EXIT

if [ "$no_rename" = 1 ]; then
  cp "$apk" "$work/unsigned.apk"
  tag="resigned"
else
  echo "==> Decoding $(basename "$apk")"
  apktool d -f -s -o "$work/dec" "$apk" >/dev/null

  old_pkg="$(sed -n 's/.*<manifest[^>]* package="\([^"]*\)".*/\1/p' "$work/dec/AndroidManifest.xml" | head -1)"
  [ -n "$new_pkg" ] || new_pkg="$old_pkg.$suffix"
  echo "==> Renaming $old_pkg -> $new_pkg"
  python3 -I "$HERE/tools/patch_manifest.py" "$work/dec" "$new_pkg" "$label" >/dev/null

  echo "==> Rebuilding"
  apktool b -o "$work/unsigned.apk" "$work/dec" >/dev/null
  tag="$new_pkg"
fi

echo "==> Aligning and signing"
"$JAVA" -jar "$SIGNER" -a "$work/unsigned.apk" -o "$work/signed" \
  --ks "$KS" --ksAlias sideload --ksPass "$pass" --ksKeyPass "$pass" \
  --allowResign >"$work/sign.log" 2>&1 || { cat "$work/sign.log"; exit 1; }

mkdir -p "$HERE/out"
out="$HERE/out/$(basename "${apk%.apk}")-$tag.apk"
mv "$(ls "$work"/signed/*.apk | head -1)" "$out"
echo "==> Built $out"

if [ "$push" = 1 ]; then
  echo "==> Pushing to phone $PHONE_DIR"
  "$HDC" file send "$out" "$PHONE_DIR/$(basename "$out")"
fi
