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
- --drop-attr NAME removes android:NAME everywhere in the manifest, for
  attributes newer than the framework apktool builds against.

The Android namespace is matched by URI, whatever prefix the manifest binds it
to: usually "android", but apktool writes "n0" for APKs whose binary manifest
has no prefix names (Chrome, for one).

Usage: patch_manifest.py <decoded_dir> <new_package> [label_suffix] [--drop-attr NAME ...]
Prints the original package name.
"""
import os
import re
import sys

ANDROID_NS = "http://schemas.android.com/apk/res/android"


def android_prefix(man):
    """Regex group matching the prefix(es) bound to the Android namespace."""
    prefixes = re.findall(r'xmlns:([\w.-]+)="%s"' % re.escape(ANDROID_NS), man)
    return "(%s)" % "|".join(map(re.escape, prefixes or ["android"]))


def main():
    args, drop = [], []
    argv = iter(sys.argv[1:])
    for arg in argv:
        if arg == "--drop-attr":
            drop.append(next(argv))
        else:
            args.append(arg)
    dec, new_pkg = args[0], args[1]
    label_suffix = args[2] if len(args) > 2 else ""
    man_path = os.path.join(dec, "AndroidManifest.xml")

    man = open(man_path, encoding="utf-8").read()
    m = re.search(r'<manifest\b[^>]*?\bpackage="([^"]+)"', man, re.S)
    if not m:
        sys.exit("no package attribute in manifest")
    old_pkg = m.group(1)
    if old_pkg == new_pkg:
        sys.exit("new package equals old package")
    a = android_prefix(man)

    # Authorities: add the new-package variant next to each old one.
    def fix_auth(match):
        parts = match.group(2).split(";")
        extra = [new_pkg + p[len(old_pkg):] for p in parts
                 if p.startswith(old_pkg + ".") or p == old_pkg]
        merged = parts + [e for e in extra if e not in parts]
        return '%s:authorities="%s"' % (match.group(1), ";".join(merged))

    man = re.sub(r'%s:authorities="([^"]*)"' % a, fix_auth, man)

    # Package-prefixed permissions: duplicate the element with the new prefix.
    def dup_perm(match):
        elem, name = match.group(0), match.group(3)
        if not name.startswith(old_pkg + "."):
            return elem
        new_name = new_pkg + name[len(old_pkg):]
        if re.search(r'%s:name="%s"' % (a, re.escape(new_name)), man):
            return elem
        return elem + "\n    " + elem.replace(name, new_name)

    man = re.sub(r'<(permission|uses-permission)\b[^>]*?%s:name="([^"]+)"[^>]*/>' % a,
                 dup_perm, man)

    if label_suffix:
        man = apply_label_suffix(dec, man, label_suffix, a)

    for name in drop:
        man, n = re.subn(r'\s+%s:%s="[^"]*"' % (a, re.escape(name)), "", man)
        if n:
            print("==> Removed android:%s from the manifest (%d), unknown to the framework"
                  % (name, n), file=sys.stderr)

    # Relative class names (".Foo") resolve against the manifest package, so pin
    # them to the old package before renaming it; the code itself is unchanged.
    # Only class attributes of component elements: names elsewhere (intent-filter
    # actions, meta-data) can start with "." too, and are plain strings.
    cls = re.compile(r'(%s:(?:name|targetActivity|backupAgent|manageSpaceActivity'
                     r'|parentActivityName|appComponentFactory)=")\.' % a)
    man = re.sub(r'<(?:application|activity|activity-alias|service|receiver|provider'
                 r'|instrumentation)\b[^>]*>',
                 lambda tag: cls.sub(lambda x: x.group(1) + old_pkg + ".", tag.group(0)), man)
    man = man.replace('package="%s"' % old_pkg, 'package="%s"' % new_pkg, 1)

    open(man_path, "w", encoding="utf-8").write(man)

    print(old_pkg)


def apply_label_suffix(dec, man, suffix, a):
    m = re.search(r'<application\b[^>]*?%s:label="([^"]+)"' % a, man, re.S)
    if not m:
        return man
    prefix, label = m.group(1), m.group(2)
    if not label.startswith("@string/"):
        return man.replace('%s:label="%s"' % (prefix, label),
                           '%s:label="%s%s"' % (prefix, label, suffix), 1)
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
