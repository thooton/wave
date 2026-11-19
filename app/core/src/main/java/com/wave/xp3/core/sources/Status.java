package com.wave.xp3.core.sources;

import com.wave.xp3.core.Version;

/** On-device state of a source candidate, including Magisk's pending changes. */
public enum Status {
    NOT_INSTALLED, INSTALLED, UPDATE, NEWER, PENDING_INSTALL, PENDING_REMOVAL;

    public interface Phone {
        /** Reads package-manager or module state. Null means there is no installed or pending copy. */
        Installed find(String id, String type);
    }

    public static final class Installed {
        public final String version;
        public final boolean pendingInstall, pendingRemoval;
        public Installed(String version, boolean pendingInstall, boolean pendingRemoval) {
            this.version = version;
            this.pendingInstall = pendingInstall;
            this.pendingRemoval = pendingRemoval;
        }
    }

    public static Status of(PackageManifest pkg, Phone phone) {
        if (!pkg.supportedType()) return NOT_INSTALLED;
        Installed installed = phone.find(pkg.id, pkg.type);
        if (installed == null) return NOT_INSTALLED;
        if (installed.pendingRemoval) return PENDING_REMOVAL;
        if (installed.pendingInstall) return PENDING_INSTALL;
        int comparison = Version.compare(installed.version, pkg.version);
        return comparison < 0 ? UPDATE : comparison > 0 ? NEWER : INSTALLED;
    }
}
