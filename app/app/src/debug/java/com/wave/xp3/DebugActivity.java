package com.wave.xp3;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.ScrollView;
import android.widget.TextView;

import com.wave.xp3.core.install.Engine;
import com.wave.xp3.core.install.Packages;
import com.wave.xp3.core.install.Plan;
import com.wave.xp3.core.root.Scripts;
import com.wave.xp3.core.root.Shell;
import com.wave.xp3.core.root.Watchdog;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Debug builds only: drives the install engine and the boot watchdog on a
 * rooted phone from adb, for the checks in app/ROOT-TESTING.md. Everything
 * it shows also goes to logcat under "WaveDebug".
 *
 * <pre>adb shell am start -n com.wave.xp3/.DebugActivity --es do facts
 *   do: facts | modules | watchdog | install-watchdog | ack | self-result | plan</pre>
 *
 * `plan` reads Android/data/com.wave.xp3/files/plan.json (or `--es file`),
 * a test plan as Packages.planFile describes, and runs it in EngineService.
 * It never reboots by itself: the procedure says when to.
 */
public final class DebugActivity extends Activity implements EngineService.Listener {

    private static final String TAG = "WaveDebug";
    private TextView out;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        out = new TextView(this);
        out.setTextSize(11);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(out);
        setContentView(scroll);
        final String what = getIntent().getStringExtra("do");
        final String file = getIntent().getStringExtra("file");
        say("do " + what);
        new Thread(() -> act(what == null ? "facts" : what, file), "wave-debug").start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        EngineService.unlisten(this);
    }

    private void act(String what, String file) {
        try {
            switch (what) {
                case "facts": show(Scripts.facts()); break;
                case "modules": show(Scripts.listModules()); break;
                case "watchdog": show(Watchdog.read() + "; cat " + Scripts.STATE + "/log 2>/dev/null | tail -n 20"); break;
                case "install-watchdog": show(Watchdog.install()); break;
                case "ack": show(Watchdog.acknowledge()); break;
                case "self-result": show(Scripts.selfUpdateResult()); break;
                case "plan": plan(file); break;
                default: say("unknown: " + what);
            }
        } catch (IOException | RuntimeException e) {
            say("error: " + e);
        }
    }

    private void show(String script) throws IOException {
        Shell.Result r = RootShell.run(this, script, Engine.QUICK_MS);
        say("exit " + r.exit + "\n" + r.output);
    }

    private void plan(String path) throws IOException {
        File f = path != null ? new File(path) : new File(getExternalFilesDir(null), "plan.json");
        if (!f.isFile()) {
            say("no plan at " + f);
            return;
        }
        String json = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
        Packages.PlanFile file = Packages.planFile(json);
        Phone phone = new Phone(this);
        List<String> ids = new ArrayList<>(file.packages.keySet());
        for (Plan.Request r : file.requests) ids.add(r.id);
        // Modules are read through root only when the plan has one or root has
        // worked here before, as the screens do; so apps also run on a stock phone.
        boolean modules = RootShell.known(this);
        for (Plan.Package p : file.packages.values()) if (p.kind == Plan.Kind.MODULE) modules = true;
        Map<String, Plan.Installed> installed = phone.installed(ids, modules);
        Plan plan;
        try {
            plan = Plan.make(file.requests, file.packages, installed, Phone.device(), getPackageName());
        } catch (Plan.Refused e) {
            say("refused: " + e.getMessage());
            return;
        }
        say("plan: " + plan.steps);
        runOnUiThread(() -> {
            EngineService.listen(this);
            if (!EngineService.start(this, plan, file.source)) say("busy");
        });
    }

    @Override
    public void changed(Engine engine) {
        if (!engine.finished()) return;
        StringBuilder s = new StringBuilder("finished; succeeded=" + engine.succeeded()
            + " reboot=" + engine.rebootNeeded() + " self=" + engine.selfUpdating());
        if (engine.watchdogNote() != null) s.append("\n").append(engine.watchdogNote());
        for (Engine.Item i : engine.items()) s.append("\n").append(i);
        say(s.toString());
        EngineService.unlisten(this);
    }

    private void say(final String text) {
        Log.i(TAG, text);
        runOnUiThread(() -> out.append(text + "\n"));
    }
}
