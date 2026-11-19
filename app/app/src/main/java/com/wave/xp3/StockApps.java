package com.wave.xp3;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;

import com.wave.xp3.core.install.Engine;
import com.wave.xp3.core.root.PmResult;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Installs and removes apps without root, for the engine ({@link
 * Engine.Apps}): the Updater's way, handing the APK to Installer
 * (../installer, which installs without the XP3's whitelist-gated screen)
 * or, without it, to Android's own installer. Those answer only an activity
 * that started them for a result, so this class is also that activity: an
 * invisible one the engine's thread starts, which starts the installer,
 * passes its answer back and closes. The engine's thread waits meanwhile.
 */
public final class StockApps extends Activity implements Engine.Apps {

    private static final ComponentName HELPER =
        new ComponentName("com.installer.xp3", "com.installer.xp3.InstallActivity");
    private static final String HELPER_MESSAGE = "message";
    private static final String HELPER_PACKAGE = "package";
    private static final String EXTRA_TICKET = "ticket";
    private static final String EXTRA_INTENT = "intent";
    /** Long enough to read Android's confirmation screen and press Install. */
    private static final long WAIT_MS = 10 * 60000;
    /** For Android to finish with the package after the installer closes. */
    private static final long SETTLE_MS = 2000;

    private static final AtomicInteger TICKETS = new AtomicInteger();
    private static final Map<Integer, Answer> ANSWERS = new ConcurrentHashMap<>();

    private static final class Answer {
        final CountDownLatch done = new CountDownLatch(1);
        volatile int result = RESULT_CANCELED;
        volatile String message;
    }

    private final Context context;

    /** The activity Android makes. */
    public StockApps() {
        this.context = null;
    }

    /** The engine's side. */
    public StockApps(Context context) {
        this.context = context.getApplicationContext();
    }

    // ------------------------------------------------------------------
    // Engine.Apps: on the engine's thread
    // ------------------------------------------------------------------

    @Override
    public PmResult install(File apk, String packageName, boolean self) throws IOException {
        File folder = ApkProvider.folder(context);
        folder.mkdirs();
        String name = "engine-" + SystemClock.elapsedRealtime();
        File served = new File(folder, name + ".apk");
        if (!apk.renameTo(served)) throw new IOException("Can't hand the download to the installer.");
        try {
            long before = lastUpdate(packageName);
            Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE)
                .setDataAndType(ApkProvider.uri(served.getName()), ApkProvider.APK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(Intent.EXTRA_RETURN_RESULT, true)
                .putExtra(HELPER_PACKAGE, packageName);
            if (hasHelper()) intent.setComponent(HELPER);
            Answer answer = ask(intent);
            if (answer.result == RESULT_OK) return PmResult.success();
            if (answer.message != null) return PmResult.failure("INSTALLER", answer.message);
            // No word: the system installer's Cancel, or Installer replacing
            // itself. The phone's copy says which.
            SystemClock.sleep(SETTLE_MS);
            if (lastUpdate(packageName) != before && lastUpdate(packageName) != -1) return PmResult.success();
            return PmResult.failure("CANCELLED", "It wasn't installed.");
        } finally {
            served.delete();
        }
    }

    @Override
    public PmResult uninstall(String packageName) throws IOException {
        Intent intent = new Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:" + packageName))
            .putExtra(Intent.EXTRA_RETURN_RESULT, true);
        ask(intent);
        SystemClock.sleep(SETTLE_MS);
        if (lastUpdate(packageName) == -1) return PmResult.success();
        return PmResult.failure("CANCELLED", "It wasn't removed.");
    }

    private Answer ask(Intent intent) throws IOException {
        int ticket = TICKETS.incrementAndGet();
        Answer answer = new Answer();
        ANSWERS.put(ticket, answer);
        try {
            context.startActivity(new Intent(context, StockApps.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_TICKET, ticket)
                .putExtra(EXTRA_INTENT, intent));
            if (!answer.done.await(WAIT_MS, TimeUnit.MILLISECONDS)) {
                throw new IOException("The installer didn't answer.");
            }
            return answer;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Stopped.");
        } finally {
            ANSWERS.remove(ticket);
        }
    }

    private long lastUpdate(String packageName) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(packageName, 0);
            return info.lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    private boolean hasHelper() {
        try {
            context.getPackageManager().getActivityInfo(HELPER, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // The activity: invisible, between the engine and the installer
    // ------------------------------------------------------------------

    private boolean answered;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) return; // Recreated: the installer is already open.
        Intent inner = getIntent().getParcelableExtra(EXTRA_INTENT);
        if (inner == null || answer() == null) {
            finish();
            return;
        }
        try {
            startActivityForResult(inner, 1);
        } catch (RuntimeException e) {
            // No installer, or Installer refused Wave (installed before it).
            reply(RESULT_CANCELED, e instanceof SecurityException
                ? "Installer wouldn't take it. Reinstall Wave with adb." : "No installer on this phone.");
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        reply(result, data == null ? null : data.getStringExtra(HELPER_MESSAGE));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!isChangingConfigurations()) reply(RESULT_CANCELED, null);
    }

    private Answer answer() {
        return ANSWERS.get(getIntent().getIntExtra(EXTRA_TICKET, -1));
    }

    private void reply(int result, String message) {
        if (answered) return;
        answered = true;
        Answer a = answer();
        if (a != null) {
            a.result = result;
            a.message = message;
            a.done.countDown();
        }
        finish();
    }
}
