package com.wave.xp3.core.git;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;

/**
 * Fetch-only Git protocol v2 on stateless smart HTTPS. No redirects or fallback.
 *
 * The Git client's only way out of this package is {@link #fetchTree(String, String)}
 * and the address helpers, and all they throw is {@link SourceFailure}, whose message
 * is written for the screen. lsRefs, fetch, Pack, Tree.files and PktLine stay
 * package-private so no caller can meet Git's own errors (GitSelfTest checks this).
 */
public final class SmartHttp {
    private static final String SERVICE = "git-upload-pack";
    private static final String MIME = "application/x-" + SERVICE;
    private static final long DEADLINE = TimeUnit.SECONDS.toNanos(45);
    private final URI endpoint;
    private final String authorization;
    private final Connections connections;
    private Map<String, String> capabilities;

    interface Connections { HttpURLConnection open(URI uri) throws IOException; }
    private interface Read<T> { T from(InputStream in) throws IOException; }

    SmartHttp(String url) throws IOException {
        this(url, uri -> (HttpURLConnection) uri.toURL().openConnection());
    }

    // Host tests can route a real request to a loopback CGI without weakening production HTTPS validation.
    SmartHttp(String url, Connections connections) throws IOException {
        URI parsed = repositoryUri(url);
        endpoint = URI.create(publicUrl(url));
        String userInfo = parsed.getUserInfo();
        authorization = userInfo == null ? null : "Basic " + Base64.getEncoder().encodeToString(
            (userInfo.indexOf(':') < 0 ? userInfo + ':' : userInfo).getBytes(StandardCharsets.UTF_8));
        this.connections = connections;
    }

    public static URI repositoryUri(String url) throws SourceFailure {
        try {
            URI uri = new URI(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getPort() > 65535 ||
                uri.getRawQuery() != null || uri.getRawFragment() != null) throw new URISyntaxException("", "Bad source URL");
            return uri;
        } catch (URISyntaxException e) {
            throw new SourceFailure(SourceFailure.Kind.ADDRESS, "Not an HTTPS repository URL without a query or fragment");
        }
    }

    /** Safe to display and use as an identity; userinfo and redundant trailing slashes are removed. */
    public static String publicUrl(String url) throws SourceFailure {
        URI uri = repositoryUri(url);
        String authority = uri.getRawAuthority();
        String path = uri.getRawPath();
        int end = path.length();
        while (end > 0 && path.charAt(end - 1) == '/') end--;
        return "https://" + authority.substring(authority.lastIndexOf('@') + 1) + path.substring(0, end);
    }

    public static final class Result {
        public final String commit;
        public final boolean unchanged;
        public final Map<String, byte[]> files;

        private Result(String commit, Map<String, byte[]> files) {
            this.commit = commit;
            unchanged = files == null;
            this.files = unchanged ? java.util.Collections.emptyMap() : files;
        }
    }

    public static Result fetchTree(String url, String knownCommit) throws SourceFailure {
        SmartHttp git;
        try { git = new SmartHttp(url); }
        catch (IOException e) { throw SourceFailure.of(e); }
        return git.fetchTree(knownCommit);
    }

    /** Every failure is a {@link SourceFailure}: its message is for the screen as it is. */
    Result fetchTree(String knownCommit) throws SourceFailure {
        try {
            String id = lsRefs();
            return new Result(id, id.equals(knownCommit) ? null : Tree.files(fetch(id), id));
        } catch (IOException | RuntimeException e) {
            throw SourceFailure.of(e);
        }
    }

    String lsRefs() throws IOException {
        advertise();
        return request("/" + SERVICE, command("ls-refs", "ref-prefix HEAD\n"), MIME + "-result", in -> {
            String head = null;
            for (;;) {
                PktLine line = packet(in);
                if (line.kind == PktLine.FLUSH) {
                    finish(in);
                    if (head == null) throw new SourceFailure(SourceFailure.Kind.EMPTY, "No default branch commit");
                    return head;
                }
                String[] parts = text(line).split(" ", 3);
                if (parts.length >= 2 && "HEAD".equals(parts[1])) {
                    if (head != null || !Pack.isId(parts[0])) throw new IOException("Invalid Git HEAD commit id");
                    head = parts[0];
                }
            }
        });
    }

