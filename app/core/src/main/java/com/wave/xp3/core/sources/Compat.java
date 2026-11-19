package com.wave.xp3.core.sources;

/** Installability is distinct from a firmware advisory. */
public final class Compat {
    public static final class Phone {
        public final String device, model, fingerprint;
        public final int sdk, format;
        public Phone(String device, String model, int sdk, String fingerprint, int format) {
            this.device = device;
            this.model = model;
            this.sdk = sdk;
            this.fingerprint = fingerprint;
            this.format = format;
        }
    }

    public final boolean fits;
    /** Null when the package fits. */
    public final String reason;
    /** A warning can coexist with a hard incompatibility. */
    public final String note;

    private Compat(String reason, String note) {
        this.fits = reason == null;
        this.reason = reason;
        this.note = note;
    }

    public static Compat of(Index.Entry entry, Phone phone) {
        return of(entry.manifest, entry.sourceManifest.wave, phone);
    }

    public static Compat of(PackageManifest pkg, long sourceFormat, Phone phone) {
        String reason = null;
        if (!pkg.supportedType() || sourceFormat > phone.format || pkg.requires.wave > phone.format) {
            reason = "Needs a newer Wave";
        } else if (!pkg.requires.device.isEmpty() && !pkg.requires.device.contains(phone.model) &&
            !pkg.requires.device.contains(phone.device)) {
            reason = "Not compatible with this device";
        } else if (pkg.requires.sdk > phone.sdk) {
            reason = "Needs " + androidName(pkg.requires.sdk) + " or later";
        }
        String note = !pkg.requires.firmware.isEmpty() && !pkg.requires.firmware.contains(phone.fingerprint) ?
            "Not tested on this firmware" : null;
        return new Compat(reason, note);
    }

    private static final String[] ANDROID = {"5.0", "5.1", "6.0", "7.0", "7.1", "8.0", "8.1", "9", "10", "11", "12", "12", "13", "14", "15", "16"};

    /** What a person calls an API level: "Android 9", falling back to "Android SDK N" for levels it doesn't know. */
    static String androidName(long sdk) {
        return sdk >= 21 && sdk - 21 < ANDROID.length ? "Android " + ANDROID[(int) (sdk - 21)] : "Android SDK " + sdk;
    }
}
