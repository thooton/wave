package com.wave.xp3.app;

import android.content.Context;

import com.wave.xp3.data.SourceController;
import com.wave.xp3.data.WaveData;

/** Where the screens get their data from. */
final class Data {

    private Data() {}

    static WaveData open(Context context) {
        return new SourceController(context);
    }
}
