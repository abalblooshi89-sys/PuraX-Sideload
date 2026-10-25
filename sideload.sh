#!/usr/bin/env bash
# Get an Android app onto the Pura X through DroiTong: download (or take a
# local file), merge split bundles, rename + re-sign if DroiTong blocks it,
# and push the result to the phone's Download folder.
#
# Usage: ./sideload.sh <app.id | file.apk|.apks|.xapk|.apkm> [options]
#   -d SOURCE        download source for an app ID: f-droid (default) or apk-pure
#   --apkpure-risk   required with -d apk-pure: APKPure has shipped modified,
#                    malware-laden APKs; pin the signer with --expect-cert
#   --expect-cert H  abort unless the original signer SHA-256 equals H
#   --repack         rename + re-sign (and remember the app as blocked)
#   -s SUFFIX / -l LABEL   passed to repack.sh (package suffix / label suffix)
#   --no-push        build only, don't send to the phone
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
JAVA="${JAVA:-/opt/homebrew/opt/openjdk/bin/java}"
SIGNER="$HERE/tools/uber-apk-signer-1.3.0.jar"
EDITOR_JAR="$HERE/tools/APKEditor-1.4.9.jar"
HDC="${HDC:-$HOME/Library/OpenHarmony/sdk/toolchains/hdc}"
BLOCKED="$HERE/blocked.txt"
PHONE_DIR="/storage/media/100/local/files/Docs/Download"

source_="f-droid"; apkpure_ok=0; expect=""; repack=0; push=1; input=""
repack_args=()
while [ $# -gt 0 ]; do
  case "$1" in
    -d) source_="$2"; shift 2 ;;
    --apkpure-risk) apkpure_ok=1; shift ;;
    --expect-cert) expect="$(echo "$2" | tr -d ':' | tr 'A-F' 'a-f')"; shift 2 ;;
    --repack) repack=1; shift ;;
    -s|-l) repack_args+=("$1" "$2"); shift 2 ;;
    --no-push) push=0; shift ;;
    -h|--help) sed -n '2,15p' "$0"; exit 0 ;;
    *) input="$1"; shift ;;
  esac
done
[ -n "$input" ] || { sed -n '6,15p' "$0" >&2; exit 1; }

work="$(mktemp -d "${TMPDIR:-/tmp}/sideload.XXXXXX")"
trap 'rm -rf "$work"' EXIT

# 1. Get the file.
if [ -f "$input" ]; then
  src="$input"
else
  dl="$HERE/downloads/$input"
  mkdir -p "$dl"
  case "$source_" in
    f-droid) apkeep -a "$input" -d f-droid "$dl" ;;
    apk-pure)
      [ "$apkpure_ok" = 1 ] || { echo "APKPure needs --apkpure-risk (it has distributed tampered APKs)" >&2; exit 1; }
      apkeep -a "$input" -d apk-pure -o 'acknowledge_dangers=true,arch=arm64-v8a' "$dl" ;;
    *) echo "unknown source: $source_" >&2; exit 1 ;;
  esac
  src="$(ls -t "$dl"/*.apk "$dl"/*.xapk "$dl"/*.apks "$dl"/*.apkm 2>/dev/null | head -1 || true)"
  [ -n "$src" ] || { echo "download failed: nothing in $dl" >&2; exit 1; }
fi
echo "==> Input: $src"

# 2. Bundle (several APKs inside a zip) or plain APK?
bundle=0
if unzip -Z1 "$src" 2>/dev/null | grep -qiE '^[^/]*\.apk$'; then bundle=1; fi

# 3. Original signer: of the APK itself, or of the base APK inside a bundle.
check="$src"
if [ "$bundle" = 1 ]; then
  base="$(unzip -Z1 "$src" | grep -iE '^base\.apk$' | head -1 || true)"
  [ -n "$base" ] || base="$(unzip -Zl "$src" | awk '$NF ~ /\.apk$/ && $NF !~ /^config\./ {print $4, $NF}' | sort -rn | head -1 | cut -d' ' -f2)"
  unzip -q -o "$src" "$base" -d "$work/base"
  check="$work/base/$base"
fi
cert="$("$JAVA" -jar "$SIGNER" -a "$check" --onlyVerify 2>&1 | sed -n 's/.*SHA256: \([0-9a-f]*\) .*/\1/p' | head -1)"
echo "==> Original signer SHA-256: ${cert:-<unsigned or unreadable>}"
if [ -n "$expect" ] && [ "$cert" != "$expect" ]; then
  echo "Signer does not match --expect-cert; refusing to continue." >&2
  exit 1
fi

# 4. Merge split bundles into one APK (DroiTong installs single APKs only).
apk="$src"
if [ "$bundle" = 1 ]; then
  echo "==> Merging split bundle"
  name="$(basename "$src")"; name="${name%.*}"
  "$JAVA" -jar "$EDITOR_JAR" m -i "$src" -o "$work/$name.apk" -clean-meta -f >"$work/merge.log" 2>&1 ||
    { cat "$work/merge.log" >&2; exit 1; }
  apk="$work/$name.apk"
fi

pkg="$("$JAVA" -jar "$EDITOR_JAR" info -i "$apk" 2>/dev/null | sed -n 's/^package="\(.*\)"/\1/p' | head -1)"
echo "==> Package: $pkg"
if [ -f "$BLOCKED" ] && grep -qxF "$pkg" "$BLOCKED"; then
  echo "    (listed in blocked.txt, repacking)"
  repack=1
fi

# 5. Rename + re-sign when DroiTong blocks it; otherwise only re-sign merged bundles.
if [ "$repack" = 1 ]; then
  final="$("$HERE/repack.sh" "$apk" ${repack_args[@]+"${repack_args[@]}"} | tee /dev/stderr | sed -n 's/^==> Built //p')"
  grep -qxF "$pkg" "$BLOCKED" 2>/dev/null || echo "$pkg" >>"$BLOCKED"
elif [ "$bundle" = 1 ]; then
  final="$("$HERE/repack.sh" "$apk" --no-rename | tee /dev/stderr | sed -n 's/^==> Built //p')"
else
  mkdir -p "$HERE/out"
  final="$HERE/out/$(basename "$apk")"
  [ "$apk" -ef "$final" ] || cp "$apk" "$final"
  echo "==> Unmodified original: $final"
fi

echo "==> Output: $final"

# 6. Push.
if [ "$push" = 1 ]; then
  echo "==> Pushing to phone Download folder"
  "$HDC" file send "$final" "$PHONE_DIR/$(basename "$final")"
  echo "Open Files > Download on the phone and tap $(basename "$final") to install via DroiTong."
fi
