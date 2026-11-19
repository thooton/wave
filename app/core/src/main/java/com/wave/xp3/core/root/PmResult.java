package com.wave.xp3.core.root;

/**
 * What `pm install`, `pm uninstall` and `cmd package compile` answered:
 * a line `Success`, or `Failure [CODE: detail]` / `Failure [CODE]`, or an
 * `Error: ...` when pm refused its arguments. Turned into words for a
 * 240px screen.
 */
public final class PmResult {

    public final boolean ok;
    /** pm's code, such as INSTALL_FAILED_UPDATE_INCOMPATIBLE; null on success. */
    public final String code;
    /** In words, for the user; null on success. */
    public final String message;

    private PmResult(boolean ok, String code, String message) {
        this.ok = ok;
        this.code = code;
        this.message = message;
    }

    public static PmResult success() {
        return new PmResult(true, null, null);
    }

    /** A failure from somewhere other than pm, such as Installer's answer. */
    public static PmResult failure(String code, String message) {
        return new PmResult(false, code, message);
    }

    public static PmResult parse(String output, int exit) {
        String failure = null;
        String error = null;
        boolean success = false;
        for (String raw : (output == null ? "" : output).split("\n")) {
            String line = raw.trim();
            if (line.equals("Success")) success = true;
            else if (line.startsWith("Failure") && failure == null) failure = line;
            else if (line.startsWith("Error:") && error == null) error = line.substring(6).trim();
        }
        if (failure != null) {
            String inside = failure;
            int open = failure.indexOf('[');
            int close = failure.lastIndexOf(']');
            if (open >= 0 && close > open) inside = failure.substring(open + 1, close);
            String code = inside;
            int colon = inside.indexOf(':');
            if (colon >= 0) code = inside.substring(0, colon);
            code = code.trim();
            return new PmResult(false, code, words(code, inside));
        }
        if (success) return new PmResult(true, null, null);
        if (error != null) return new PmResult(false, "ERROR", "Android refused it: " + error);
        String last = lastLine(output);
        return new PmResult(false, "UNKNOWN", last.isEmpty()
            ? "Android gave no answer (exit " + exit + ")."
            : "Android said: " + last);
    }

    private static String lastLine(String output) {
        if (output == null) return "";
        String[] lines = output.trim().split("\n");
        return lines.length == 0 ? "" : lines[lines.length - 1].trim();
    }

    private static String words(String code, String whole) {
        if (code.equals("INSTALL_FAILED_UPDATE_INCOMPATIBLE")
                || code.equals("INSTALL_FAILED_SHARED_USER_INCOMPATIBLE")) {
            return "The phone's copy is signed with a different key.";
        }
        if (code.equals("INSTALL_FAILED_INSUFFICIENT_STORAGE")) return "The phone is out of space.";
        if (code.equals("INSTALL_FAILED_VERSION_DOWNGRADE")) return "The phone has a newer version.";
        if (code.equals("INSTALL_FAILED_OLDER_SDK")) return "It needs a newer Android.";
        if (code.equals("INSTALL_FAILED_NO_MATCHING_ABIS")) return "It isn't built for this phone's processor.";
        if (code.equals("INSTALL_FAILED_DUPLICATE_PERMISSION")) return "Another app already defines one of its permissions.";
        if (code.equals("INSTALL_FAILED_CONFLICTING_PROVIDER")) return "Another app already uses one of its names.";
        if (code.equals("INSTALL_FAILED_INVALID_APK") || code.startsWith("INSTALL_PARSE_FAILED")) {
            return "The file isn't a valid app.";
        }
        if (code.equals("INSTALL_FAILED_ABORTED")) return "The install was stopped.";
        if (code.equals("DELETE_FAILED_INTERNAL_ERROR") || code.startsWith("not installed")) {
            return "It isn't installed.";
        }
        if (code.equals("DELETE_FAILED_DEVICE_POLICY_MANAGER")) return "It's a device administrator and can't be removed.";
        return "Android refused it: " + whole;
    }
}
