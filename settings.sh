#!/usr/bin/env bash
# Open Android settings pages inside DroiTong's container from the Mac.
# Apps can't open their own per-app permission pages there (they close at once),
# but these list pages work; tap the app in the list and flip its toggle.
#
# Usage: ./settings.sh <page>
#   unknown   Install apps from external sources (installer permission)
#   files     All files access
#   apps      Apps & notifications
#   <Class>   any com.android.settings activity, e.g. 'Settings$UsageAccessSettingsActivity'
set -euo pipefail

HDC="${HDC:-$HOME/Library/OpenHarmony/sdk/toolchains/hdc}"
case "${1:-}" in
  unknown) act='Settings$ManageExternalSourcesActivity' ;;
  files)   act='Settings$ManageExternalStorageActivity' ;;
  apps)    act='Settings$AppAndNotificationDashboardActivity' ;;
  "")      sed -n '2,11p' "$0"; exit 1 ;;
  *)       act="$1" ;;
esac
"$HDC" shell "aa start -b com.android.settings -m entry -a 'com.android.settings.$act'"
