package com.bulletuae.pxinstaller;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.Key;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Enumeration;

/**
 * The sideload signing key, imported once from the Mac's keystore (PKCS#12)
 * so apps repacked on either side can update each other.
 */
final class Keys {

    private static final String FILE = "sideload.p12";
    private static final String PREFS = "keys";

    static final class SigningKey {
        final PrivateKey privateKey;
        final X509Certificate certificate;

        SigningKey(PrivateKey privateKey, X509Certificate certificate) {
            this.privateKey = privateKey;
            this.certificate = certificate;
        }

        String fingerprint() {
            try {
                byte[] d = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
                StringBuilder sb = new StringBuilder();
                for (byte b : d) sb.append(String.format("%02x", b));
                return sb.toString();
            } catch (Exception e) {
                return "?";
            }
        }

        String shortFingerprint() {
            String f = fingerprint();
            return "key " + f.substring(0, Math.min(8, f.length())) + "…";
        }
    }

    private Keys() {
    }

    static boolean has(Context ctx) {
        return new File(ctx.getFilesDir(), FILE).isFile() && prefs(ctx).contains("pass");
    }

    /** Validates the PKCS#12 file and stores it in the app's private storage. */
    static SigningKey importFrom(Context ctx, Uri uri, String password) throws Exception {
        byte[] bytes;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalArgumentException("Cannot open the key file");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            Repacker.copy(in, bos);
            bytes = bos.toByteArray();
        }
        SigningKey key = read(new ByteArrayInputStream(bytes), password);
        File f = new File(ctx.getFilesDir(), FILE);
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(bytes);
        }
        prefs(ctx).edit().putString("pass", password).apply();
        return key;
    }

    static SigningKey load(Context ctx) throws Exception {
        String pass = prefs(ctx).getString("pass", null);
        if (pass == null) throw new IllegalStateException("No signing key imported yet");
        try (InputStream in = new FileInputStream(new File(ctx.getFilesDir(), FILE))) {
            return read(in, pass);
        }
    }

    private static SigningKey read(InputStream in, String password) throws Exception {
        char[] pass = password.toCharArray();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(in, pass);
        Enumeration<String> aliases = ks.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            if (!ks.isKeyEntry(alias)) continue;
            Key k = ks.getKey(alias, pass);
            Certificate c = ks.getCertificate(alias);
            if (k instanceof PrivateKey && c instanceof X509Certificate) {
                return new SigningKey((PrivateKey) k, (X509Certificate) c);
            }
        }
        throw new IllegalArgumentException("No private key found in this file");
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
