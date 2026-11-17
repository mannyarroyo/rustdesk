package com.ustreaming.setup;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Installs U-Streaming Support through a PackageInstaller session.
 *
 * Android 13+ blocks the accessibility switch of apps installed from a
 * downloaded file (Downloader, browsers). Apps installed through a session
 * like this one are not blocked.
 */
public class SetupActivity extends Activity {
    private static final String TAG = "ustreaming-setup";
    private static final String TARGET = "com.ustreaming.support";
    private static final String APK_URL =
            "https://github.com/mannyarroyo/rustdesk/releases/latest/download/u-streaming-support.apk";
    private static final String ACTION_INSTALL_RESULT = "com.ustreaming.setup.INSTALL_RESULT";
    // PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE / PACKAGE_SOURCE_DOWNLOADED_FILE
    private static final int SOURCE_LOCAL_FILE = 3;
    private static final int SOURCE_DOWNLOADED_FILE = 4;

    private enum Step { START, NEED_PERMISSION, NEED_UNINSTALL, DOWNLOADING, INSTALLING, DONE, FAILED }

    private final Handler main = new Handler(Looper.getMainLooper());
    private Step step = Step.START;
    private TextView title;
    private TextView message;
    private Button button;
    private File apk;
    private boolean permissionTried;
    private long hintsUntil;
    private final Runnable hint = new Runnable() {
        @Override
        public void run() {
            if (System.currentTimeMillis() > hintsUntil) return;
            String text = canInstall()
                    ? "Done! Now press BACK on your remote."
                    : "Press DOWN to \"U-Streaming Setup\", click it to turn it On, then press BACK.";
            Toast.makeText(getApplicationContext(), text, Toast.LENGTH_SHORT).show();
            main.postDelayed(this, 8000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        show(Step.START);
        handleResult(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleResult(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        stopHints();
        // Returning from the "install unknown apps" switch or the uninstall prompt.
        if (step == Step.NEED_PERMISSION) {
            if (canInstall()) {
                next();
            } else if (permissionTried) {
                show(Step.NEED_PERMISSION);
                title.setText("Not allowed yet");
            }
        } else if (step == Step.NEED_UNINSTALL && !isInstalled()) {
            next();
        }
    }

    private void onButton() {
        switch (step) {
            case START:
            case FAILED:
                next();
                break;
            case NEED_PERMISSION:
                openInstallPermission();
                break;
            case NEED_UNINSTALL:
                startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + TARGET)));
                break;
            case DONE:
                openSupportApp();
                break;
            default:
                break;
        }
    }

    /** Works out the next step from the current state of the box. */
    private void next() {
        if (!canInstall()) {
            show(Step.NEED_PERMISSION);
            return;
        }
        if (isBlockedCopyInstalled()) {
            show(Step.NEED_UNINSTALL);
            return;
        }
        download();
    }

    private boolean canInstall() {
        return Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls();
    }

    private void openInstallPermission() {
        if (Build.VERSION.SDK_INT < 26) return;
        permissionTried = true;
        // TV settings ignore the package and show the whole list of apps,
        // so keep reminding the customer what to look for.
        hintsUntil = System.currentTimeMillis() + 3 * 60 * 1000;
        main.postDelayed(hint, 1500);
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            Log.e(TAG, "no unknown-sources screen", e);
            startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
        }
    }

    private void stopHints() {
        hintsUntil = 0;
        main.removeCallbacks(hint);
    }

    private boolean isInstalled() {
        try {
            getPackageManager().getPackageInfo(TARGET, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** A copy installed from a downloaded file can never have its accessibility switch turned on. */
    private boolean isBlockedCopyInstalled() {
        if (Build.VERSION.SDK_INT < 33 || !isInstalled()) return false;
        try {
            InstallSourceInfo info = getPackageManager().getInstallSourceInfo(TARGET);
            int source = info.getPackageSource();
            return source == SOURCE_LOCAL_FILE || source == SOURCE_DOWNLOADED_FILE;
        } catch (Exception e) {
            Log.e(TAG, "install source", e);
            return false;
        }
    }

    private void download() {
        show(Step.DOWNLOADING);
        new Thread(() -> {
            try {
                apk = new File(getCacheDir(), "u-streaming-support.apk");
                fetch(APK_URL, apk);
                main.post(this::install);
            } catch (Exception e) {
                Log.e(TAG, "download failed", e);
                main.post(() -> fail("Could not download. Check the internet connection and try again."));
            }
        }).start();
    }

    private void fetch(String url, File out) throws Exception {
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(20000);
            c.setReadTimeout(30000);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                url = new URL(new URL(url), c.getHeaderField("Location")).toString();
                c.disconnect();
                continue;
            }
            if (code != 200) throw new Exception("HTTP " + code);
            long total = c.getContentLength();
            try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(out)) {
                byte[] buf = new byte[64 * 1024];
                long done = 0;
                int lastPct = -1;
                int n;
                while ((n = in.read(buf)) > 0) {
                    o.write(buf, 0, n);
                    done += n;
                    if (total > 0) {
                        int pct = (int) (done * 100 / total);
                        if (pct != lastPct) {
                            lastPct = pct;
                            main.post(() -> message.setText("Downloading... " + pct + "%"));
                        }
                    }
                }
            }
            return;
        }
        throw new Exception("too many redirects");
    }

