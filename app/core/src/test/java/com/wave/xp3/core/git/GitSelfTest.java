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
        check.run("Git source limits and left-out files", () -> limits(check));
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
        check.fails("size cap", "8 MB", () -> Pack.parse(new byte[Pack.MAX_PACK + 1]));
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
            check.fails("do not follow redirects", "sends Wave to a different address", () -> new SmartHttp("https://fixture.invalid/redirect", connection).lsRefs());
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

    /** A pack built here: whole objects only, enough for Tree's walk. */
    private static final class Builder {
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private int count;

        String blob(byte[] data) throws Exception { return add(3, "blob", data); }

        /** Entries as mode, name, id; Git sorts them by name, so pass them sorted. */
        String tree(String... entries) throws Exception {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (int i = 0; i < entries.length; i += 3) {
                out.write((entries[i] + " " + entries[i + 1] + "\0").getBytes(StandardCharsets.US_ASCII));
                for (int j = 0; j < 40; j += 2) out.write(Integer.parseInt(entries[i + 2].substring(j, j + 2), 16));
            }
            return add(2, "tree", out.toByteArray());
        }

        String commit(String tree) throws Exception {
            return add(1, "commit", ("tree " + tree + "\nauthor t <t@t> 0 +0000\ncommitter t <t@t> 0 +0000\n\nx\n")
                .getBytes(StandardCharsets.US_ASCII));
        }

        private String add(int type, String name, byte[] data) throws Exception {
            MessageDigest sha = MessageDigest.getInstance("SHA-1");
            sha.update((name + " " + data.length + "\0").getBytes(StandardCharsets.US_ASCII));
            String id = Pack.hex(sha.digest(data), 0, 20);
            long size = data.length;
            int first = (int) (type << 4 | (size & 15));
            size >>>= 4;
            body.write(size > 0 ? first | 128 : first);
            while (size > 0) {
                int b = (int) (size & 127);
                size >>>= 7;
                body.write(size > 0 ? b | 128 : b);
            }
            java.util.zip.DeflaterOutputStream z = new java.util.zip.DeflaterOutputStream(body);
            z.write(data);
            z.finish();
            count++;
            return id;
        }

        Pack pack() throws Exception { return Pack.parse(bytes(count)); }

        byte[] bytes(long claimed) throws Exception {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write("PACK".getBytes(StandardCharsets.US_ASCII));
            out.write(new byte[] {0, 0, 0, 2});
            out.write(java.nio.ByteBuffer.allocate(4).putInt((int) claimed).array());
            body.writeTo(out);
            out.write(MessageDigest.getInstance("SHA-1").digest(out.toByteArray()));
            return out.toByteArray();
        }
    }

    private static byte[] json(int size) {
        byte[] data = new byte[size];
        Arrays.fill(data, (byte) ' ');
        data[0] = '{';
        data[size - 1] = '}';
        return data;
    }

    private static void limits(Check check) throws Exception {
        // Files the format reads but over 64 KB are left out, and the walk says which; nothing else is noted.
        Builder b = new Builder();
        String small = b.blob("{}".getBytes(StandardCharsets.US_ASCII)), big = b.blob(json(70 * 1024));
        String bigIcon = b.blob(new byte[70 * 1024]);
        String bigFolder = b.tree("100644", "icon.png", bigIcon, "100644", "package.json", small);
        String demo = b.tree("100644", "package.json", big);
        String ok = b.tree("100644", "package.json", small);
        String apps = b.tree("40000", "big", bigFolder, "40000", "demo", demo, "40000", "ok", ok);
        String docs = b.tree("100644", "notes.md", big);
        String other = b.tree("40000", "x", docs);
        String root = b.tree("40000", ".github", other, "40000", "apps", apps, "40000", "docs", other,
            "100644", "README.md", big, "100644", "wave.json", small);
        String head = b.commit(root);
        Map<String, byte[]> files = Tree.files(b.pack(), head);
        check.same("left out: the format's big files only", "apps/big/icon.png\napps/demo/package.json\n",
            new String(files.get(Tree.DROPPED), StandardCharsets.US_ASCII));
        check.ok("the rest kept", files.containsKey("apps/ok/package.json") && files.containsKey("apps/big/package.json")
            && files.containsKey("wave.json") && files.size() == 4);
        check.same("record read back", Arrays.asList("apps/big/icon.png", "apps/demo/package.json"),
            Tree.dropped(files.get(Tree.DROPPED)));

        Builder plain = new Builder();
        String w = plain.blob("{}".getBytes(StandardCharsets.US_ASCII));
        String headPlain = plain.commit(plain.tree("100644", "wave.json", w));
        check.ok("no record when nothing is left out", !Tree.files(plain.pack(), headPlain).containsKey(Tree.DROPPED));

        StringBuilder torn = new StringBuilder("wave.json\n../escape\napps/x/package.json\napps/x/package.json\nnot/a/path\n");
        for (int i = 0; i < 300; i++) torn.append("apps/p").append(i).append("/package.json\n");
        torn.append("apps/").append(new String(new char[300]).replace('\0', 'a')).append("/package.json\n");
        java.util.List<String> read = Tree.dropped(torn.toString().getBytes(StandardCharsets.US_ASCII));
        check.same("a torn record keeps only well-formed paths, at most 200", Tree.MAX_DROPPED, read.size());
        check.ok("and never an escape or a repeat", read.get(0).equals("wave.json") && read.get(1).equals("apps/x/package.json")
            && !read.contains("../escape") && !read.contains("not/a/path"));
        check.ok("garbage is no record", Tree.dropped(new byte[] {0, -1, 10}).isEmpty() && Tree.dropped(null).isEmpty());

        // More package folders than Wave keeps: its own words, not the 8 MB ones.
        Builder many = new Builder();
        String pkg = many.tree("100644", "package.json", many.blob("{}".getBytes(StandardCharsets.US_ASCII)));
        String[] entries = new String[12_001 * 3];
        for (int i = 0; i < 12_001; i++) {
            entries[i * 3] = "40000";
            entries[i * 3 + 1] = String.format("p%05d", i);
            entries[i * 3 + 2] = pkg;
        }
        String section = many.tree(entries);
        String headMany = many.commit(many.tree("40000", "apps", section));
        try {
            Tree.files(many.pack(), headMany);
            check.ok("12,001 packages refused", false);
        } catch (SourceFailure e) {
            check.same("12,001 packages: " + e.detail(), SourceFailure.Kind.TOO_MANY, e.kind);
        }

        // A pack claiming more objects than Wave reads is too big, not unreadable.
        try {
            Pack.parse(new Builder().bytes(64_001));
            check.ok("64,001 objects refused", false);
        } catch (SourceFailure e) {
            check.same("64,001 objects: " + e.detail(), SourceFailure.Kind.TOO_BIG, e.kind);
        }
        try {
            Pack.parse(new Builder().bytes(1000));
            check.ok("a count past the data refused", false);
        } catch (SourceFailure e) {
            check.ok("a count past the data is not too big", e.kind != SourceFailure.Kind.TOO_BIG);
        } catch (IOException e) {
            check.ok("a count past the data is unreadable", true);
        }
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
