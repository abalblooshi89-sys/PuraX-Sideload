# Device change audit log

## Device

| | |
|---|---|
| Model | HUAWEI Pura X View (VOL-AL00) |
| Serial (hdc) | (redacted) |
| OS | HarmonyOS 7.0 / OpenHarmony 7.0.0.111, API 26 |
| Software version | VOL-AL00 7.0.0.111(SP10C00E100R5P6) |
| Security patch | 2026-10-01 |
| Connection | USB, Huawei hdc 3.2.0d (ADB is not supported on this OS) |
| Shell access | `shell` user (uid 2000), no root |

## Changes made on 2026-10-26 (times in UTC; phone clock is UTC+4)

All uninstalls used `-k`, which keeps the app's data on the device.

| Time (UTC) | Command | Result |
|---|---|---|
| 14:25:44 | `bm uninstall -n com.huawei.hmos.mmiautotest -k` | uninstall bundle successfully |
| 14:25:44 | `bm uninstall -n com.huawei.hms.analyticsservice -k` | **Failed**: code 304, "user does not install the hap" (it is installed for system user 0, not user 100) |
| 14:25:55 | `bm uninstall -n com.huawei.hms.analyticsservice -k -u 0` | uninstall bundle successfully |
| 14:25:45 | `bm uninstall -n com.huawei.hmos.suggestion -k` | uninstall bundle successfully |

Commands were run as `hdc shell "<command>"`.

## What was removed

| Bundle | What it is | Version before removal | Installed from |
|---|---|---|---|
| com.huawei.hmos.mmiautotest | Factory hardware test tool | 1.0.0 (1000000) | Built into the OS |
| com.huawei.hms.analyticsservice | Huawei analytics service | 8.04.00.330 (80400330) | AppGallery |
| com.huawei.hmos.suggestion | Suggestions feed | 13.11.10.232 (131110232) | AppGallery |

All three were marked `removable: true` by the OS before removal.

## Checks after removal

- Installed app count went from 187 to 184; none of the three is listed any more.
- These core services were still running: home screen and system UI (`com.ohos.sceneboard`), `foundation`, `render_service`, and the Android container broker (`anco_service_broker`).
- The error log had no crash or freeze entries. It showed only routine cleanup messages after the analytics removal, from AppGallery, Notifications, Cloud Auth and Account Manager.
- Not checked: whether AppGallery or a system update quietly reinstalls the analytics service later. If it appears in `bm dump -a` again, it was reinstalled.

## How to undo

The original app files couldn't be backed up first: the shell user is not allowed to read the preinstall folders.

- **analyticsservice and suggestion** were installed from AppGallery, so they can be reinstalled from AppGallery.
- **mmiautotest** is built into the OS and may not be in AppGallery. If it isn't, a factory reset is the only way to get it back.
- A factory reset restores all three.

Check what is installed:

```
hdc shell "bm dump -a"
```

## Android container: microG check and GSF install (2026-10-26, ~14:35–14:39 UTC)

### Findings before the change

The Android container (DroiTong / EasyAbroad) already had microG, branded "Framework Settings". Its Self-Check passed everything except one item:

| Self-Check item | Before | After |
|---|---|---|
| System spoofs signature | ✓ | ✓ |
| Play Services (GmsCore) installed / correct signature | ✓ / ✓ | ✓ / ✓ |
| Play Store (Phonesky) installed / correct signature | ✓ / ✓ | ✓ / ✓ |
| Services Framework (GSF) installed | **✗** | ✓ |
| All permissions (location, storage, accounts, notifications, phone, SMS, contacts, overlay) | ✓ | ✓ |

Other microG state at the time: Google device registration on, Cloud Messaging on with 14 registered apps, Google Device Attestation on. No Google account was added.

### What was installed

| | |
|---|---|
| Package | `com.google.android.gsf` (microG Services Framework Proxy) |
| Version | v0.1.0 (versionCode 8), target SDK 23 |
| Source | https://github.com/microg/GsfProxy/releases/download/v0.1.0/GsfProxy.apk |
| File | `GsfProxy.apk`, 21,872 bytes, SHA-256 `86891b174301f06a1c84187b545a0a2a57044c6b768f3e84e865908743349692` |
| Signer SHA-256 | `9bd06727e62796c0130eb6dab39b73157451582cbd138e86c468acc395d14165`; matches microG's F-Droid repo index |
| Modified? | No: original package name and signature |

