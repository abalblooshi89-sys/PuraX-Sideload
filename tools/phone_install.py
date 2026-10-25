#!/usr/bin/env python3
"""Drive the phone UI to install an APK that is already in the Download folder.

DroiTong only reads files the Files app hands it (a direct `aa start` with a
file URI fails with "File loading failed"), so this opens Files, searches for
the file, taps it, then answers the container installer's screens by reading
the UI tree with `uitest dumpLayout`.

Usage: phone_install.py <file name in Download>
Exit codes: 0 installed, 2 blocked by DroiTong's list, 1 other failure.
"""
import json
import os
import re
import subprocess
import sys
import tempfile
import time

HDC = os.environ.get("HDC", os.path.expanduser("~/Library/OpenHarmony/sdk/toolchains/hdc"))
FILES = "com.huawei.hmos.filemanager"
LAYOUT = "/data/local/tmp/phone_install.json"


def hdc(*args):
    return subprocess.run([HDC, *args], capture_output=True, text=True).stdout


def shell(cmd):
    return hdc("shell", cmd)


def nodes():
    """Flattened list of (text, id, center) for the current screen; input
    fields are skipped so typed text never matches as a button or result."""
    shell("uitest dumpLayout -p " + LAYOUT)
    with tempfile.TemporaryDirectory() as tmp:
        local = os.path.join(tmp, "l.json")
        hdc("file", "recv", LAYOUT, local)
        try:
            tree = json.load(open(local))
        except (OSError, ValueError):
            return []
    out = []

    def walk(n):
        a = n.get("attributes", {})
        b = re.findall(r"\d+", a.get("bounds", ""))
        if len(b) == 4 and a.get("type") not in ("SearchField", "TextInput", "TextArea"):
            x1, y1, x2, y2 = map(int, b)
            out.append(((a.get("text") or "").strip(), a.get("id") or "", ((x1 + x2) // 2, (y1 + y2) // 2)))
        for c in n.get("children", []):
            walk(c)

    walk(tree)
    return out


def find(ns, text=None, id_=None):
    for t, i, c in ns:
        if (text and re.fullmatch(text, t, re.I)) or (id_ and i == id_):
            return c
    return None


def tap(xy):
    shell("uitest uiInput click %d %d" % xy)


def wait_tap(text=None, id_=None, timeout=10):
    end = time.time() + timeout
    while time.time() < end:
        xy = find(nodes(), text, id_)
        if xy:
            tap(xy)
            return True
        time.sleep(1)
    return False


def main():
    name = sys.argv[1]
    shell("aa force-stop " + FILES)
    shell("aa start -b %s -a MainAbility" % FILES)
    time.sleep(2)
    if not wait_tap(id_="search_button"):
        sys.exit("Files search button not found")
    time.sleep(1)
    shell("uitest uiInput text '%s'" % name.replace("'", ""))
    shell("uitest uiInput keyEvent 2054")  # Enter
    if not wait_tap(text=re.escape(name), timeout=15):
        sys.exit("%s not found in Files search" % name)

    print("==> Installer opened, waiting for its verdict")
    end = time.time() + 180
    while time.time() < end:
        ns = nodes()
        texts = " | ".join(t for t, _, _ in ns if t)
        if re.search(r"not supported by DroiTong", texts, re.I):
            tap(find(ns, "CANCEL") or (0, 0))
            print("==> Blocked by DroiTong's list")
            sys.exit(2)
        if re.search(r"Installation successful", texts, re.I):
            tap(find(ns, "DONE") or (0, 0))
            print("==> Installation successful")
            sys.exit(0)
        if re.search(r"File loading failed|Installation failed|App not installed", texts, re.I):
            print("==> Install failed: " + texts[:200])
            sys.exit(1)
        xy = find(ns, "INSTALL|UPDATE|CONTINUE")
        if xy:
            tap(xy)
            print("==> Confirmed install")
            time.sleep(3)
        time.sleep(2)
    print("==> Timed out waiting for the installer")
    sys.exit(1)


if __name__ == "__main__":
    main()
