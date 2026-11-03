package com.bulletuae.pxinstaller;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;

import com.reandroid.apk.ApkModule;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_PICK_APK = 1;
    private static final int REQ_PICK_KEY = 2;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView appName, appDetails, log, keyStatus, warn;
    private ImageView icon;
    private Switch rename;
    private EditText newPkg;
    private Button go, open;

    // Current selection.
    private File baseApk;      // the APK to install, or the base of a bundle
    private File splitDir;     // extracted split APKs, or null for a single APK
    private int splitCount;
    private String oldPkg;
    private String installedPkg;
    private boolean busy;

    private final BroadcastReceiver resultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            onInstallResult(intent);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        appName = findViewById(R.id.appName);
        appDetails = findViewById(R.id.appDetails);
        log = findViewById(R.id.log);
        warn = findViewById(R.id.warn);
        keyStatus = findViewById(R.id.keyStatus);
        icon = findViewById(R.id.icon);
        rename = findViewById(R.id.rename);
        newPkg = findViewById(R.id.newPkg);
        go = findViewById(R.id.go);
        open = findViewById(R.id.open);

        findViewById(R.id.choose).setOnClickListener(v -> pick(REQ_PICK_APK));
        findViewById(R.id.importKey).setOnClickListener(v -> pick(REQ_PICK_KEY));
        rename.setOnCheckedChangeListener((b, on) -> newPkg.setEnabled(on));
        go.setOnClickListener(v -> repackAndInstall());
        open.setOnClickListener(v -> openInstalled());

        IntentFilter f = new IntentFilter(Installer.ACTION_RESULT);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(resultReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(resultReceiver, f);
        }

        updateKeyStatus();
        // Leftovers from an earlier run (e.g. the app was closed mid-install);
        // a newly shared file replaces the work folder anyway.
        if (savedInstanceState == null) worker.execute(() -> Repacker.deleteRecursive(workDir()));
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(resultReceiver);
        worker.shutdownNow();
        super.onDestroy();
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        Uri uri = null;
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            uri = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class)
                    : intent.getParcelableExtra(Intent.EXTRA_STREAM);
        } else if (Intent.ACTION_VIEW.equals(action) || Intent.ACTION_INSTALL_PACKAGE.equals(action)) {
            uri = intent.getData();
        }
        if (uri != null) load(uri);
    }

    private void pick(int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, req);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == REQ_PICK_APK) load(data.getData());
        else if (requestCode == REQ_PICK_KEY) importKey(data.getData());
    }

    /** Tries a password-less .p12 first (what the Mac exports), then asks for one. */
    private void importKey(Uri uri) {
        worker.execute(() -> {
            try {
                Keys.SigningKey k = Keys.importFrom(this, uri, "");
                ui.post(() -> onKeyImported(k));
            } catch (Throwable t) {
                ui.post(() -> askKeyPassword(uri));
            }
        });
    }

    private void onKeyImported(Keys.SigningKey k) {
        appendLog("✓ Signing key imported (" + k.shortFingerprint() + "). Delete the .p12 file now.");
        updateKeyStatus();
    }

    // ---- Loading -------------------------------------------------------------

    private void load(Uri uri) {
        if (busy) return;
        setBusy(true);
        clearLog();
        open.setVisibility(View.GONE);
        warn.setVisibility(View.GONE);
        String name = displayName(uri);
        appName.setText(name != null ? name : "Loading…");
        appDetails.setText("Reading file…");
        worker.execute(() -> {
            try {
                File work = workDir();
                Repacker.deleteRecursive(work);
                work.mkdirs();
                File input = new File(work, "input.bin");
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(input)) {
                    if (in == null) throw new IllegalStateException("Cannot open the file");
                    Repacker.copy(in, out);
                }
                File base;
                File splits = null;
                int count = 1;
                if (Repacker.isPlainApk(input)) {
                    base = new File(work, "input.apk");
                    if (!input.renameTo(base)) throw new IllegalStateException("Cannot prepare the APK");
                } else {
                    splits = new File(work, "splits");
                    List<File> apks = Repacker.extractApks(input, splits);
                    //noinspection ResultOfMethodCallIgnored
                    input.delete();
                    count = apks.size();
                    base = Repacker.findBase(apks);
                }
                PackageInfo info = getPackageManager().getPackageArchiveInfo(base.getPath(), 0);
                if (info == null) throw new IllegalStateException("This doesn't look like a valid APK");
                ApplicationInfo ai = info.applicationInfo;
                ai.sourceDir = base.getPath();
                ai.publicSourceDir = base.getPath();
                CharSequence label = ai.loadLabel(getPackageManager());
                android.graphics.drawable.Drawable drawable = ai.loadIcon(getPackageManager());
                File fBase = base, fSplits = splits;
                int fCount = count;
                ui.post(() -> {
                    baseApk = fBase;
                    splitDir = fSplits;
                    splitCount = fCount;
                    oldPkg = info.packageName;
                    appName.setText(label);
                    icon.setImageDrawable(drawable);
                    icon.setVisibility(View.VISIBLE);
                    String details = info.packageName + "\nversion " + info.versionName;
                    if (fCount > 1) details += " · " + fCount + " split APKs";
                    appDetails.setText(details);
                    String note = KnownApps.note(oldPkg, label);
                    if (note != null) {
                        warn.setText(note);
                        warn.setVisibility(View.VISIBLE);
                    }
                    boolean alreadyRenamed = oldPkg.endsWith(".px");
                    rename.setChecked(!alreadyRenamed);
                    newPkg.setText(prefs().getString("pkg:" + oldPkg, oldPkg + ".px"));
                    newPkg.setEnabled(rename.isChecked());
                    appendLog("Ready. Tap “Repack & install”.");
                    setBusy(false);
                });
            } catch (Throwable t) {
                ui.post(() -> {
                    baseApk = null;
                    appDetails.setText("Couldn't read this file.");
                    appendError(t);
                    setBusy(false);
                });
            }
        });
    }

    // ---- Repack + install ----------------------------------------------------

    private void repackAndInstall() {
        if (busy || baseApk == null) return;
        boolean doRename = rename.isChecked();
        String target = newPkg.getText().toString().trim();
        if (doRename && !target.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) {
            appendLog("✗ Enter a valid package name, e.g. " + oldPkg + ".px");
            return;
        }
        boolean needsSigning = doRename || splitCount > 1;
        if (needsSigning && !Keys.has(this)) {
            appendLog("✗ Import your signing key first (button at the bottom).");
            return;
        }
        if (doRename) prefs().edit().putString("pkg:" + oldPkg, target).apply();
        setBusy(true);
        open.setVisibility(View.GONE);
        clearLog();
        installedPkg = doRename ? target : oldPkg;

        worker.execute(() -> {
            try {
                File apk = baseApk;
                if (needsSigning) {
                    File work = workDir();
                    File unsigned = new File(work, "unsigned.apk");
                    File signed = new File(work, "signed.apk");
                    ApkModule module = Repacker.load(baseApk, splitDir, splitCount, this::appendLogAsync);
                    try {
                        if (doRename) Repacker.rename(module, target, this::appendLogAsync);
                        Repacker.writeUnsigned(module, unsigned, this::appendLogAsync);
                    } finally {
                        module.close();
                    }
                    Repacker.sign(unsigned, signed, Keys.load(this), this::appendLogAsync);
                    //noinspection ResultOfMethodCallIgnored
                    unsigned.delete();
                    apk = signed;
                }
                appendLogAsync("Opening installer…");
                Installer.install(this, apk);
            } catch (Throwable t) {
                ui.post(() -> {
                    appendError(t);
                    setBusy(false);
                });
            }
        });
    }

    private void onInstallResult(Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION:
                Intent confirm = Build.VERSION.SDK_INT >= 33
                        ? intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                        : intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(confirm);
                }
                return;
            case PackageInstaller.STATUS_SUCCESS:
                appendLog("✓ Installed " + installedPkg);
                String hint = KnownApps.installedHint(oldPkg);
                if (hint != null) appendLog(hint);
                open.setVisibility(View.VISIBLE);
                cleanUp();
                break;
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                appendLog("✗ Install cancelled or blocked."
                        + (rename.isChecked() ? "" : " Try again with “Rename package” on."));
                break;
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                appendLog("✗ " + (msg != null ? msg : "Conflicts with an installed app")
                        + "\nAn app with this package name is installed with a different signature."
                        + " Uninstall it first, or pick another package name.");
                break;
            default:
                appendLog("✗ Install failed" + (msg != null ? ": " + msg : ""));
        }
        setBusy(false);
    }

    /** Deletes the copied file, extracted splits and repacked APK once installed. */
    private void cleanUp() {
        baseApk = null;
        splitDir = null;
        worker.execute(() -> {
            File work = workDir();
            long bytes = sizeOf(work);
            Repacker.deleteRecursive(work);
            if (bytes > 0) appendLogAsync("Freed " + (bytes >> 20) + " MB of temporary files.");
        });
    }

    private static long sizeOf(File f) {
        if (f.isFile()) return f.length();
        long total = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) total += sizeOf(k);
        return total;
    }

    private void openInstalled() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(installedPkg);
        if (launch != null) startActivity(launch);
        else appendLog("Couldn't find a launcher screen for " + installedPkg);
    }

    // ---- Signing key ---------------------------------------------------------

    private void askKeyPassword(Uri uri) {
        EditText pass = new EditText(this);
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pass.setHint("Keystore password");
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        pass.setPadding(pad, pad / 2, pad, pad / 2);
        new AlertDialog.Builder(this)
                .setTitle("Import signing key")
                .setMessage("Password for this .p12 file")
                .setView(pass)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Import", (d, w) -> {
                    String p = pass.getText().toString();
                    worker.execute(() -> {
                        try {
                            Keys.SigningKey k = Keys.importFrom(this, uri, p);
                            ui.post(() -> onKeyImported(k));
                        } catch (Throwable t) {
                            ui.post(() -> appendError(t));
                        }
                    });
                })
                .show();
    }

    private void updateKeyStatus() {
        if (!Keys.has(this)) {
            keyStatus.setText("No signing key yet");
            return;
        }
        worker.execute(() -> {
            String text;
            try {
                text = "Signing " + Keys.load(this).shortFingerprint();
            } catch (Throwable t) {
                text = "Signing key unreadable — import it again";
            }
            String fText = text;
            ui.post(() -> keyStatus.setText(fText));
        });
    }

    // ---- Helpers -------------------------------------------------------------

    private File workDir() {
        return new File(getCacheDir(), "work");
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("settings", MODE_PRIVATE);
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
        }
        return uri.getLastPathSegment();
    }

    private void setBusy(boolean b) {
        busy = b;
        go.setEnabled(!b && baseApk != null);
        go.setText(b ? "Working…" : "Repack & install");
    }

    private void clearLog() {
        log.setText("");
    }

    private void appendLog(String line) {
        CharSequence cur = log.getText();
        log.setText(cur.length() == 0 ? line : cur + "\n" + line);
    }

    private void appendLogAsync(String line) {
        ui.post(() -> appendLog(line));
    }

    private void appendError(Throwable t) {
        String m = t.getMessage();
        appendLog("✗ " + t.getClass().getSimpleName() + (m != null ? ": " + m : ""));
        if (t instanceof OutOfMemoryError) appendLog("This app is too large to repack on the phone.");
    }
}