Steps, run from the PuraX-Sideload project:

```
./sideload.sh downloads/com.google.android.gsf/GsfProxy.apk --no-push --expect-cert 9bd06727e62796c0130eb6dab39b73157451582cbd138e86c468acc395d14165
hdc file send out/GsfProxy.apk /storage/media/100/local/files/Docs/Download/GsfProxy.apk
python3 -I tools/phone_install.py GsfProxy.apk
```

Result: "Installation successful". DroiTong didn't block it.

### Other actions in this step (nothing changed)

- Opened EasyAbroad once to read its logs.
- Opened microG's settings and Self-Check screens with `aa start` and screen taps. No toggles changed.

### How to undo

Uninstall "microG Services Framework Proxy" inside the Android container, from its Settings > Apps. This doesn't touch GmsCore or Phonesky.

## Aurora Store as the Play Store: check and test install (2026-10-26, ~14:44–14:50 UTC)

The real Google Play Store was not installed: microG's Companion holds its package name (`com.android.vending`). Aurora Store (`com.aurora.store.px`, the repacked 4.8.4 build in `out/`) is used instead.

### Aurora state (checked, not changed)

| Item | State |
|---|---|
| Login | Signed in with a Google account. The live Google Play catalog loads. |
| Installation method | Session installer (Aurora's recommended default). Not changed. |
| Installer permission | Granted |
| External storage manager | Granted |
| Notifications | Granted |
| Background downloads (battery exemption) | **Not granted**: updates only download while Aurora is open. Left as is. |
| App links | Not granted (optional). Left as is. |

### Test install

| | |
|---|---|
| App | Wikipedia (`org.wikipedia`), Wikimedia Foundation, v50614-r-2026-10-20 (versionCode 50614), 60 MB |
| Source | Google Play, through Aurora Store |
| Flow | Aurora **Install**. The container installer showed "Security alert: This app isn't verified"; tapped **INSTALL**. |
| Result | "Installation successful" at 14:49:29 UTC. DroiTong did not block it. |
| Launch | Opened to its welcome screen. Container logs show the process started (Android uid 10149); no crash. |

### Mistaken taps (no effect)

Extra back presses briefly left Aurora for EasyAbroad, and one tap opened EasyAbroad's "Select Region" screen. Nothing was selected; the region was left unchanged. Typed text there went to no input field.

### How to undo

Uninstall Wikipedia in the container, from Settings > Apps or Aurora's My apps & games.

## Aurora MicroG installer test: failed, reverted (2026-10-26, ~14:52–15:07 UTC)

Goal: make installed apps look like they came from Google Play by having microG's Companion install them.

### Steps and results

| Time (UTC) | Action | Result |
|---|---|---|
| 14:52:02 | Aurora → Settings → Installation → Installation method → **MicroG installer** | Aurora asked to enable the installer in microG first |
| 14:53:32 | microG (Framework Settings) → Play Store services → ⋮ → App Installer Settings → **Allow App Installation: on** | Switched on. "Apps using App Installer: None" |
| 14:53:57 | Aurora dialog **Okay** | MicroG installer selected |
| 14:55:05 | Aurora: install **Material Files** (`me.zhanghai.android.files`, v1.7.5, 13 MB) | Downloaded |
| ~14:55:17 | Companion prompt: *Allow "EasyAbroad Assistant" to install apps downloaded from "Aurora Store"* → **ALLOW** (with "Keep this selection" **not** ticked) | Android opened "Install apps from external sources" for EasyAbroad Assistant (version 40.2.46-S-zh-0920) |
| ~14:58 | (no action by Claude) | **Allow app installs** for EasyAbroad Assistant read as **on**. Claude did not touch it; it was either already on or switched on by the owner. Its earlier state wasn't recorded. |
| ~14:59:58 | Back to the install | **"Can't install – This app is not supported by EasyAbroad."** Tapped CANCEL. |

Conclusion: when the Companion is the installer, EasyAbroad's own supported-app list applies (not DroiTong's), and it rejected an ordinary Play app. The MicroG installer isn't usable for general installs on this phone.

### Reverted

