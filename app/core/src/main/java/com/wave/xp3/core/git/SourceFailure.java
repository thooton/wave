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
        NOT_FOUND("No Wave source at that address."),
        SIGN_IN("That source needs a sign-in."),
        REDIRECT("That address sends Wave elsewhere. Use the address it ends at."),
        SERVER("The source's server had a problem. Try again later."),
        UNSUPPORTED("That source's server doesn't work with Wave."),
        EMPTY("That source is empty."),
        UNREADABLE("That source sent something Wave can't read."),
        TOO_BIG("That source is too big for Wave."),
        TIMEOUT("The connection timed out. Try again."),
        OFFLINE("Couldn't reach the source. Check the connection."),
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
        super(kind.says, cause);
        this.kind = kind;
        this.detail = detail == null ? "" : detail;
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
