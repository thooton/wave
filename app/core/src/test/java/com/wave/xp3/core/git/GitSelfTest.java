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
        check.fails("size cap", "8 MiB", () -> Pack.parse(new byte[Pack.MAX_PACK + 1]));
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
            check.fails("do not follow redirects", "redirects are refused", () -> new SmartHttp("https://fixture.invalid/redirect", connection).lsRefs());
            check.fails("reject plain HTTP", "HTTPS", () -> new SmartHttp("http://fixture.invalid/fixture", connection));
            check.same("display never shows credentials", "https://fixture.invalid/fixture", SmartHttp.publicUrl("https://user:tok%2Ben@fixture.invalid/fixture/"));
        } finally { server.stop(0); }
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
