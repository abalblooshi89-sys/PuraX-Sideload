package com.bulletuae.pxinstaller;

import com.android.apksig.ApkSigner;
import com.reandroid.apk.ApkBundle;
import com.reandroid.apk.ApkModule;
import com.reandroid.arsc.chunk.PackageBlock;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import com.reandroid.arsc.chunk.xml.ResXmlAttribute;
import com.reandroid.arsc.chunk.xml.ResXmlElement;
import com.reandroid.arsc.value.ValueType;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * On-device port of repack.sh: merge split bundles, rename the package so
 * DroiTong's block list no longer matches it, and re-sign with the sideload key.
 */
final class Repacker {

    interface Log {
        void log(String msg);
    }

    // android:* attribute resource IDs (android.R.attr).
    private static final int ATTR_NAME = 0x01010003;
    private static final int ATTR_MANAGE_SPACE_ACTIVITY = 0x01010004;
    private static final int ATTR_PROTECTION_LEVEL = 0x01010009;
    private static final int ATTR_AUTHORITIES = 0x01010018;
    private static final int ATTR_TARGET_ACTIVITY = 0x01010202;
    private static final int ATTR_BACKUP_AGENT = 0x0101027f;
    private static final int ATTR_PARENT_ACTIVITY = 0x010103a7;
    private static final int ATTR_EXTRACT_NATIVE_LIBS = 0x010104ea;
    private static final int ATTR_IS_SPLIT_REQUIRED = 0x01010591;
    private static final int ATTR_REQUIRED_SPLIT_TYPES = 0x0101064e;
    private static final int ATTR_SPLIT_TYPES = 0x0101064f;

    private static final Set<String> COMPONENT_TAGS = new HashSet<>(Arrays.asList(
            "application", "activity", "activity-alias", "service", "receiver", "provider",
            "instrumentation"));
    private static final int[] CLASS_ATTRS = {
            ATTR_NAME, ATTR_TARGET_ACTIVITY, ATTR_BACKUP_AGENT, ATTR_MANAGE_SPACE_ACTIVITY,
            ATTR_PARENT_ACTIVITY};
    private static final Set<String> SPLIT_META = new HashSet<>(Arrays.asList(
            "com.android.vending.splits.required", "com.android.vending.splits",
            "com.android.stamp.source", "com.android.stamp.type",
            "com.android.dynamic.apk.fused.modules"));
    private static final Pattern OLD_SIGNATURE = Pattern.compile(
            "^META-INF/([^/]+\\.(SF|RSA|DSA|EC)|MANIFEST\\.MF)$|^stamp-cert-sha256$");

    private Repacker() {
    }

    /** True when the file is a single APK rather than a bundle of APKs. */
    static boolean isPlainApk(File file) throws IOException {
        try (ZipFile zip = new ZipFile(file)) {
            return zip.getEntry("AndroidManifest.xml") != null;
        }
    }

