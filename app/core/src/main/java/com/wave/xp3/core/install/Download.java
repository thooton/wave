package com.wave.xp3.core.install;

import com.wave.xp3.core.Releases;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Fetches a package's binary into the cache, as the Updater's
 * {@link Releases#download} does, for any `https://` URL a manifest names:
 * through `<file>.part`, with progress, checked against the manifest's size
 * and SHA-256, and resumable. A download cut off by the network keeps its
 * `.part`, and the next try asks only for the rest (`Range`); every other
 * failure (wrong size or hash, no space, stopped) deletes it, so nothing is
 * left behind. The engine deletes any `.part` still there when its run ends.
 *
 * A private source's packages come with its {@link Credential}, which is sent
 * only to the addresses it covers; redirects are followed here, one at a time,
 * so each hop is judged by itself (a CDN's signed address never gets it), and
 * none may leave https.
 */
public final class Download {

    private Download() {}

    public interface Progress {
        void update(long done, long total);
    }

    public interface Cancel {
        boolean cancelled();
    }

    /** Thrown when the user stopped it. */
    public static final class Stopped extends IOException {
        public Stopped() {
            super("Stopped.");
        }
    }

    private static final int CONNECT_TIMEOUT_MS = 20000;
    private static final int READ_TIMEOUT_MS = 60000;
    /** Bigger than any package should be: a module of whole APKs, with room. */
    static final long MAX = 512L * 1024 * 1024;
    /** Kept free beyond the download itself, so installing has room too. */
    static final long SPARE = 4L * 1024 * 1024;
    /** Tries per download while each one gets further. */
    static final int ATTEMPTS = 5;
    /** Wait before try n+1: n times this. Public only for the tests to shorten. */
    public static volatile long backoffMs = 2000;

    private static void pause(int attempt, Cancel cancel) throws IOException {
        long until = System.currentTimeMillis() + backoffMs * attempt;
        while (System.currentTimeMillis() < until) {
            if (cancel != null && cancel.cancelled()) throw new Stopped();
            try {
                Thread.sleep(Math.min(200, Math.max(1, until - System.currentTimeMillis())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Stopped();
            }
        }
    }

    /** Redirects followed per request, as HttpURLConnection would. */
    static final int REDIRECTS = 5;

    public static void fetch(String url, long size, String sha256, File to, Progress progress, Cancel cancel)
            throws IOException {
        fetch(url, size, sha256, to, progress, cancel, null);
    }

    /** As above, signing in with `credential` (null for none) where it applies. */
    public static void fetch(String url, long size, String sha256, File to, Progress progress, Cancel cancel,
            Credential credential) throws IOException {
        checkUrl(url);
        if (size < 0 || size > MAX) throw new IOException("The source gives a download size that isn't valid or is too large.");
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IOException("The source doesn't give a valid SHA-256 checksum.");
        }
        File part = new File(to.getPath() + ".part");
        File dir = to.getAbsoluteFile().getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("Wave can't make the download folder.");
        if (part.length() > size) part.delete();
        long have = part.isFile() ? part.length() : 0;
        if (dir != null && dir.getUsableSpace() < size - have + SPARE) {
            part.delete();
            throw new IOException("Not enough space. The download needs " + megabytes(size + SPARE) + ".");
        }
        try {
            // A connection cut part way resumes from what arrived, as long as
            // each try gets further; a try that gets nothing ends it.
            for (int attempt = 1; ; attempt++) {
                long before = part.isFile() ? part.length() : 0;
                try {
                    if (!get(url, size, part, before, progress, cancel, credential)) {
                        // The server ignored or refused the range: start again.
                        part.delete();
                        get(url, size, part, 0, progress, cancel, credential);
                    }
                    break;
                } catch (Discard | Stopped e) {
                    throw e;
                } catch (IOException e) {
                    long now = part.isFile() ? part.length() : 0;
                    if (outOfSpace(e) || now <= before || attempt >= ATTEMPTS) throw e;
                    pause(attempt, cancel);
                }
            }
            if (part.length() != size) throw new Discard("The download size is different from the size that the source gives.");
            if (!sha256.equals(sha256(part))) throw new Discard("The download checksum is different from the checksum that the source gives.");
            if (to.exists() && !to.delete()) throw new Discard("Wave can't replace the old download.");
            if (!part.renameTo(to)) throw new Discard("Wave can't save the download.");
        } catch (Discard | Stopped e) {
            part.delete();
            throw e;
        } catch (IOException e) {
            if (outOfSpace(e)) {
                part.delete();
                throw new IOException("Not enough space.");
            }
            throw Releases.friendly(e, "The download stopped before it was complete.");
        }
    }

    /** A failure after which the `.part` is worthless. */
    private static final class Discard extends IOException {
        Discard(String message) {
            super(message);
        }
    }

    /**
     * One request, appending to `part` from `from`. False when a range was
     * asked for and the answer wasn't the rest of the file.
     */
    private static boolean get(String url, long size, File part, long from, Progress progress, Cancel cancel,
            Credential credential) throws IOException {
        if (from > 0 && from == size) return true;
        HttpURLConnection c = null;
        boolean signedIn = false;
        String at = url;
        int status;
        for (int hop = 0; ; hop++) {
            c = open(at);
            if (from > 0) c.setRequestProperty("Range", "bytes=" + from + "-");
            String[] headers = credential == null ? null : credential.headersFor(at);
            signedIn = headers != null;
            if (headers != null) {
                for (int i = 0; i < headers.length; i += 2) c.setRequestProperty(headers[i], headers[i + 1]);
            }
            try {
                status = c.getResponseCode();
            } catch (IOException e) {
                c.disconnect();
                throw e;
            }
            if (!redirect(status)) break;
            String location = c.getHeaderField("Location");
            c.disconnect();
            if (location == null || hop >= REDIRECTS) throw new Discard("Wave can't download the file. The source sent too many redirects.");
            try {
                at = new java.net.URI(at).resolve(location.trim()).toString();
            } catch (java.net.URISyntaxException | IllegalArgumentException e) {
                throw new Discard("Wave can't download the file. The source sent a redirect that isn't valid.");
            }
            try {
                checkUrl(at);
            } catch (IOException e) {
                throw new Discard("Wave can't download the file. The source sent a redirect to an address without https.");
            }
        }
        try {
            if (status >= 500) {
                // The server's trouble, not the file's: keep what arrived.
                throw new IOException("The server sent status " + status + ". Try again later.");
            }
            if (status == 401 || status == 403) throw new Discard(signInProblem(credential, signedIn));
            if (from > 0) {
                if (status == 416 || status == 200) return false;
                if (status != 206 || !rangeStartsAt(c.getHeaderField("Content-Range"), from)) return false;
            } else if (status != 200) {
                throw new Discard(status == 404 ? "The file is missing from the source." : "The server sent status " + status + ".");
            }
            long length = c.getContentLengthLong();
            if (length >= 0 && from + length != size) {
                throw new Discard("The download size is different from the size that the source gives.");
            }
            long done = from;
            InputStream in = c.getInputStream();
            try {
                OutputStream out = new FileOutputStream(part, from > 0);
                try {
                    byte[] buffer = new byte[64 * 1024];
                    int n;
                    if (progress != null) progress.update(done, size);
                    while ((n = in.read(buffer)) != -1) {
                        if (cancel != null && cancel.cancelled()) throw new Stopped();
                        done += n;
                        if (done > size) throw new Discard("The download size is different from the size that the source gives.");
                        out.write(buffer, 0, n);
                        if (progress != null) progress.update(done, size);
                    }
                } finally {
                    out.close();
                }
            } finally {
                in.close();
            }
            if (done != size) throw new IOException("The download stopped before it was complete.");
            return true;
        } finally {
            c.disconnect();
        }
    }

    static boolean rangeStartsAt(String contentRange, long from) {
        if (contentRange == null) return false;
        String v = contentRange.trim().toLowerCase(Locale.US);
        return v.startsWith("bytes " + from + "-");
    }

    private static boolean redirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    /** What a refused download says, by whether a sign-in went with it. */
    static String signInProblem(Credential credential, boolean signedIn) {
        if (credential == null) {
            return "This source needs a sign-in. Add the source again with a user name and a token in its address.";
        }
        if (signedIn) return "The source didn't accept the sign-in. Check the token in the source address.";
        return "The download needs a sign-in. Wave signs in only to the host of the source.";
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        // Followed in get(), so a sign-in never rides a redirect to another host.
        c.setInstanceFollowRedirects(false);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", "Wave");
        c.setRequestProperty("Accept-Encoding", "identity");
        return c;
    }

    /** Only https, but plain http to this machine for the tests. */
    static void checkUrl(String url) throws IOException {
        if (url == null) throw new IOException("The source doesn't give a download address.");
        String u = url.toLowerCase(Locale.US);
        int scheme = u.indexOf("://");
        if (scheme > 0) {
            int end = scheme + 3;
            while (end < u.length() && "/?#".indexOf(u.charAt(end)) < 0) end++;
            // A sign-in belongs to the phone's copy of the source's address, never in a manifest.
            if (u.substring(scheme + 3, end).indexOf('@') >= 0) {
                throw new IOException("The download address contains a sign-in. Wave doesn't use sign-ins from download addresses.");
            }
        }
        if (u.startsWith("https://")) return;
        if (u.startsWith("http://127.0.0.1:") || u.startsWith("http://localhost:")) return;
        throw new IOException("Wave downloads only over https.");
    }

    static boolean outOfSpace(IOException e) {
        String m = e.getMessage();
        return m != null && (m.contains("ENOSPC") || m.contains("No space left"));
    }

    public static String sha256(File file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 isn't available.");
        }
        InputStream in = new FileInputStream(file);
        try {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
        } finally {
            in.close();
        }
        StringBuilder out = new StringBuilder(64);
        for (byte b : digest.digest()) out.append(String.format("%02x", b & 0xFF));
        return out.toString();
    }

    static String megabytes(long bytes) {
        return ((bytes + 1024 * 1024 - 1) / (1024 * 1024)) + " MB";
    }
}
