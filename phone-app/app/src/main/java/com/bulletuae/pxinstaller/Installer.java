package com.bulletuae.pxinstaller;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Hands an APK to the system installer through a PackageInstaller session. */
final class Installer {

    static final String ACTION_RESULT = "com.bulletuae.pxinstaller.INSTALL_RESULT";

    private Installer() {
    }

    static void install(Context ctx, File apk) throws IOException {
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setSize(apk.length());
        int id = pi.createSession(params);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (OutputStream out = s.openWrite("base.apk", 0, apk.length());
                 InputStream in = new FileInputStream(apk)) {
                Repacker.copy(in, out);
                s.fsync(out);
            }
            Intent cb = new Intent(ACTION_RESULT).setPackage(ctx.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent pending = PendingIntent.getBroadcast(ctx, id, cb, flags);
            s.commit(pending.getIntentSender());
        }
    }
}