    /** Extracts every *.apk inside a .apks/.xapk/.apkm bundle into dir. */
    static List<File> extractApks(File bundle, File dir) throws IOException {
        deleteRecursive(dir);
        if (!dir.mkdirs()) throw new IOException("Cannot create " + dir);
        List<File> out = new ArrayList<>();
        try (ZipFile zip = new ZipFile(bundle)) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                if (e.isDirectory() || !name.toLowerCase().endsWith(".apk")) continue;
                String base = name.substring(name.lastIndexOf('/') + 1);
                File f = new File(dir, out.size() + "_" + base);
                try (InputStream in = zip.getInputStream(e); OutputStream o = new FileOutputStream(f)) {
                    copy(in, o);
                }
                out.add(f);
            }
        }
        if (out.isEmpty()) {
            throw new IOException("No APKs inside this file (encrypted .apkm files are not supported)");
        }
        return out;
    }

    /** Returns the base (non-split) APK of an extracted bundle. */
    static File findBase(List<File> apks) throws IOException {
        for (File f : apks) {
            ApkModule m = ApkModule.loadApkFile(f);
            try {
                if (!m.getAndroidManifest().isSplit()) return f;
            } finally {
                m.close();
            }
        }
        return apks.get(0);
    }

    /** Loads a single APK, or merges extracted split APKs into one module. */
    static ApkModule load(File apk, File splitDir, int splitCount, Log log) throws IOException {
        // The bundled Android framework tables are stripped from the app (DroiTong
        // rejects APKs carrying nested .apk files), and renaming doesn't need them.
        if (splitDir == null || splitCount <= 1) {
            ApkModule module = ApkModule.loadApkFile(apk);
            module.setLoadDefaultFramework(false);
            return module;
        }
        log.log("Merging " + splitCount + " split APKs into one…");
        ApkBundle bundle = new ApkBundle();
        bundle.loadApkDirectory(splitDir);
        ApkModule merged = bundle.mergeModules();
        merged.setLoadDefaultFramework(false);
        cleanSplitInfo(merged.getAndroidManifest());
        return merged;
    }

    /**
     * Renames the package in the manifest (and resource table), keeping class
     * names pointed at the original code. Returns the original package name.
     */
    static String rename(ApkModule module, String newPkg, Log log) {
        AndroidManifestBlock man = module.getAndroidManifest();
        String oldPkg = man.getPackageName();
        if (oldPkg == null) throw new IllegalStateException("Manifest has no package name");
        if (oldPkg.equals(newPkg)) throw new IllegalArgumentException("New package is the same as the old one");

        ResXmlElement root = man.getManifestElement();
        List<ResXmlElement> all = new ArrayList<>();
        Iterator<ResXmlElement> it = root.recursiveElements();
        while (it.hasNext()) all.add(it.next());

        int pinned = 0, authorities = 0;
        for (ResXmlElement e : all) {
            String tag = e.getName();
            // Relative class names (".Foo" or "Foo") resolve against the manifest
            // package, so pin them to the old package before renaming it.
            if (COMPONENT_TAGS.contains(tag) || "activity".equals(tag)) {
                for (int id : CLASS_ATTRS) {
                    ResXmlAttribute a = e.searchAttributeByResourceId(id);
                    String v = stringValue(a);
                    if (v == null || v.isEmpty()) continue;
                    if (v.startsWith(".")) {
                        a.setValueAsString(oldPkg + v);
                        pinned++;
                    } else if (v.indexOf('.') < 0) {
                        a.setValueAsString(oldPkg + "." + v);
                        pinned++;
                    }
                }
            }
            // Apps often build provider authorities from getPackageName(), so
            // add the new-package variant next to each old one.
            if ("provider".equals(tag)) {
                ResXmlAttribute a = e.searchAttributeByResourceId(ATTR_AUTHORITIES);
                String v = stringValue(a);
                if (v != null) {
                    List<String> parts = new ArrayList<>(Arrays.asList(v.split(";")));
                    List<String> extra = new ArrayList<>();
                    for (String p : parts) {
                        if (p.equals(oldPkg) || p.startsWith(oldPkg + ".")) {
                            String n = newPkg + p.substring(oldPkg.length());
                            if (!parts.contains(n) && !extra.contains(n)) extra.add(n);
                        }
                    }
                    if (!extra.isEmpty()) {
                        parts.addAll(extra);
                        a.setValueAsString(String.join(";", parts));
                        authorities++;
                    }
                }
            }
        }

        // Package-prefixed permissions: declare/request the new-prefix twin too
        // (e.g. androidx's <pkg>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION).
        Set<String> existing = new HashSet<>();
        for (ResXmlElement e : all) {
            String v = stringValue(e.searchAttributeByResourceId(ATTR_NAME));
            if (v != null && e.getParentElement() == root) existing.add(e.getName() + "|" + v);
        }
        int perms = 0;
        for (ResXmlElement e : all) {
            String tag = e.getName();
            if (e.getParentElement() != root) continue;
            if (!"permission".equals(tag) && !"uses-permission".equals(tag)) continue;
            String v = stringValue(e.searchAttributeByResourceId(ATTR_NAME));
            if (v == null || !v.startsWith(oldPkg + ".")) continue;
            String n = newPkg + v.substring(oldPkg.length());
            if (!existing.add(tag + "|" + n)) continue;
            ResXmlElement twin = root.createChildElement(tag);
            twin.getOrCreateAndroidAttribute("name", ATTR_NAME).setValueAsString(n);
            ResXmlAttribute level = e.searchAttributeByResourceId(ATTR_PROTECTION_LEVEL);
            if (level != null) {
                ResXmlAttribute l = twin.getOrCreateAndroidAttribute("protectionLevel", ATTR_PROTECTION_LEVEL);
                l.setValueType(level.getValueType());
                l.setData(level.getData());
            }
            perms++;
        }

        man.setPackageName(newPkg);
        // Same as aapt2 --rename-resources-package: keeps getIdentifier(..., getPackageName()) working.
        if (module.hasTableBlock()) {
            for (PackageBlock pb : module.getTableBlock().listPackages()) {
                if (oldPkg.equals(pb.getName())) pb.setName(newPkg);
            }
        }
        man.refresh();
        log.log("Renamed " + oldPkg + " → " + newPkg + " (" + pinned + " class names pinned, "
                + authorities + " providers, " + perms + " permissions)");
        return oldPkg;
    }

    /** Strips old signatures and writes an unsigned APK. */
    static void writeUnsigned(ApkModule module, File out, Log log) throws IOException {
        module.getZipEntryMap().removeIf(OLD_SIGNATURE);
        AndroidManifestBlock man = module.getAndroidManifest();
        boolean hasLibs = false;
        for (com.reandroid.archive.InputSource src : module.getZipEntryMap().listInputSources()) {
            if (src.getAlias().startsWith("lib/")) {
                hasLibs = true;
                break;
            }
        }
        if (hasLibs) {
            // Let the installer extract native libs instead of requiring page-aligned stored .so files.
            ResXmlElement app = man.getOrCreateApplicationElement();
            ResXmlAttribute a = app.getOrCreateAndroidAttribute("extractNativeLibs", ATTR_EXTRACT_NATIVE_LIBS);
            a.setValueType(ValueType.BOOLEAN);
            a.setData(0xffffffff);
            man.refresh();
        }
        module.getZipEntryMap().autoSortApkFiles();
        log.log("Writing APK…");
        module.writeApk(out);
    }

    /** Signs with apksig (v1+v2+v3), aligning stored entries as zipalign does. */
    static void sign(File in, File out, Keys.SigningKey key, Log log) throws Exception {
        log.log("Signing with " + key.shortFingerprint() + "…");
        ApkSigner.SignerConfig cfg = new ApkSigner.SignerConfig.Builder(
                "PXSIGN", key.privateKey, Collections.singletonList(key.certificate)).build();
        new ApkSigner.Builder(Collections.singletonList(cfg))
                .setInputApk(in)
                .setOutputApk(out)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setV4SigningEnabled(false)
                .build()
                .sign();
    }

    private static void cleanSplitInfo(AndroidManifestBlock man) {
        ResXmlElement root = man.getManifestElement();
        root.removeAttributesWithId(ATTR_IS_SPLIT_REQUIRED);
        root.removeAttributesWithId(ATTR_REQUIRED_SPLIT_TYPES);
        root.removeAttributesWithId(ATTR_SPLIT_TYPES);
        ResXmlElement app = man.getApplicationElement();
        if (app != null) {
            app.removeAttributesWithId(ATTR_IS_SPLIT_REQUIRED);
            List<ResXmlElement> remove = new ArrayList<>();
            Iterator<ResXmlElement> it = app.recursiveElements();
            while (it.hasNext()) {
                ResXmlElement e = it.next();
                if ("meta-data".equals(e.getName())
                        && SPLIT_META.contains(stringValue(e.searchAttributeByResourceId(ATTR_NAME)))) {
                    remove.add(e);
                }
            }
            for (ResXmlElement e : remove) e.removeSelf();
        }
        man.refresh();
    }

    private static String stringValue(ResXmlAttribute a) {
        if (a == null || a.getValueType() != ValueType.STRING) return null;
        return a.getValueAsString();
    }

    static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
