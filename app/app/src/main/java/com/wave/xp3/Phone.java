package com.wave.xp3;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import com.wave.xp3.core.install.Engine;
import com.wave.xp3.core.install.Plan;
import com.wave.xp3.core.root.Modules;
import com.wave.xp3.core.root.Scripts;
import com.wave.xp3.core.root.Shell;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What is on this phone, for planning and for each package's status: apps
 * from PackageManager, modules from `/data/adb` through root. Also the
 * engine's view of the platform ({@link Engine.Platform}).
 */
public final class Phone implements Engine.Platform {

    private final Context context;

    public Phone(Context context) {
        this.context = context.getApplicationContext();
    }

    /** The installed copy of each app in `ids` (others are left out). */
    public Map<String, Plan.Installed> apps(Iterable<String> ids) {
        Map<String, Plan.Installed> out = new LinkedHashMap<>();
        PackageManager pm = context.getPackageManager();
        for (String id : ids) {
            try {
                PackageInfo info = pm.getPackageInfo(id, 0);
                out.put(id, new Plan.Installed(id, Plan.Kind.APK, version(info), false));
            } catch (PackageManager.NameNotFoundException e) {
                // Not installed.
            }
        }
        return out;
    }

    /** Every Magisk module, through root. Call on a worker. */
    public List<Modules.Module> modules() throws IOException {
        Shell.Result r = RootShell.run(context, Scripts.listModules(), Engine.QUICK_MS);
        return Modules.parse(r.output);
    }

    /** Installed apps among `apkIds`, and every module, as the planner wants them. */
    public Map<String, Plan.Installed> installed(Iterable<String> apkIds, boolean withModules) throws IOException {
        Map<String, Plan.Installed> out = apps(apkIds);
        if (withModules) {
            for (Modules.Module m : modules()) {
                out.put(m.id, new Plan.Installed(m.id, Plan.Kind.MODULE, m.version,
                    m.state == Modules.State.PENDING_REMOVAL));
            }
        }
        out.put(context.getPackageName(), new Plan.Installed(context.getPackageName(), Plan.Kind.APK,
            selfVersion(), false));
        return out;
    }

    public static Plan.Device device() {
        return new Plan.Device(Build.VERSION.SDK_INT, Build.DEVICE, Build.MODEL);
    }

    private String selfVersion() {
        try {
            return version(context.getPackageManager().getPackageInfo(context.getPackageName(), 0));
        } catch (PackageManager.NameNotFoundException e) {
            return "0";
        }
    }

    // ------------------------------------------------------------------
    // Engine.Platform
    // ------------------------------------------------------------------

    @Override
    public File downloads() {
        File dir = new File(context.getCacheDir(), "downloads");
        dir.mkdirs();
        return dir;
    }

    /**
     * Read by the platform's own parser, the one `pm` uses, rather than a
     * binary-manifest reader of Wave's own: what it says is what the
     * install will see.
     */
    @Override
    public Engine.Apk read(File apk, String packageName) {
        PackageManager pm = context.getPackageManager();
        PackageInfo info = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNATURES);
        if (info == null) return new Engine.Apk(null, null, 0, -1, null, false);
        PackageInfo here = null;
        try {
            here = pm.getPackageInfo(info.packageName, PackageManager.GET_SIGNATURES);
        } catch (PackageManager.NameNotFoundException e) {
            // Not installed.
        }
        return new Engine.Apk(info.packageName, info.versionName, code(info),
            here == null ? -1 : code(here), here == null ? null : version(here),
            here != null && Arrays.equals(here.signatures, info.signatures));
    }

    @Override
    public String selfActivity() {
        return ".app.WaveActivity";
    }

    // getLongVersionCode arrives in API 28; the XP3 is 27.
    @SuppressWarnings("deprecation")
    static long code(PackageInfo info) {
        return info.versionCode;
    }

    static String version(PackageInfo info) {
        return info.versionName != null ? info.versionName : String.valueOf(code(info));
    }
}
