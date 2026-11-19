package com.wave.xp3.core.git;

import com.sun.net.httpserver.HttpServer;
import com.xp3.common.Check;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class GitSelfTest {
    public static void run(Check check) {
        check.run("Git packet lines", () -> packets(check));
        check.run("Git packs", () -> packs(check));
        check.run("Git HTTP", () -> http(check));
        check.run("Source failures in plain words", () -> plainFailures(check));
        check.run("The Git client throws only SourceFailure", () -> onlySourceFailures(check));
    }

    private static void packets(Check check) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PktLine.write(out, new byte[] {0, -1, 10});
        PktLine.control(out, PktLine.DELIMITER);
        PktLine.write(out, "");
        PktLine.control(out, PktLine.FLUSH);
        PktLine.control(out, PktLine.RESPONSE_END);
        InputStream in = new ByteArrayInputStream(out.toByteArray());
        check.ok("binary packet", Arrays.equals(new byte[] {0, -1, 10}, PktLine.read(in).bytes));
        check.same("delimiter", PktLine.DELIMITER, PktLine.read(in).kind);
        check.same("empty data distinct from flush", PktLine.DATA, PktLine.read(in).kind);
        check.same("flush", PktLine.FLUSH, PktLine.read(in).kind);
        check.same("response end", PktLine.RESPONSE_END, PktLine.read(in).kind);
        check.same("clean end", null, PktLine.read(in));
        check.fails("write length cap", () -> PktLine.write(out, new byte[PktLine.MAX_PAYLOAD + 1]));
        for (String input : new String[] {"0", "0003", "zzzz", "ffff", "0006x"}) {
            check.fails("invalid " + input, () -> PktLine.read(new ByteArrayInputStream(input.getBytes(StandardCharsets.US_ASCII))));
        }
    }

    private static void packs(Check check) throws Exception {
        String head = head();
        for (String name : new String[] {"plain", "ofs-delta", "ref-delta", "filtered"}) {
            Pack pack = Pack.parse(fixture(name + ".pack"));
            Map<String, byte[]> tree = Tree.files(pack, head);
            check.same("source paths in " + name, 6, tree.size());
            check.ok("package path in " + name, tree.containsKey("applications/list/package.json"));
            check.ok("module path in " + name, tree.containsKey("system/messages-improved/icon.png"));
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update("blob 300000\0".getBytes(StandardCharsets.US_ASCII));
            String largeId = Pack.hex(digest.digest(new byte[300_000]), 0, 20);
            check.same("large blob presence in " + name, !"filtered".equals(name), pack.blob(largeId, 300_000) != null);
        }
        byte[] good = fixture("plain.pack");
        check.fails("wrong HEAD", "HEAD commit", () -> Tree.files(Pack.parse(good), head.replaceFirst("^..", "ff")));
        byte[] corrupt = good.clone();
        int zlib = 12;
        while ((corrupt[zlib++] & 128) != 0) { /* skip the entry header */ }
        corrupt[zlib] = 0;
        check.fails("corrupt trailer", "checksum", () -> Pack.parse(corrupt));
        // Re-sign so the zlib parser, not just the trailer check, must reject the corruption.
        MessageDigest sha = MessageDigest.getInstance("SHA-1");
        sha.update(corrupt, 0, corrupt.length - 20);
        System.arraycopy(sha.digest(), 0, corrupt, corrupt.length - 20, 20);
        check.fails("corrupt compressed object", "zlib", () -> Pack.parse(corrupt));
        check.fails("truncated data", () -> Pack.parse(Arrays.copyOf(good, good.length - 1)));
        check.fails("truncated header", () -> Pack.parse(Arrays.copyOf(good, 10)));
        check.fails("size cap", "too big", () -> Pack.parse(new byte[Pack.MAX_PACK + 1]));
    }

    private static void http(Check check) throws Exception {
        byte[] advertisement = fixture("advertisement.pkt");
        byte[] ls = fixture("ls-refs.pkt");
        byte[] fetch = fixture("fetch.pkt");
        AtomicInteger ads = new AtomicInteger(), refs = new AtomicInteger(), packs = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/fixture", exchange -> {
            try {
                byte[] body = read(exchange.getRequestBody());
                String request = new String(body, StandardCharsets.US_ASCII);
                if (!"version=2".equals(exchange.getRequestHeaders().getFirst("Git-Protocol")) ||
                    !"Basic dXNlcjp0b2srZW4=".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    exchange.sendResponseHeaders(403, -1);
                    return;
                }
                byte[] reply;
                String suffix;
                if ("GET".equals(exchange.getRequestMethod()) &&
                    "/fixture/info/refs?service=git-upload-pack".equals(exchange.getRequestURI().toString())) {
                    ads.incrementAndGet();
                    suffix = "advertisement";
                    reply = advertisement;
                } else if ("POST".equals(exchange.getRequestMethod()) &&
                           "/fixture/git-upload-pack".equals(exchange.getRequestURI().getPath()) &&
                           "application/x-git-upload-pack-request".equals(exchange.getRequestHeaders().getFirst("Content-Type"))) {
                    suffix = "result";
                    if (request.contains("command=ls-refs\n") && request.contains("ref-prefix HEAD\n")) {
                        refs.incrementAndGet();
                        reply = ls;
                    } else if (request.contains("command=fetch\n") && request.contains("want " + head() + "\n") &&
                               request.contains("deepen 1\n") && request.contains("filter blob:limit=262144\n") &&
                               request.contains("no-progress\n") && request.contains("done\n") && !request.contains("thin-pack")) {
                        packs.incrementAndGet();
                        reply = fetch;
                    } else { exchange.sendResponseHeaders(400, -1); return; }
                } else { exchange.sendResponseHeaders(404, -1); return; }
                exchange.getResponseHeaders().set("Content-Type", "application/x-git-upload-pack-" + suffix);
                exchange.sendResponseHeaders(200, reply.length);
                exchange.getResponseBody().write(reply);
            } finally { exchange.close(); }
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "/fixture/info/refs?service=git-upload-pack");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        try {
            SmartHttp.Connections connection = uri -> (HttpURLConnection) URI.create("http://127.0.0.1:" +
                server.getAddress().getPort() + uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())).toURL().openConnection();
            SmartHttp git = new SmartHttp("https://user:tok%2Ben@fixture.invalid/fixture/", connection);
            SmartHttp.Result result = git.fetchTree(null);
            check.same("fetched HEAD", head(), result.commit);
            check.same("fetched source", 6, result.files.size());
            check.ok("known HEAD", git.fetchTree(result.commit).unchanged);
            check.same("only one advertisement", 1, ads.get());
            check.same("only one pack", 1, packs.get());
            check.same("one HEAD request per refresh", 2, refs.get());
            check.fails("do not follow redirects", "sends Wave elsewhere", () -> new SmartHttp("https://fixture.invalid/redirect", connection).lsRefs());
            check.fails("reject plain HTTP", "https://", () -> new SmartHttp("http://fixture.invalid/fixture", connection));
            check.same("display never shows credentials", "https://fixture.invalid/fixture", SmartHttp.publicUrl("https://user:tok%2Ben@fixture.invalid/fixture/"));
        } finally { server.stop(0); }
    }

    /**
     * Every way a fetch can fail reaches the app as a SourceFailure whose
     * message is shown as it is: this fails when a failure escapes untyped
     * or its words are Git's.
     */
    private static void plainFailures(Check check) throws Exception {
        for (SourceFailure.Kind kind : SourceFailure.Kind.values()) {
            check.ok(kind + " is plain: " + kind.says, !com.wave.xp3.core.sources.SourcesSelfTest.JARGON.matcher(kind.says).find()
                && kind.says.length() <= 70 && kind.says.endsWith("."));
        }
        byte[] advertisement = fixture("advertisement.pkt");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath();
                boolean advertise = "GET".equals(exchange.getRequestMethod());
                if (path.startsWith("/missing/")) exchange.sendResponseHeaders(404, -1);
                else if (path.startsWith("/locked/")) exchange.sendResponseHeaders(401, -1);
                else if (path.startsWith("/forbidden/")) exchange.sendResponseHeaders(403, -1);
                else if (path.startsWith("/broken/")) exchange.sendResponseHeaders(503, -1);
                else if (path.startsWith("/moved/")) {
                    exchange.getResponseHeaders().set("Location", "https://elsewhere.invalid/");
                    exchange.sendResponseHeaders(301, -1);
                } else if (path.startsWith("/page/")) {
                    byte[] page = "<html>hello</html>".getBytes(StandardCharsets.US_ASCII);
                    exchange.getResponseHeaders().set("Content-Type", "text/html");
                    exchange.sendResponseHeaders(200, page.length);
                    exchange.getResponseBody().write(page);
                } else {
                    byte[] reply = advertise ? advertisement : "0011not a packet!".getBytes(StandardCharsets.US_ASCII);
                    exchange.getResponseHeaders().set("Content-Type", "application/x-git-upload-pack-" + (advertise ? "advertisement" : "result"));
                    if (path.startsWith("/huge/") && !advertise) {
                        exchange.sendResponseHeaders(200, Pack.MAX_PACK + 1L);
                        return;
                    }
                    exchange.sendResponseHeaders(200, reply.length);
                    exchange.getResponseBody().write(reply);
                }
            } catch (IOException ignored) {
            } finally { exchange.close(); }
        });
        server.start();
        int closed;
        try (java.net.ServerSocket free = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) { closed = free.getLocalPort(); }
        try {
            int port = server.getAddress().getPort();
            Object[][] cases = {
                {"missing", SourceFailure.Kind.NOT_FOUND, port}, {"locked", SourceFailure.Kind.SIGN_IN, port},
                {"forbidden", SourceFailure.Kind.SIGN_IN, port}, {"broken", SourceFailure.Kind.SERVER, port},
                {"moved", SourceFailure.Kind.REDIRECT, port}, {"page", SourceFailure.Kind.NOT_FOUND, port},
                {"garbage", SourceFailure.Kind.UNREADABLE, port}, {"huge", SourceFailure.Kind.TOO_BIG, port},
                {"nobody", SourceFailure.Kind.OFFLINE, closed},
            };
            for (Object[] c : cases) {
                final int to = (Integer) c[2];
                SmartHttp.Connections connection = uri -> (HttpURLConnection) URI.create("http://127.0.0.1:" + to + uri.getRawPath() +
                    (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())).toURL().openConnection();
                try {
                    new SmartHttp("https://source.invalid/" + c[0] + "/", connection).fetchTree(null);
                    check.ok(c[0] + " is refused", false);
                } catch (SourceFailure e) {
                    check.same(c[0] + " (" + e.detail() + ")", c[1], e.kind);
                    check.same(c[0] + " says it plainly", e.kind.says, e.getMessage());
                }
            }
            try {
                SmartHttp.fetchTree("http://source.invalid/plain", null);
                check.ok("plain HTTP is refused", false);
            } catch (SourceFailure e) {
                check.same("plain HTTP", SourceFailure.Kind.ADDRESS, e.kind);
            }
        } finally { server.stop(0); }
    }

    /** Whatever leaves the package can only fail in the screen's words: no public method declares a bare IOException. */
    private static void onlySourceFailures(Check check) {
        Class<?>[] all = {SmartHttp.class, SmartHttp.Result.class, Tree.class, Pack.class, PktLine.class, SourceFailure.class};
        for (Class<?> type : all) {
            if (!java.lang.reflect.Modifier.isPublic(type.getModifiers())) continue;
            java.util.List<java.lang.reflect.Executable> exits = new java.util.ArrayList<>();
            exits.addAll(Arrays.asList(type.getDeclaredMethods()));
            exits.addAll(Arrays.asList(type.getDeclaredConstructors()));
            for (java.lang.reflect.Executable exit : exits) {
                if (!java.lang.reflect.Modifier.isPublic(exit.getModifiers())) continue;
                for (Class<?> thrown : exit.getExceptionTypes()) {
                    boolean checked = Exception.class.isAssignableFrom(thrown) && !RuntimeException.class.isAssignableFrom(thrown);
                    check.ok(type.getSimpleName() + "." + exit.getName() + " throws " + thrown.getSimpleName(),
                        !checked || SourceFailure.class.isAssignableFrom(thrown));
                }
            }
        }
        check.ok("Pack is internal", !java.lang.reflect.Modifier.isPublic(Pack.class.getModifiers()));
        check.ok("PktLine is internal", !java.lang.reflect.Modifier.isPublic(PktLine.class.getModifiers()));
    }

    private static String head() throws IOException { return new String(fixture("head.txt"), StandardCharsets.US_ASCII).trim(); }

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = GitSelfTest.class.getResourceAsStream("/git/" + name)) {
            if (in == null) throw new IOException("Missing Git fixture " + name);
            return read(in);
        }
    }

    private static byte[] read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        for (int n; (n = in.read(buf)) != -1;) out.write(buf, 0, n);
        return out.toByteArray();
    }

    /** Manual integration probe; only loopback can use the test-only HTTP transport. */
    public static void main(String[] args) throws Exception {
        long start = System.nanoTime();
        String url = args[0];
        SmartHttp git = url.startsWith("http://127.0.0.1:") ?
            new SmartHttp(url.replaceFirst("^http:", "https:"), uri ->
                (HttpURLConnection) URI.create(uri.toString().replaceFirst("^https:", "http:")).toURL().openConnection()) :
            new SmartHttp(url);
        SmartHttp.Result result = git.fetchTree(null);
        System.out.printf("HEAD %s, %d files, %.3f s%n", result.commit, result.files.size(), (System.nanoTime() - start) / 1e9);
        if (!git.fetchTree(result.commit).unchanged) throw new AssertionError("Expected unchanged on second fetch");
    }
}
