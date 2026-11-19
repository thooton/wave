package com.wave.xp3.core.root;

import java.util.Map;

/** What {@link Scripts#facts} found: Magisk and the boot watchdog. */
public final class Facts {

    /** Magisk's version name, such as "24.0:MAGISK"; empty without Magisk. */
    public final String magisk;
    /** Magisk's version code, such as 24000; -1 when unknown. */
    public final long magiskCode;
    /** The watchdog scripts' versions on the phone; 0 when missing. */
    public final int watchdogPostFsData;
    public final int watchdogService;

    private Facts(Map<String, String> values) {
        magisk = values.containsKey("magisk") ? values.get("magisk") : "";
        magiskCode = Modules.number(values.get("magiskCode"));
        watchdogPostFsData = (int) Math.max(0, Modules.number(values.get("watchdogPfd")));
        watchdogService = (int) Math.max(0, Modules.number(values.get("watchdogService")));
    }

    public static Facts parse(String output) {
        return new Facts(Prop.parse(output));
    }

    public boolean hasMagisk() {
        return !magisk.isEmpty() || magiskCode > 0;
    }
}
