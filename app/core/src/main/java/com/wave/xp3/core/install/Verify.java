package com.wave.xp3.core.install;

import com.wave.xp3.core.Version;
import com.wave.xp3.core.root.Prop;
import com.wave.xp3.core.root.Scripts;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Checks a downloaded file is what its manifest says, before anything is
 * installed (design section 4): a module zip's `module.prop` id and version.
 *
 * For an APK the platform reads the file ({@code PackageManager
 * .getPackageArchiveInfo}, in :app: the same parser `pm` will use, which a
 * binary-manifest reader of our own could only imitate) and hands what it
 * found to {@link #apk}, which decides.
 */
public final class Verify {

    private Verify() {}

    /** The largest module.prop read; real ones are a few hundred bytes. */
    private static final int MAX_PROP = 64 * 1024;

    /** `module.prop` of a module zip; throws if it isn't one. */
    public static Map<String, String> moduleProp(File zip) throws IOException {
        ZipFile file;
        try {
            file = new ZipFile(zip);
        } catch (IOException e) {
            throw new IOException("The download isn't a module.");
        }
        try {
            ZipEntry entry = file.getEntry("module.prop");
            if (entry == null || entry.isDirectory()) throw new IOException("The download isn't a module.");
            InputStream in = file.getInputStream(entry);
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                    if (out.size() > MAX_PROP) throw new IOException("The module's module.prop is far too large.");
                }
                return Prop.parse(new String(out.toByteArray(), "UTF-8"));
            } finally {
                in.close();
            }
        } finally {
            file.close();
        }
    }

    /** Why a module zip can't be installed as `id` `version`, or null. */
    public static String module(File zip, String id, String version) {
        Map<String, String> prop;
        try {
            prop = moduleProp(zip);
        } catch (IOException e) {
            return e.getMessage();
        }
        String found = prop.get("id");
        if (found == null || !Scripts.isModuleId(found)) return "The module has no usable id.";
        if (!found.equals(id)) return "The download is module " + found + ", not " + id + ".";
        String v = prop.get("version");
        if (v == null || Version.compare(v, version) != 0) {
            return "The download is version " + (v == null ? "(none)" : v) + ", not " + version + ".";
        }
        return null;
    }

    /**
     * Why an APK can't be installed as `id` `version`, or null. `found*` is
     * what the platform read from the file (null package: unreadable);
     * `phoneCode` the installed copy's versionCode, -1 when not installed;
     * `sameKey` whether the file is signed like the installed copy (ignored
     * when not installed).
     */
    public static String apk(String id, String version, String foundPackage, String foundVersion,
            long foundCode, long phoneCode, String phoneVersion, boolean sameKey) {
        if (foundPackage == null) return "The download isn't an app this phone can read.";
        if (!foundPackage.equals(id)) return "The download is " + foundPackage + ", not " + id + ".";
        if (foundVersion == null || Version.compare(foundVersion, version) != 0) {
            return "The download is version " + (foundVersion == null ? "(none)" : foundVersion)
                + ", not " + version + ".";
        }
        if (phoneCode < 0) return null;
        if (!sameKey) return "The phone's copy is signed with a different key.";
        if (foundCode < phoneCode) {
            return "The source's " + foundVersion + " is older than the phone's " + phoneVersion + ".";
        }
        return null;
    }
}
