package com.wave.xp3.app;

import android.app.Activity;
import android.content.Context;

import com.wave.xp3.data.SourceController;
import com.wave.xp3.data.WaveData;

/**
 * Where the screens get their data from: the sources, the phone and the
 * queue (SourceController). A debug build started with the extra
 * "fixture" gets the mockups' sample data instead (FakeWaveData, in
 * src/debug only), for walking every screen on a phone without changing it.
 */
final class Data {

    private Data() {}

    static WaveData open(Context context) {
        if (context instanceof Activity) {
            String fixture = ((Activity) context).getIntent().getStringExtra("fixture");
            if (fixture != null) {
                try {
                    Class<?> fake = Class.forName("com.wave.xp3.app.FakeWaveData");
                    return (WaveData) fake.getConstructor(String.class).newInstance(fixture);
                } catch (ReflectiveOperationException absentInRelease) {
                    // A release build has no fixture: the real thing.
                }
            }
        }
        return new SourceController(context);
    }
}