| Time (UTC) | Action |
|---|---|
| 15:00:43 | Aurora installation method set back to **Session installer** |
| 15:01:34 | microG App Installer Settings → **Allow App Installation: off** (its original state). Aurora Store had appeared in its "Apps using App Installer" list with its own switch off. |
| ~15:03 | Aurora showed Material Files stuck as "Installing"; cleared it in Aurora → Downloads → Clear |

Not reverted: Android's "Install apps from external sources" switch for EasyAbroad Assistant (see above; Claude never changed it).

### Check that the Session installer still works

| | |
|---|---|
| 15:05:05 | Aurora: install Material Files again with the Session installer |
| Prompt | Container installer showed "No threats found" → **INSTALL** |
| 15:07:08 | "Installation successful". Opens to its file list; process started (Android uid 10150). |

Material Files is left installed. To undo, uninstall it in the container's Settings > Apps or in Aurora.

## Chrome install attempts (2026-10-26, ~15:16–15:31 UTC), not installed yet

| Time (UTC) | Route | Result |
|---|---|---|
| 15:16:19 | Aurora, Session installer: Google Chrome v155.0.8059.87 (244 MB) | Downloaded. Container installer: "Performing security check…" → **"Can't install – This app is not supported by DroiTong."** Tapped CANCEL. Cleared the download in Aurora afterwards. |
| 15:18:00 | EasyAbroad catalog: Chrome (276.7 MB) → Install | No visible change. The watcher waited about 4 minutes, until the phone was on the home screen. |
| 15:25:51, 15:26:20 | EasyAbroad Chrome detail page → Install | No visible change. Log: `NotifyDownloadStatus` for `com.android.chrome`, source `com.easy.abroad`, status 504 → 507 → 510 within 20 ms; container package installer ran for about 50 ms; then status **511**, and the home screen removed Chrome's placeholder icon. A silent install failure; the container-side reason isn't visible over hdc. |
| ~15:28–15:30 | Aurora: Chrome downloaded again (to export the files) | Blocked by DroiTong again; tapped CANCEL. The download stays in Aurora → Downloads. |
| ~15:30 | (not Claude) | PX Installer was open showing `com.android.chrome.zip`: "Couldn't read this file. ZipException: zip END header not found". The owner seemed to be using the phone at the same time; Claude stopped tapping. The zip is in the container's storage, not the HarmonyOS Download folder. |

Nothing Chrome-related was installed, and no settings changed.

## Chrome repacked as `com.android.chrome.px` (2026-10-26, ~15:37 UTC onwards)

The owner asked for Chrome to be repacked on the Mac with this project's tools under a new package name, installed, opened, and for the default-browser screen to be brought up. The owner kept their hands off the phone during this step.

### Source APK

| | |
|---|---|
| Source | APKMirror, Google Chrome 155.0.8059.87, variant arm64-v8a, Android 10+, single APK (downloaded in the built-in browser; plain `curl` was blocked by Cloudflare) |
| File | `downloads/com.android.chrome/apkmirror/chrome-155.0.8059.87-arm64.apk`, 290,171,033 bytes, SHA-256 `7e8246d7ef341dc949ae65837a150289eb5f94eb9552424e01486ba17e56a102` |
| Package / version | `com.android.chrome`, versionCode 805908704, minSdk 29, target SDK 37 |
| Signer | Google's Chrome key: SHA-256 `f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83` (SHA-1 `38918a45…`), APK Signature Scheme v3 |
| Source stamp | Google Play, SHA-256 `3257d599a49d2c961a471ca9843f59d341a405884583fc087df4237b733bbd6d` (verified) |
| Native code | `arm64-v8a` only. No Trichrome / static-library dependency. |

The APKPure XAPK from the earlier attempt (`downloads/com.android.chrome/com.android.chrome@arm64-v8a.xapk`) wasn't used: despite its name it contains only 32-bit (`armeabi-v7a`) libraries, and this phone is 64-bit only.

### Repack

`./sideload.sh <apk> --repack --no-push --expect-cert f0fd6c5b…` failed at the rebuild: apktool's framework (Android 35) doesn't know two manifest attributes added after it, `android:zygotePreloadNativeLib` and `android:nativeService`. `repack.sh`'s steps were then run by hand in a scratch folder with three adjustments:

