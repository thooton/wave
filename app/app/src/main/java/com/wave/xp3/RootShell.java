package com.wave.xp3;

import android.content.Context;
import android.content.SharedPreferences;

import com.wave.xp3.core.root.ProcessShell;
import com.wave.xp3.core.root.Shell;

import java.io.IOException;

/**
 * Wave's one root shell (design section 4, "Root"): a single long-lived `su`
 * process for the whole app, opened the first time the user asks for
 * something that needs it, never before. {@link ProcessShell} (in :core)
 * does the work; this holds it and remembers whether root has ever worked
 * here, so that opening the app on a phone without root never calls `su`
 * by itself.
 *
 * Root is Magisk's to grant: the user allows Wave in Magisk's Superuser
 * screen (or answers its prompt). Wave never grants itself anything.
 */
public final class RootShell {

    private RootShell() {}

    private static final String PREFS = "root";
    private static final String WORKED = "worked";

    private static ProcessShell shell;

    public static synchronized Shell get() {
        if (shell == null) shell = ProcessShell.su();
        return shell;
    }

    /**
     * Runs a script as root. Call on a worker, never the main thread: the
     * first call may wait for Magisk's prompt.
     */
    public static Shell.Result run(Context context, String script, long timeoutMs) throws IOException {
        try {
            Shell.Result result = get().run(script, timeoutMs);
            remember(context, true);
            return result;
        } catch (Shell.NoRoot e) {
            remember(context, false);
            throw e;
        }
    }

    /** True once root has worked and not been refused since. */
    public static boolean known(Context context) {
        return prefs(context).getBoolean(WORKED, false);
    }

    private static void remember(Context context, boolean worked) {
        SharedPreferences p = prefs(context);
        if (p.getBoolean(WORKED, false) != worked) p.edit().putBoolean(WORKED, worked).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized void close() {
        if (shell != null) shell.close();
    }
}
