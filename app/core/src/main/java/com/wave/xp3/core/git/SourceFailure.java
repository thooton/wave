package com.wave.xp3.core.git;

import java.io.IOException;

/**
 * Why a source couldn't be reached or read, in words for the person holding
 * the phone: {@link #getMessage()} is shown as it is. What went wrong in
 * Git's or the source format's terms is kept in {@link #detail()} (and the
 * cause) for logs and tests, never on screen.
 *
 * Every failure that leaves {@link SmartHttp#fetchTree} or the source store's
 * fetch, preview and refresh is one of these, so the app never has to read
 * or translate an exception's text.
 */
public final class SourceFailure extends IOException {

    public enum Kind {
        ADDRESS("Use an https:// address without ? or #."),
        NOT_FOUND("Wave found no source at that address."),
        SIGN_IN("That source needs a sign-in."),
        REDIRECT("That address sends Wave to a different address. Use the final address."),
        SERVER("The source's server had a problem. Try again later."),
        UNSUPPORTED("That source's server doesn't work with Wave."),
        EMPTY("That source is empty."),
        UNREADABLE("That source sent data that Wave can't read."),
        /** Over what Wave fetches at once: it reads the whole latest commit, whatever else the repository holds. */
        TOO_BIG("Wave fetches 8 MB at most. Keep only the source at that address."),
        /** Within the fetch, but more package files and icons than Wave keeps. */
        TOO_MANY("Wave reads 12,000 files and 16 MB at most. That source has more."),
        TIMEOUT("The source didn't answer in time. Try again."),
        OFFLINE("Wave couldn't connect to the source. Check the network connection."),
        INSECURE("The secure connection to the source failed."),
        STOPPED("Stopped.");

        public final String says;

        Kind(String says) { this.says = says; }
    }

    public final Kind kind;
    private final String detail;

    public SourceFailure(Kind kind, String detail) {
        this(kind, detail, null);
    }

    public SourceFailure(Kind kind, String detail, Throwable cause) {
        this(kind, kind.says, detail, cause);
    }

    private SourceFailure(Kind kind, String says, String detail, Throwable cause) {
        super(says, cause);
        this.kind = kind;
        this.detail = detail == null ? "" : detail;
    }

    /**
     * A failure of the kind whose screen text is more specific than the kind's
     * own, such as which field of the source's details is wrong. The text must
     * already be plain words.
     */
    public static SourceFailure saying(Kind kind, String says, String detail, Throwable cause) {
        return new SourceFailure(kind, says, detail, cause);
    }

    /** A reply other than 200 from the source's server. */
    public static SourceFailure status(int status) {
        Kind kind = status == 404 || status == 410 ? Kind.NOT_FOUND
            : status == 401 || status == 403 ? Kind.SIGN_IN
            : status >= 300 && status < 400 ? Kind.REDIRECT
            : Kind.SERVER;
        return new SourceFailure(kind, "HTTP " + status);
    }

    /** Anything else that went wrong reading the source becomes UNREADABLE, keeping its own words as the detail. */
    public static SourceFailure of(Exception e) {
        if (e instanceof SourceFailure) return (SourceFailure) e;
        return new SourceFailure(Kind.UNREADABLE, e.getMessage(), e);
    }

    /** What went wrong in Git's or the format's terms. Not for the screen. */
    public String detail() { return detail; }

    @Override
    public String toString() {
        return "SourceFailure " + kind + (detail.isEmpty() ? "" : ": " + detail);
    }
}