1. Chrome's manifest uses the namespace prefix `n0:` for the Android namespace, and `tools/patch_manifest.py` only matches the literal `android:` prefix. It renamed the package but skipped authorities and permissions. The prefix was renamed to `android:` (same namespace URI) before running `patch_manifest.py`.
2. `zygotePreloadNativeLib` and the two `nativeService` attributes were removed. The container's Android is older than these attributes and ignores them.
3. `patch_manifest.py` treated the intent action `.BOOKMARK_APPWIDGET_UPDATE` (bookmarks widget) as a relative class name and prefixed it. It was set back to its original value.

Everything else is what `repack.sh` does: `apktool d -f -s`, `patch_manifest.py <dir> com.android.chrome.px ""`, `apktool b`, then uber-apk-signer with `keys/sideload.jks` (alias `sideload`). Manifest changes compared with the original: package name; provider authorities and `com.android.chrome.*` permissions given `com.android.chrome.px.*` variants alongside the originals; the three attributes above removed. Code (dex) and native libraries are unchanged.

| | |
|---|---|
| Output | `out/chrome-155.0.8059.87-arm64-com.android.chrome.px.apk`, 292,823,404 bytes, SHA-256 `6ce424cef402aa7080dfcef34157d6a137dfe11813f212ad980c1f1aa82f65ba` |
| Package | `com.android.chrome.px`, same version |
| Signer | project key, SHA-256 `0a20ce117cf89f9eefe14360c4be5e66d192d7e6a7e5b13d8bb811f7cceab04a` (v3) |
| Alignment | Uncompressed native libraries are 4 KB page-aligned |

`blocked.txt` was not changed (the failed `sideload.sh` run stopped before that step).

### Install and test of the renamed builds

All APKs were pushed to `/storage/media/100/local/files/Docs/Download/` and installed with `python3 -I tools/phone_install.py <file>`. All launches used `aa start -b <package> -m entry -a com.google.android.apps.chrome.Main`.

| Time (UTC) | Build | Result |
|---|---|---|
| 15:44:00–15:44:29 | `out/chrome-155.0.8059.87-arm64-com.android.chrome.px.apk` (above) | Installed: "Installation successful", not blocked by DroiTong. |
| 15:44:43 | Launch | **Crashed.** The first-run screen (`FirstRunActivity`) resumed, then the process died about 0.13 s later. |
| 15:49:41–15:50:11 | `out/chrome-155.0.8059.87-arm64-com.android.chrome.px-origres.apk`: Chrome's original APK, byte for byte, with only `AndroidManifest.xml` swapped for the renamed one (no apktool resource rebuild). 290,490,657 bytes, SHA-256 `32324de3a8c129d599e4276b4a5f28fe8a8621000c22ddf5cd36b74ee4244c6b`, same project key. Built with a scratch script, not a project tool. | Installed as an update over the first build. Launch: **same crash.** So apktool's resource rebuild isn't the cause. |
| 15:51:45–15:52:04 | `out/chrome-155.0.8059.87-arm64-resigned.apk` (`./repack.sh <apk> --no-rename`: original package `com.android.chrome`, re-signed only). SHA-256 `084c3d206f668c1c9cc5a9d784288a9b7d0828e6c56418132899920d428906af` | **"Not supported by DroiTong"**; CANCEL tapped automatically. DroiTong blocks the package name whatever the signer. |

The container's Android log isn't readable over hdc: `logcat` is not available, and `/data/log/faultlog` is permission-denied. The exact exception is therefore unknown. The pattern (activity paused, then the process died within about 15 ms) is what Android does after an uncaught Java exception. Since unmodified, Google-signed Chrome Beta runs fine (below), the cause is the rename and/or the new signature, not the container.

**Still installed:** `com.android.chrome.px` ("Chrome" icon), which crashes on launch. It wasn't removed. To remove it, uninstall it from the container's app list.

### Chrome Beta from Google Play through Aurora (unmodified)

Following the README's tip ("look for an official build with a different package ID"):