    Pack fetch(String commit) throws IOException {
        if (commit == null || !Pack.isId(commit)) throw new IOException("Invalid Git commit id");
        advertise();
        if (!offers("shallow")) throw new SourceFailure(SourceFailure.Kind.UNSUPPORTED, "No shallow fetches");
        return request("/" + SERVICE, command("fetch", "want " + commit + '\n', "deepen 1\n",
            offers("filter") ? "filter blob:limit=262144\n" : null, "no-progress\n", "ofs-delta\n", "done\n"),
            MIME + "-result", SmartHttp::sideband).verify();
    }

    private boolean offers(String feature) {
        for (String value : capabilities.get("fetch").split(" ")) if (feature.equals(value)) return true;
        return false;
    }

    private void advertise() throws IOException {
        if (capabilities != null) return;
        capabilities = request("/info/refs?service=" + SERVICE, null, MIME + "-advertisement", in -> {
            String first = text(packet(in));
            // Git's CGI may omit this prelude for v2, while several hosting services send it.
            if (("# service=" + SERVICE).equals(first)) {
                if (packet(in).kind != PktLine.FLUSH) throw new IOException("Invalid Git service advertisement");
                first = text(packet(in));
            }
            if (!"version 2".equals(first)) throw new SourceFailure(SourceFailure.Kind.UNSUPPORTED, "Not Git protocol v2");
            Map<String, String> caps = new HashMap<>();
            for (;;) {
                PktLine line = packet(in);
                if (line.kind == PktLine.FLUSH) break;
                String[] parts = text(line).split("=", 2);
                if ("fetch".equals(parts[0]) || "ls-refs".equals(parts[0]) || "object-format".equals(parts[0])) {
                    if (caps.put(parts[0], parts.length == 1 ? "" : parts[1]) != null) {
                        throw new IOException("Duplicate Git capability");
                    }
                }
            }
            finish(in);
            if (caps.containsKey("object-format") && !"sha1".equals(caps.get("object-format"))) {
                throw new SourceFailure(SourceFailure.Kind.UNSUPPORTED, "Not SHA-1 objects");
            }
            if (!caps.containsKey("fetch") || !caps.containsKey("ls-refs")) {
                throw new SourceFailure(SourceFailure.Kind.UNSUPPORTED, "No fetch and ls-refs");
            }
            return caps;
        });
    }

