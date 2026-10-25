# PuraX-Sideload

Gets Android apps onto a Huawei Pura X (HarmonyOS 7) through DroiTong, including
apps DroiTong's blacklist rejects.

## How it works

DroiTong runs Android in Huawei's "anco" container and installs APKs through the
container's own package installer. Its blacklist is a check on package name and
signing certificate, so an APK with a new package name and a new signature gets
through. These scripts prepare the APK on the Mac and put it in the phone's
Download folder; you tap it there to install.

## Granting permissions inside the container

Apps can't open their own per-app permission pages in the container (they close
instantly, so Aurora's "Grant" buttons report "denied"). The list pages work when
opened from the Mac; tap the app there and flip its toggle:

```bash
./settings.sh unknown   # Install apps from external sources (installer permission)
./settings.sh files     # All files access
```

## One-command install (no taps on the phone)

```bash
./install.sh ~/Downloads/app.apk        # or an F-Droid app ID, .apkm/.xapk/.apks
```

Prepares the APK, pushes it, opens it through the Files app (DroiTong only reads
files Files hands it), confirms the installer's "isn't verified" prompt, and if
the result is "not supported by DroiTong" it cancels, repacks and installs again.
The phone must be unlocked. Every install still goes through the container's
installer: Huawei removed Developer options/ADB from the container, and there is
no other installer, so there is no truly DroiTong-free path without root.

## Usage (step by step)

```bash
./sideload.sh org.some.app                   # download from F-Droid, push
./sideload.sh ~/Downloads/app.apkm           # local file (APKMirror .apk/.apkm, .xapk, .apks)
./sideload.sh ~/Downloads/app.apk --repack   # DroiTong rejected it: rename + re-sign
./sideload.sh some.app -d apk-pure --apkpure-risk --expect-cert <sha256>
```

- Split bundles (`.apkm`, `.xapk`, `.apks`) are merged into one APK and re-signed.
- `--repack` renames the package (`<id>.px`, change with `-s`), re-signs it and
  adds the app to `blocked.txt`, so later runs repack it automatically.
- The original signer's SHA-256 is always printed. `--expect-cert` aborts on a
  mismatch. Use it with any mirror, and always with APKPure, which has shipped
  tampered APKs.
- `repack.sh` is the lower-level rename/re-sign step; `./repack.sh -h` lists options.

## Tips

- The blacklist is applied by the container's own installer screen ("This app is
  not supported by DroiTong"), so installing from Aurora hits it too. Aurora is
  fine for apps that aren't blocked.
- Before repacking, look for an official build with a different package ID. The
  blacklist matches exact IDs: Telegram from telegram.org
  (`org.telegram.messenger.web`) installs unmodified, while the Play build
  (`org.telegram.messenger`) is blocked. Unmodified installs keep the original
  signature, updates and push notifications.

## Caveats

- A repackaged app is a different app to Android: it can't update over the
  original, and updates have to go through this tool again.
- Apps that check their own signature (some banking apps and games) may refuse to
  run after re-signing.
- **Back up `keys/`.** Updates of repackaged apps only install if they're signed
  with the same key.

## Tools

`hdc` (OpenHarmony SDK, `~/Library/OpenHarmony/sdk/toolchains`), Homebrew
`openjdk`, `apktool` and `apkeep`, plus `tools/uber-apk-signer-1.3.0.jar` and
`tools/APKEditor-1.4.9.jar`.