    private void install() {
        show(Step.INSTALLING);
        try {
            PackageInstaller pi = getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(TARGET);
            int id = pi.createSession(params);
            try (PackageInstaller.Session s = pi.openSession(id)) {
                try (InputStream in = new FileInputStream(apk);
                     OutputStream out = s.openWrite("base.apk", 0, apk.length())) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    s.fsync(out);
                }
                Intent cb = new Intent(this, SetupActivity.class).setAction(ACTION_INSTALL_RESULT);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
                PendingIntent p = PendingIntent.getActivity(this, 1, cb, flags);
                s.commit(p.getIntentSender());
            }
        } catch (Exception e) {
            Log.e(TAG, "install failed", e);
            fail("Install failed. Press OK to try again.");
        }
    }

    private void handleResult(Intent intent) {
        if (intent == null || !ACTION_INSTALL_RESULT.equals(intent.getAction())) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        Log.d(TAG, "install status " + status + " " + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(confirm);
            }
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            if (apk != null) apk.delete();
            setIconVisible(this, false);
            show(Step.DONE);
            main.postDelayed(this::openSupportApp, 1500);
        } else {
            fail("Install was cancelled. Press OK to try again.");
        }
    }

    static void setIconVisible(android.content.Context c, boolean visible) {
        c.getPackageManager().setComponentEnabledSetting(
                new android.content.ComponentName(c, "com.ustreaming.setup.Launcher"),
                visible ? PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }

    private void openSupportApp() {
        PackageManager pm = getPackageManager();
        Intent launch = pm.getLeanbackLaunchIntentForPackage(TARGET);
        if (launch == null) launch = pm.getLaunchIntentForPackage(TARGET);
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launch);
            finish();
        }
    }

    private void fail(String text) {
        show(Step.FAILED);
        message.setText(text);
    }

    private void show(Step s) {
        step = s;
        button.setVisibility(View.VISIBLE);
        switch (s) {
            case START:
                title.setText("Install U-Streaming Support");
                message.setText("Press OK to install the support app.");
                button.setText("Install");
                break;
            case NEED_PERMISSION:
                title.setText("Step 1 of 2: Allow installing");
                message.setText("On the next screen:\n"
                        + "1.  Press DOWN until you reach \"U-Streaming Setup\"\n"
                        + "2.  Click it to Turn \"U-Streaming Setup\" On\n"
                        + "3.  Press BACK on your remote");
                button.setText("Open settings");
                break;
            case NEED_UNINSTALL:
                title.setText("Remove the old copy first");
                message.setText("An old copy of U-Streaming Support must be removed.\nPress OK, then choose OK to uninstall.");
                button.setText("Remove old copy");
                break;
            case DOWNLOADING:
                title.setText("Installing U-Streaming Support");
                message.setText("Downloading...");
                button.setVisibility(View.INVISIBLE);
                break;
            case INSTALLING:
                title.setText("Step 2 of 2: Install");
                message.setText("When asked, choose Install.");
                button.setVisibility(View.INVISIBLE);
                break;
            case DONE:
                title.setText("Installed");
                message.setText("Opening U-Streaming Support...");
                button.setText("Open U-Streaming Support");
                break;
            case FAILED:
                title.setText("Something went wrong");
                button.setText("Try again");
                break;
        }
        if (button.getVisibility() == View.VISIBLE) button.requestFocus();
    }

    private void buildUi() {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setBackgroundColor(Color.rgb(0x17, 0x17, 0x1B));
        root.setPadding((int) (56 * d), (int) (32 * d), (int) (56 * d), (int) (32 * d));

        ImageView logo = new ImageView(this);
        logo.setImageResource(getResources().getIdentifier("logo", "drawable", getPackageName()));
        root.addView(logo, new LinearLayout.LayoutParams((int) (200 * d), (int) (200 * d)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding((int) (48 * d), 0, 0, 0);
        root.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        title = new TextView(this);
        title.setTextColor(Color.WHITE);
        title.setTextSize(34);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);

        message = new TextView(this);
        message.setTextColor(Color.WHITE);
        message.setTextSize(22);
        message.setPadding(0, (int) (16 * d), 0, (int) (28 * d));
        col.addView(message);

        button = new Button(this);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setTextSize(24);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setPadding((int) (32 * d), (int) (14 * d), (int) (32 * d), (int) (14 * d));
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, pill(d, true));
        bg.addState(new int[]{}, pill(d, false));
        button.setBackground(bg);
        button.setFocusable(true);
        button.setOnClickListener(v -> onButton());
        col.addView(button, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    private GradientDrawable pill(float d, boolean focused) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.rgb(0xE8, 0x40, 0x0A));
        g.setCornerRadius(14 * d);
        if (focused) g.setStroke((int) (4 * d), Color.WHITE);
        return g;
    }
}
