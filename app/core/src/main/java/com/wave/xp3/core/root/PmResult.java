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
        boolean exception = false;
        for (String raw : (output == null ? "" : output).split("\n")) {
            String line = raw.trim();
            if (line.equals("Success")) success = true;
            else if (line.startsWith("Failure") && failure == null) failure = line;
            else if (line.startsWith("Error:") && error == null) error = line.substring(6).trim();
            else if (line.startsWith("Exception occurred while executing")) exception = true;
            else if (exception && error == null && !line.isEmpty()) {
                // `cmd` throwing: the first line after is the exception and
                // its message; the rest is a stack trace.
                int colon = line.indexOf(": ");
                error = colon > 0 && line.substring(0, colon).matches("[\\w.$]+") ? line.substring(colon + 2) : line;
            }
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
        if (success && !exception) return new PmResult(true, null, null);
        if (error != null) return new PmResult(false, "ERROR", "Android refused the request: " + error);
        String last = lastLine(output);
        return new PmResult(false, "UNKNOWN", last.isEmpty()
            ? "Android didn't answer (exit code " + exit + ")."
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
            return "The installed app has a different signing key.";
        }
        if (code.equals("INSTALL_FAILED_INSUFFICIENT_STORAGE")) return "Not enough space.";
        if (code.equals("INSTALL_FAILED_VERSION_DOWNGRADE")) return "A newer version is installed.";
        if (code.equals("INSTALL_FAILED_OLDER_SDK")) return "The app needs a newer Android version.";
        if (code.equals("INSTALL_FAILED_NO_MATCHING_ABIS")) return "The app doesn't support this processor.";
        if (code.equals("INSTALL_FAILED_DUPLICATE_PERMISSION")) return "Another app already defines a permission that this app uses.";
        if (code.equals("INSTALL_FAILED_CONFLICTING_PROVIDER")) return "Another app already uses a name that this app needs.";
        if (code.equals("INSTALL_FAILED_INVALID_APK") || code.startsWith("INSTALL_PARSE_FAILED")) {
            return "The file isn't a valid app.";
        }
        if (code.equals("INSTALL_FAILED_ABORTED")) return "The install stopped.";
        if (code.equals("DELETE_FAILED_INTERNAL_ERROR") || code.startsWith("not installed")) {
            return "The app isn't installed.";
        }
        if (code.equals("DELETE_FAILED_DEVICE_POLICY_MANAGER")) return "The app is a device administrator. Wave can't remove a device administrator.";
        return "Android refused the request: " + whole;
    }
}
