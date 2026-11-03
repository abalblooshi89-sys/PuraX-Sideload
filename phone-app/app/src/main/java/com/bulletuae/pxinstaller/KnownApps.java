package com.bulletuae.pxinstaller;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Apps that verify their own signing certificate (or demand Google Play
 * Integrity) when you register or sign in. Repacking re-signs the APK, so these
 * install and open but die the moment you start registration/login — no package
 * rename or re-sign can satisfy the check. Flag them up front so the repack
 * isn't mistaken for the problem.
 *
 * Confirmed on this device: renamed WhatsApp installs, opens to its own Welcome
 * screen, then closes the instant "Agree and continue" starts registration,
 * while the official build from EasyAbroad (original package and signature)
 * registers normally. So point these apps at EasyAbroad instead.
 */
final class KnownApps {

    private static final Set<String> SIGNATURE_LOCKED = new HashSet<>(Arrays.asList(
            "com.whatsapp",             // WhatsApp
            "com.whatsapp.w4b",         // WhatsApp Business
            "com.instagram.android",    // Instagram
            "com.facebook.katana",      // Facebook
            "com.facebook.orca",        // Messenger
            "com.twitter.android",      // X (Twitter)
            "com.snapchat.android",     // Snapchat
            "com.zhiliaoapp.musically", // TikTok
            "com.ss.android.ugc.trill", // TikTok (global)
            "com.ss.android.ugc.aweme"  // Douyin / TikTok
    ));

    private KnownApps() {
    }

    /** True for an app known to reject a re-signed build at login/registration. */
    static boolean isSignatureLocked(String pkg) {
        return pkg != null && SIGNATURE_LOCKED.contains(stripSuffix(pkg));
    }

    /** Pre-install warning for a flagged app, or null if it isn't flagged. */
    static String note(String pkg, CharSequence label) {
        if (!isSignatureLocked(pkg)) return null;
        String name = (label == null || label.length() == 0) ? "This app" : label.toString();
        String base = name + " checks its own signature when you register or sign in. "
                + "Repacking re-signs it, so it installs and opens but closes the moment you "
                + "try to sign in. Renaming or re-signing can't get past that check. "
                + "Install the official version from EasyAbroad instead: it keeps the "
                + "original signature, so sign-in works.";
        return "⚠ " + base;
    }

    /** One-line reminder after such an app installs, since it will look fine until login. */
    static String installedHint(String pkg) {
        return isSignatureLocked(pkg)
                ? "Note: it opens, but will close at sign-in — it verifies its own signature. "
                        + "Get it from EasyAbroad instead."
                : null;
    }

    private static String stripSuffix(String pkg) {
        int dot = pkg.lastIndexOf('.');
        // Undo a "<id>.px" style rename so the original id is matched.
        if (dot > 0 && pkg.substring(dot + 1).matches("px[a-z0-9]*")) return pkg.substring(0, dot);
        return pkg;
    }
}
