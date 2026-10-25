#!/usr/bin/env python3
"""Patch an apktool-decoded APK so it installs under a new package name.

- Rewrites the manifest package attribute (apktool 3 builds the manifest as
  decoded, with fully qualified class names), changing the installed package
  ID without touching code.
- Provider authorities that start with the old package get the new-package
  variant added (authorities="old.x;new.x"), since apps often build them at
  runtime from context.packageName.
- <permission>/<uses-permission> entries prefixed with the old package are
  duplicated with the new prefix for the same reason.
- Optionally appends a suffix to the app label.

Usage: patch_manifest.py <decoded_dir> <new_package> [label_suffix]
Prints the original package name.
"""
import os
import re
import sys

ANDROID_NS = "http://schemas.android.com/apk/res/android"


def main():
    dec, new_pkg = sys.argv[1], sys.argv[2]
    label_suffix = sys.argv[3] if len(sys.argv) > 3 else ""
    man_path = os.path.join(dec, "AndroidManifest.xml")

    man = open(man_path, encoding="utf-8").read()
    m = re.search(r'<manifest\b[^>]*?\bpackage="([^"]+)"', man, re.S)
    if not m:
        sys.exit("no package attribute in manifest")
    old_pkg = m.group(1)
    if old_pkg == new_pkg:
        sys.exit("new package equals old package")

    # Authorities: add the new-package variant next to each old one.
    def fix_auth(match):
        parts = match.group(1).split(";")
        extra = [new_pkg + p[len(old_pkg):] for p in parts
                 if p.startswith(old_pkg + ".") or p == old_pkg]
        merged = parts + [e for e in extra if e not in parts]
        return 'android:authorities="%s"' % ";".join(merged)

    man = re.sub(r'android:authorities="([^"]*)"', fix_auth, man)

    # Package-prefixed permissions: duplicate the element with the new prefix.
    def dup_perm(match):
        elem, name = match.group(0), match.group(2)
        if not name.startswith(old_pkg + "."):
            return elem
        new_name = new_pkg + name[len(old_pkg):]
        if 'android:name="%s"' % new_name in man:
            return elem
        return elem + "\n    " + elem.replace(name, new_name)

    man = re.sub(r'<(permission|uses-permission)\b[^>]*?android:name="([^"]+)"[^>]*/>',
                 dup_perm, man)

    if label_suffix:
        man = apply_label_suffix(dec, man, label_suffix)

    # Relative class names (".Foo") resolve against the manifest package, so pin
    # them to the old package before renaming it; the code itself is unchanged.
    man = re.sub(r'(android:(?:name|targetActivity|backupAgent|manageSpaceActivity)=")\.',
                 lambda x: x.group(1) + old_pkg + ".", man)
    man = man.replace('package="%s"' % old_pkg, 'package="%s"' % new_pkg, 1)

    open(man_path, "w", encoding="utf-8").write(man)

    print(old_pkg)


def apply_label_suffix(dec, man, suffix):
    m = re.search(r'<application\b[^>]*?android:label="([^"]+)"', man, re.S)
    if not m:
        return man
    label = m.group(1)
    if not label.startswith("@string/"):
        return man.replace('android:label="%s"' % label,
                           'android:label="%s%s"' % (label, suffix), 1)
    key = label[len("@string/"):]
    res = os.path.join(dec, "res")
    pat = re.compile(r'(<string name="%s"[^>]*>)(.*?)(</string>)' % re.escape(key), re.S)
    for d in os.listdir(res):
        f = os.path.join(res, d, "strings.xml")
        if d.startswith("values") and os.path.isfile(f):
            s = open(f, encoding="utf-8").read()
            s2 = pat.sub(lambda x: x.group(1) + x.group(2) + suffix + x.group(3), s, 1)
            if s2 != s:
                open(f, "w", encoding="utf-8").write(s2)
    return man


if __name__ == "__main__":
    main()
