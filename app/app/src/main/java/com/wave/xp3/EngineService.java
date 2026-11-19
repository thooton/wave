package com.wave.xp3;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.wave.xp3.core.install.Engine;
import com.wave.xp3.core.install.Plan;
import com.wave.xp3.core.install.Receipts;

import java.io.File;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Runs one plan at a time through the {@link Engine}, in a foreground
 * service so the work carries on when the screen that started it goes away
 * (Back, another app, the activity being recreated). Screens follow it with
 * {@link #listen}; the engine itself lives here, not in an activity.
 */
public final class EngineService extends Service {

    public interface Listener {
        /** On the main thread. */
        void changed(Engine engine);
    }

    private static final String CHANNEL = "work";
    private static final int NOTIFICATION = 1;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile Engine current;
    private static volatile Plan pending;
    private static volatile String pendingSource;

    /** The run in progress or the last one, or null. */
    public static Engine current() {
        return current;
    }

    public static boolean busy() {
        Engine e = current;
        return e != null && !e.finished();
    }

    public static void listen(Listener listener) {
        LISTENERS.add(listener);
    }

    public static void unlisten(Listener listener) {
        LISTENERS.remove(listener);
    }

    /**
     * Whether `plan` would go through root: any module, or root has worked
     * here before. Otherwise apps go through Installer (StockApps), and
     * Wave never opens `su` by itself.
     */
    public static boolean usesRoot(Context context, Plan plan) {
        for (Plan.Step step : plan.steps) if (step.kind == Plan.Kind.MODULE) return true;
        return RootShell.known(context);
    }

    /** Starts `plan`; false when a run is already going. */
    public static synchronized boolean start(Context context, Plan plan, String source) {
        if (busy() || pending != null) return false;
        pending = plan;
        pendingSource = source;
        context.startForegroundService(new Intent(context, EngineService.class));
        return true;
    }

    /** Asks the run in progress to stop after its current step. */
    public static void stop() {
        Engine e = current;
        if (e != null) e.stop();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int id) {
        startForeground(NOTIFICATION, notification("Getting ready…"));
        final Plan plan;
        final String source;
        synchronized (EngineService.class) {
            plan = pending;
            source = pendingSource;
            pending = null;
        }
        if (plan == null) {
            if (!busy()) stopSelf();
            return START_NOT_STICKY;
        }
        final Phone phone = new Phone(this);
        final Receipts receipts = Receipts.load(new File(getFilesDir(), "receipts.json"));
        final Engine engine = new Engine(plan, RootShell.get(), phone, receipts, this::changed, source,
            getPackageName());
        if (!usesRoot(this, plan)) engine.apps(new StockApps(this));
        current = engine;
        Thread worker = new Thread(() -> {
            engine.run();
            MAIN.post(() -> {
                stopForeground(true);
                stopSelf();
            });
        }, "wave-engine");
        worker.start();
        return START_NOT_STICKY;
    }

    private long lastNotified;

    private void changed(final Engine engine) {
        long now = System.currentTimeMillis();
        if (now - lastNotified > 1000 || engine.finished()) {
            lastNotified = now;
            String text = describe(engine);
            getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(text));
        }
        MAIN.post(() -> {
            for (Listener l : LISTENERS) l.changed(engine);
        });
    }

    /** One line for the notification: the step under way. */
    static String describe(Engine engine) {
        for (Engine.Item i : engine.items()) {
            switch (i.phase) {
                case PREPARING: return "Preparing " + i.step.name;
                case DOWNLOADING: return "Downloading " + i.step.name + (i.percent >= 0 ? " " + i.percent + "%" : "");
                case CHECKING: return "Checking " + i.step.name;
                case INSTALLING: return "Installing " + i.step.name;
                case REMOVING: return "Removing " + i.step.name;
                default: break;
            }
        }
        return engine.succeeded() ? "Done" : "Finished";
    }

    private Notification notification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Installing",
                NotificationManager.IMPORTANCE_LOW));
        }
        return new Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Wave")
            .setContentText(text)
            .setOngoing(true)
            .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
