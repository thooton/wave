package com.wave.xp3;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.wave.xp3.core.install.Engine;
import com.wave.xp3.core.root.Modules;
import com.wave.xp3.core.root.PmResult;
import com.wave.xp3.core.root.Scripts;
import com.wave.xp3.core.root.Shell;
import com.wave.xp3.core.root.Watchdog;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What Wave says when it opens on a phone where root has worked before: how
 * its own update went (it was stopped part way, see Scripts.selfUpdate),
 * which mods the boot watchdog turned off and why, with Remove and Try Again
 * (design section 5), and, after Magisk's safe mode turned every module off,
 * a way to turn them back on one at a time.
 *
 * Runs on a worker; dialogs go to the activity while it is still there.
 */
public final class AfterBoot {

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());

    AfterBoot(Activity activity) {
        this.activity = activity;
    }

    /**
     * How Wave's own update through root went, said once when Wave opens
     * again (Scripts.selfUpdate leaves pm's answer behind). Only where root
     * has worked before, so it never brings up a prompt; on a worker.
     *
     * The worker holds the application, not the Activity: its Thread, and
     * so its Runnable, stayed reachable after it had ended (a VM-internal
     * root in tools/qa/soak.py's heap dumps), and with it a closed Activity
     * and all its views.
     * The Activity is looked up again on the main thread when there is
     * something to say; if it has closed by then, a failure is still said, as
     * a toast.
     */
    public static void selfUpdate(Activity activity) {
        final android.content.Context app = activity.getApplicationContext();
        final WeakReference<Activity> shown = new WeakReference<>(activity);
        new Thread(() -> {
            try {
                // The preference is read here, off the main thread.
                if (!RootShell.known(app)) return;
                String said = RootShell.run(app, Scripts.selfUpdateResult(), Engine.QUICK_MS).output.trim();
                if (said.isEmpty()) return;
                final PmResult pm = PmResult.parse(said, 0);
                new Handler(Looper.getMainLooper()).post(() -> {
                    Activity now = shown.get();
                    if (now == null || now.isDestroyed() || now.isFinishing()) {
                        Toast.makeText(app, pm.ok ? "Wave was updated" : "Wave wasn't updated: " + pm.message,
                            Toast.LENGTH_LONG).show();
                    } else if (pm.ok) {
                        new AfterBoot(now).toast("Wave was updated");
                    } else {
                        new AfterBoot(now).alert("Wave wasn't updated", pm.message);
                    }
                });
            } catch (IOException e) {
                // No root now, or it didn't answer: nothing to say.
            }
        }, "wave-self-update").start();
    }

    private void selfUpdateResult() throws IOException {
        String said = root(Scripts.selfUpdateResult()).output.trim();
        if (said.isEmpty()) return;
        final PmResult pm = PmResult.parse(said, 0);
        post(() -> {
            if (pm.ok) toast("Wave was updated");
            else alert("Wave wasn't updated", pm.message);
        });
    }

    void run() {
        try {
            selfUpdateResult();
            List<Modules.Module> modules = Modules.parse(root(Scripts.listModules()).output);
            final Map<String, String> names = new HashMap<>();
            for (Modules.Module m : modules) names.put(m.id, m.name);
            Watchdog.State state = Watchdog.parse(root(Watchdog.read()).output);
            if (!state.disabled.isEmpty()) {
                final List<Watchdog.Disabled> off = state.disabled;
                post(() -> explain(off, 0, names));
            } else if (Modules.allDisabled(modules)) {
                final List<Modules.Module> all = modules;
                post(() -> safeMode(all));
            }
        } catch (IOException e) {
            // No root now, or it didn't answer: nothing to say.
        }
    }

    /** One dialog per mod the watchdog turned off, in turn. */
    private void explain(final List<Watchdog.Disabled> off, final int at, final Map<String, String> names) {
        if (at >= off.size()) {
            work(() -> root(Watchdog.acknowledge()), null);
            return;
        }
        final Watchdog.Disabled d = off.get(at);
        String name = names.containsKey(d.id) ? names.get(d.id) : d.id;
        new AlertDialog.Builder(activity)
            .setTitle(name + " was turned off")
            .setMessage(d.why() + " Wave turned it off so the phone could start.")
            .setPositiveButton("Remove", (dialog, which) -> work(() -> root(Scripts.removeModule(d.id)),
                "Removed when the phone restarts"))
            .setNeutralButton("Try Again", (dialog, which) -> work(() -> root(Scripts.enableModule(d.id)),
                "On again when the phone restarts"))
            .setNegativeButton("Leave Off", null)
            .setOnDismissListener(dialog -> explain(off, at + 1, names))
            .show();
    }

    private void safeMode(final List<Modules.Module> modules) {
        final List<Modules.Module> disabled = new ArrayList<>();
        for (Modules.Module m : modules) if (m.state == Modules.State.DISABLED) disabled.add(m);
        String[] items = new String[disabled.size()];
        for (int i = 0; i < items.length; i++) items[i] = disabled.get(i).name;
        new AlertDialog.Builder(activity)
            .setTitle("Every mod is off")
            .setItems(items, (dialog, which) -> {
                final Modules.Module m = disabled.get(which);
                work(() -> root(Scripts.enableModule(m.id)), m.name + " is on again when the phone restarts");
            })
            .setNegativeButton("Leave Off", null)
            .show();
    }

    private interface Job {
        Shell.Result run() throws IOException;
    }

    private void work(final Job job, final String done) {
        new Thread(() -> {
            try {
                Shell.Result r = job.run();
                if (done != null) {
                    post(() -> toast(r.ok() ? done : "That didn't work."));
                }
            } catch (final IOException e) {
                post(() -> toast(e.getMessage()));
            }
        }, "wave-after-boot").start();
    }

    private Shell.Result root(String script) throws IOException {
        return RootShell.run(activity, script, Engine.QUICK_MS);
    }

    private void post(final Runnable r) {
        main.post(() -> {
            if (!activity.isDestroyed() && !activity.isFinishing()) r.run();
        });
    }

    private void toast(String text) {
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
    }

    private void alert(String title, String message) {
        new AlertDialog.Builder(activity).setTitle(title).setMessage(message)
            .setPositiveButton(android.R.string.ok, null).show();
    }
}