    private static byte[] command(String command, String... args) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PktLine.write(out, "command=" + command + '\n');
        PktLine.control(out, PktLine.DELIMITER);
        for (String arg : args) if (arg != null) PktLine.write(out, arg);
        PktLine.control(out, PktLine.FLUSH);
        return out.toByteArray();
    }

    private static PackBytes sideband(InputStream in) throws IOException {
        for (;;) {
            String section = text(packet(in));
            if ("packfile".equals(section)) break;
            if (!"shallow-info".equals(section) && !"acknowledgments".equals(section)) {
                throw new IOException("Unexpected Git fetch response section");
            }
            for (;;) {
                PktLine line = packet(in);
                if (line.kind == PktLine.DELIMITER) break;
                text(line);
            }
        }
        PackBytes pack = new PackBytes();
        for (;;) {
            PktLine line = packet(in);
            if (line.kind == PktLine.FLUSH) break;
            if (line.kind != PktLine.DATA || line.bytes.length == 0) throw new IOException("Invalid Git sideband packet");
            int band = line.bytes[0];
            if (band == 1) {
                if (line.bytes.length - 1 > Pack.MAX_PACK - pack.size()) throw new SourceFailure(SourceFailure.Kind.TOO_BIG, "Git pack exceeds 8 MiB");
                pack.write(line.bytes, 1, line.bytes.length - 1);
            } else if (band != 2) {
                // Never surface server-controlled text; it can include private repository information.
                throw new IOException(band == 3 ? "The Git server could not send the pack" : "Invalid Git sideband channel");
            }
        }
        finish(in);
        return pack;
    }

    private static final class PackBytes extends ByteArrayOutputStream {
        Pack verify() throws IOException { return Pack.parse(buf, count); }
    }

    private static PktLine packet(InputStream in) throws IOException {
        PktLine line = PktLine.read(in);
        if (line == null) throw new IOException("Truncated Git reply");
        return line;
    }

    private static void finish(InputStream in) throws IOException {
        PktLine tail = PktLine.read(in);
        if (tail != null && (tail.kind != PktLine.RESPONSE_END || in.read() != -1)) {
            throw new IOException("Unexpected data after Git reply");
        }
    }

    private static String text(PktLine line) throws IOException {
        if (line.kind != PktLine.DATA) throw new IOException("Unexpected Git control packet");
        String text = line.text();
        if (text.startsWith("ERR ")) throw new IOException("The Git server rejected the request");
        return text;
    }

    private <T> T request(String suffix, byte[] body, String expected, Read<T> read) throws IOException {
        long deadline = System.nanoTime() + DEADLINE;
        checkDeadline(deadline);
        HttpURLConnection connection = connections.open(URI.create(endpoint.toString() + suffix));
        try {
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Git-Protocol", "version=2");
            connection.setRequestProperty("Cache-Control", "no-cache");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("Accept", expected);
            if (authorization != null) connection.setRequestProperty("Authorization", authorization);
            if (body != null) {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", MIME + "-request");
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = connection.getOutputStream()) { out.write(body); }
            }
            int status = connection.getResponseCode();
            if (status != 200) {
                throw SourceFailure.status(status);
            }
            String type = connection.getContentType();
            if (type == null || !expected.equalsIgnoreCase(type.split(";", 2)[0].trim())) {
                throw new SourceFailure(SourceFailure.Kind.NOT_FOUND, "Not a smart Git repository (" + type + ")");
            }
            String encoding = connection.getContentEncoding();
            if (encoding != null && !"identity".equalsIgnoreCase(encoding)) throw new IOException("Unsupported Git HTTP encoding");
            if (connection.getContentLengthLong() > Pack.MAX_PACK) throw new SourceFailure(SourceFailure.Kind.TOO_BIG, "Git reply exceeds 8 MiB");
            try (InputStream in = new Bounded(new BufferedInputStream(connection.getInputStream()), deadline)) {
                return read.from(in);
            }
        } catch (SocketTimeoutException e) {
            throw new SourceFailure(SourceFailure.Kind.TIMEOUT, "Git request timed out", e);
        } catch (UnknownHostException | java.net.SocketException e) {
            throw new SourceFailure(SourceFailure.Kind.OFFLINE, String.valueOf(e), e);
        } catch (SSLException e) {
            throw new SourceFailure(SourceFailure.Kind.INSECURE, String.valueOf(e), e);
        } finally {
            connection.disconnect();
        }
    }

    private static void checkDeadline(long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new SourceFailure(SourceFailure.Kind.STOPPED, "Git fetch cancelled");
        if (System.nanoTime() - deadline >= 0) throw new SourceFailure(SourceFailure.Kind.TIMEOUT, "Git request timed out");
    }

    private static final class Bounded extends FilterInputStream {
        final long deadline;
        int left = Pack.MAX_PACK;

        Bounded(InputStream in, long deadline) { super(in); this.deadline = deadline; }

        @Override public int read() throws IOException {
            checkDeadline(deadline);
            int b = in.read();
            spent(b < 0 ? 0 : 1);
            return b;
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            checkDeadline(deadline);
            int n = in.read(buffer, offset, Math.min(left + 1, length));
            spent(Math.max(n, 0));
            return n;
        }

        private void spent(int n) throws IOException {
            if ((left -= n) < 0) throw new SourceFailure(SourceFailure.Kind.TOO_BIG, "Git reply exceeds 8 MiB");
            checkDeadline(deadline);
        }
    }
}