| Time (UTC) | Action | Result |
|---|---|---|
| 15:54 | Aurora: searched "Chrome Beta" and opened **Chrome Beta**, Google LLC, `com.chrome.beta` v157.0.8103.14 (versionCode 810301404), 245 MB | |
| 15:55:06 | Aurora **Install** (Session installer) | Downloaded in about 10 s. Container installer: "Performing security check…" → **"No threats found"** → **INSTALL** tapped. |
| 15:55:30 | | **"Installation successful"**. Not blocked by DroiTong. Original Google signature; nothing modified. |
| 15:55:57 | Launch | Opens to its first-run screen "Make Chrome your own" (Add account to device / Stay signed out). The process stays up. |

The first-run screen was **not** completed: continuing means agreeing to Chrome's Terms of Service, which is the owner's decision.

### Default browser

| Place checked | Finding |
|---|---|
| Container Android Settings, **Default apps** (`com.android.permissioncontroller` `DefaultAppListActivity`) | Shows only Home app, App links and More. More lists Gallery, Video, Music, Email, document/zip types and Autofill. **No Browser app entry.** |
| `DefaultAppActivity` for the Browser role, started over hdc | Not available ("The specified ability does not exist") |
| Container "Apps" settings (`./settings.sh apps`) | Redirects to HarmonyOS Settings > Apps & services |
| HarmonyOS Settings > Apps & services > **Default browser** | Lists only HUAWEI Browser (`com.huawei.hmos.browser`, selected). Android apps aren't offered there. |

Nothing was changed on any of these screens. In HarmonyOS Settings, the app-list search was used once ("default", no results) and Settings search for "Default apps" and "browser"; no settings were opened beyond Default browser.

What is left for the owner: finish Chrome Beta's first-run screen, then make it the default from inside Chrome. Android shows its own "Set as default browser" prompt there. Links opened from HarmonyOS apps keep going to HUAWEI Browser regardless.

### Files left behind

- Phone, `/storage/media/100/local/files/Docs/Download/`: the three test APKs (`chrome-155.0.8059.87-arm64-com.android.chrome.px.apk`, `…-px-origres.apk`, `…-resigned.apk`), about 870 MB in total. Not deleted.
- Mac, `out/`: the same three APKs. `downloads/com.android.chrome/apkmirror/`: the original APKMirror APK.
- Aurora > Downloads still lists the blocked Chrome (stable) download from ~15:30.

## Hidden menus explored (2026-10-26, ~16:10–16:21 UTC), nothing changed

Read-only survey. Build type is `default` (retail, not an engineering build), and the shell has no root.

| Menu | How it was opened | What was done |
|---|---|---|
| ProjectMenu (`com.huawei.hmos.projectmenu`) | `aa start -b com.huawei.hmos.projectmenu -a MainAbility` | Opened Background Settings (USB Port Settings and AP Log Settings dialogs), Veneer Information Query > Version Info, Network Information Query (Fuse Info, Vendor Country Info) and Battery Charge to read them. Each dialog was closed with Back without choosing an option. Fuse Info "start" was not tapped. Software Upgrade and Factory Restore were not opened. |
| MMI factory tests (`com.huawei.hmos.mmitest`) | `aa start -b com.huawei.hmos.mmitest -a MmiSingleItemMenu` | Scrolled the test list and pressed Back. No test was started. |
| Container Android Developer options / Feature flags | `./settings.sh 'Settings$DevelopmentSettingsDashboardActivity'` (and `DevelopmentSettingsActivity`, `DeviceInfoSettingsActivity`, `FeatureFlagsDashboardActivity`) | All four: "The specified ability does not exist". Not available in this container. |

Afterwards the phone showed a blank white screen: Back had left the MMI app's full-screen window (`mmitest0`) on top, and it doesn't respond to Back or Home gestures. At ~16:24 UTC Claude ran `aa force-stop com.huawei.hmos.mmitest`, then `aa force-stop com.huawei.hmos.projectmenu` (also still open underneath), and pressed Home. The home screen came back, with no MMI or ProjectMenu windows left. Force-stop only closes the apps; no settings were changed.

## Not changed

- No security settings were changed, on HarmonyOS or in the container.
- microG GmsCore and Phonesky were left as they were. Nothing was reinstalled or replaced.
- The Android compatibility apps were kept: `com.droi.tong` (Zhuoyitong 1.0.10.93) and `com.easy.hmos.abroad`.
- [device-inventory.tsv](device-inventory.tsv) is the HarmonyOS app list from **before** these changes.
