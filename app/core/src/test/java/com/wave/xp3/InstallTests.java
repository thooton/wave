package com.wave.xp3;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.wave.xp3.core.install.Download;
import com.wave.xp3.core.install.Engine;
import com.wave.xp3.core.install.Plan;
import com.wave.xp3.core.install.Receipts;
import com.wave.xp3.core.install.Verify;
import com.wave.xp3.core.root.Modules;
import com.wave.xp3.core.root.ProcessShell;
import com.wave.xp3.core.root.Scripts;

import com.xp3.common.Check;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The install engine: planning, downloading, checking, receipts, and whole
 * plans run end to end through a fake `su` on a pretend phone (see Sandbox).
 */
final class InstallTests {

    private InstallTests() {}

    static void run(Check check) {
        check.run("Install: plan", () -> plan(check));
        check.run("Install: download", () -> download(check));
        check.run("Install: verify", () -> verify(check));
        check.run("Install: receipts", () -> receipts(check));
        check.run("Install: packages", () -> packages(check));
        check.run("Install: engine end to end", () -> engine(check));
        check.run("Install: engine failures", () -> failures(check));
        check.run("Install: engine without root", () -> noRoot(check));
    }

    // ------------------------------------------------------------------
    // Plan
    // ------------------------------------------------------------------

    static Plan.Package pkg(String id, Plan.Kind kind, String version) {
        return new Plan.Package(id, kind, id.toUpperCase(), version, "https://example.com/" + id, 10, sha("x"));
    }

    private static Map<String, Plan.Package> offer(Plan.Package... packages) {
        Map<String, Plan.Package> out = new LinkedHashMap<>();
        for (Plan.Package p : packages) out.put(p.id, p);
        return out;
    }

    private static Map<String, Plan.Installed> phone(Plan.Installed... installed) {
        Map<String, Plan.Installed> out = new LinkedHashMap<>();
        for (Plan.Installed i : installed) out.put(i.id, i);
        return out;
    }

    private static List<Plan.Request> ask(Object... pairs) {
        List<Plan.Request> out = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) out.add(new Plan.Request((Plan.Op) pairs[i], (String) pairs[i + 1]));
        return out;
    }

    private static String steps(Plan plan) {
        StringBuilder s = new StringBuilder();
        for (Plan.Step step : plan.steps) {
            if (s.length() > 0) s.append(", ");
            s.append(step.op.name().toLowerCase()).append(' ').append(step.id);
        }
        return s.toString();
    }

    private static void plan(Check check) throws Exception {
        final String self = "com.wave.xp3";
        Plan.Package app = pkg("com.a.xp3", Plan.Kind.APK, "1.0");
        Plan.Package lib = pkg("lib", Plan.Kind.MODULE, "2.0");
        Plan.Package base = pkg("base", Plan.Kind.MODULE, "1.0");
        app.depends.add(new Plan.Dep("lib", "1.5"));
        lib.depends.add(new Plan.Dep("base", null));
        Plan.Package wave = pkg(self, Plan.Kind.APK, "0.2.0");
        final Map<String, Plan.Package> offered = offer(app, lib, base, wave);

        Plan p = Plan.make(ask(Plan.Op.INSTALL, self, Plan.Op.INSTALL, "com.a.xp3"), offered, phone(), null, self);
        check.same("deps first, Wave last", "install base, install lib, install com.a.xp3, install com.wave.xp3", steps(p));
        check.same("why", "Needed by LIB", p.steps.get(0).why);
        check.ok("self flagged", p.steps.get(3).self);
        check.ok("reboot", p.needsReboot());

        p = Plan.make(ask(Plan.Op.INSTALL, "com.a.xp3"), offered,
            phone(new Plan.Installed("lib", Plan.Kind.MODULE, "1.6", false),
                new Plan.Installed("base", Plan.Kind.MODULE, "0.1", false)), null, self);
        check.same("satisfied deps skipped", "install com.a.xp3", steps(p));
        check.ok("apk only, no reboot", !p.needsReboot());

        p = Plan.make(ask(Plan.Op.INSTALL, "com.a.xp3"), offered,
            phone(new Plan.Installed("lib", Plan.Kind.MODULE, "1.0", false),
                new Plan.Installed("base", Plan.Kind.MODULE, "1.0", false)), null, self);
        check.same("too old dep updated", "update lib, install com.a.xp3", steps(p));

        p = Plan.make(ask(Plan.Op.INSTALL, "base"), offered,
            phone(new Plan.Installed("base", Plan.Kind.MODULE, "1.0", false)), null, self);
        check.same("asked for the same: reinstall", "reinstall base", steps(p));
        p = Plan.make(ask(Plan.Op.REINSTALL, "base"), offered,
            phone(new Plan.Installed("base", Plan.Kind.MODULE, "1.0", false)), null, self);
        check.same("reinstall", "reinstall base", steps(p));
        p = Plan.make(ask(Plan.Op.INSTALL, "base"), offered,
            phone(new Plan.Installed("base", Plan.Kind.MODULE, "1.0", true)), null, self);
        check.same("pending removal: install again", "install base", steps(p));

        check.fails("newer on phone", "newer BASE", () -> Plan.make(ask(Plan.Op.INSTALL, "base"), offered,
            phone(new Plan.Installed("base", Plan.Kind.MODULE, "3.0", false)), null, self));
        check.fails("unknown", "No source offers nope", () -> Plan.make(ask(Plan.Op.INSTALL, "nope"), offered,
            phone(), null, self));
        check.fails("nothing", "Nothing to do", () -> Plan.make(ask(), offered, phone(), null, self));

        Plan.Package needy = pkg("needy", Plan.Kind.APK, "1.0");
        needy.depends.add(new Plan.Dep("lib", "9.0"));
        check.fails("dep too new for any source", "lib 9.0 or newer",
            () -> Plan.make(ask(Plan.Op.INSTALL, "needy"), offer(needy, lib, base), phone(), null, self));
        Plan.Package orphan = pkg("orphan", Plan.Kind.APK, "1.0");
        orphan.depends.add(new Plan.Dep("ghost", null));
        check.fails("missing dep", "ghost", () -> Plan.make(ask(Plan.Op.INSTALL, "orphan"), offer(orphan),
            phone(), null, self));

        Plan.Package x = pkg("x", Plan.Kind.MODULE, "1");
        Plan.Package y = pkg("y", Plan.Kind.MODULE, "1");
        x.depends.add(new Plan.Dep("y", null));
        y.depends.add(new Plan.Dep("x", null));
        check.fails("cycle", "depends on itself", () -> Plan.make(ask(Plan.Op.INSTALL, "x"), offer(x, y),
            phone(), null, self));

        Plan.Package fighter = pkg("fighter", Plan.Kind.MODULE, "1");
        fighter.conflicts.add("base");
        check.fails("conflict with installed", "alongside BASE", () -> Plan.make(ask(Plan.Op.INSTALL, "fighter"),
            offer(fighter, base), phone(new Plan.Installed("base", Plan.Kind.MODULE, "1.0", false)), null, self));
        check.fails("conflict in plan", "alongside", () -> Plan.make(
            ask(Plan.Op.INSTALL, "fighter", Plan.Op.INSTALL, "base"), offer(fighter, base), phone(), null, self));
        p = Plan.make(ask(Plan.Op.INSTALL, "fighter", Plan.Op.REMOVE, "base"), offer(fighter, base),
            phone(new Plan.Installed("base", Plan.Kind.MODULE, "1.0", false)), null, self);
        check.same("conflict removed in the same plan", "remove base, install fighter", steps(p));
        check.fails("conflict declared by the installed one", "alongside FIGHTER", () -> Plan.make(
            ask(Plan.Op.INSTALL, "base"), offer(fighter, base),
            phone(new Plan.Installed("fighter", Plan.Kind.MODULE, "1", false)), null, self));

        Plan.Package improved = pkg("messages_improved", Plan.Kind.MODULE, "1.2.0");
        improved.replaces.add("messages_argon");
        p = Plan.make(ask(Plan.Op.INSTALL, "messages_improved"), offer(improved),
            phone(new Plan.Installed("messages_argon", Plan.Kind.MODULE, "dev", false)), null, self);
        check.same("replaces, after", "install messages_improved, remove messages_argon", steps(p));
        check.same("replaced why", "Replaced by MESSAGES_IMPROVED", p.steps.get(1).why);
        p = Plan.make(ask(Plan.Op.INSTALL, "messages_improved"), offer(improved), phone(), null, self);
        check.same("replaces nothing installed", "install messages_improved", steps(p));

        check.fails("remove a dependency", "needs LIB", () -> Plan.make(ask(Plan.Op.REMOVE, "lib"), offered,
            phone(new Plan.Installed("lib", Plan.Kind.MODULE, "2.0", false),
                new Plan.Installed("com.a.xp3", Plan.Kind.APK, "1.0", false)), null, self));
        p = Plan.make(ask(Plan.Op.REMOVE, "lib", Plan.Op.REMOVE, "com.a.xp3"), offered,
            phone(new Plan.Installed("lib", Plan.Kind.MODULE, "2.0", false),
                new Plan.Installed("com.a.xp3", Plan.Kind.APK, "1.0", false)), null, self);
        check.same("remove both", "remove lib, remove com.a.xp3", steps(p));
        check.fails("remove self", "Wave can't remove itself", () -> Plan.make(ask(Plan.Op.REMOVE, self), offered,
            phone(new Plan.Installed(self, Plan.Kind.APK, "0.1.0", false)), null, self));
        check.fails("remove missing", "isn't installed", () -> Plan.make(ask(Plan.Op.REMOVE, "lib"), offered,
            phone(), null, self));
        check.fails("remove and install", "both removed and installed", () -> Plan.make(
            ask(Plan.Op.REMOVE, "base", Plan.Op.INSTALL, "base"), offered,
            phone(new Plan.Installed("base", Plan.Kind.MODULE, "0.5", false)), null, self));

        Plan.Package picky = pkg("picky", Plan.Kind.APK, "1");
        picky.devices.add("XP3800");
        picky.minSdk = 27;
        Plan.Device xp3 = new Plan.Device(27, "XP3plus", "XP3800");
        check.same("compatible", "install picky",
            steps(Plan.make(ask(Plan.Op.INSTALL, "picky"), offer(picky), phone(), xp3, self)));
        check.fails("other phone", "isn't made for this phone", () -> Plan.make(ask(Plan.Op.INSTALL, "picky"),
            offer(picky), phone(), new Plan.Device(27, "pixel"), self));
        check.fails("old android", "newer Android", () -> Plan.make(ask(Plan.Op.INSTALL, "picky"),
            offer(picky), phone(), new Plan.Device(26, "XP3800"), self));

        Plan.Package odd = new Plan.Package("odd", Plan.Kind.of("theme"), "Odd", "1", "https://e/x", 1, sha("x"));
        check.fails("unknown type", "kind of package", () -> Plan.make(ask(Plan.Op.INSTALL, "odd"), offer(odd),
            phone(), null, self));
        check.fails("kind changed", "different kind", () -> Plan.make(ask(Plan.Op.INSTALL, "base"), offered,
            phone(new Plan.Installed("base", Plan.Kind.APK, "0.1", false)), null, self));
    }

    // ------------------------------------------------------------------
    // A file server with ranges, and a way to cut a download short
    // ------------------------------------------------------------------

    static final class Files2 implements AutoCloseable {
        final HttpServer server;
        final Map<String, byte[]> files = Collections.synchronizedMap(new HashMap<String, byte[]>());
        /** Path -> bytes after which the next answer is cut off (once). */
        final Map<String, Integer> cut = Collections.synchronizedMap(new HashMap<String, Integer>());
        final List<String> ranges = Collections.synchronizedList(new ArrayList<String>());
        volatile boolean ignoreRanges;

        Files2() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::serve);
            server.start();
        }

        String url(String name) {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/" + name;
        }

        private void serve(HttpExchange ex) throws IOException {
            String name = ex.getRequestURI().getPath().substring(1);
            byte[] body = files.get(name);
            if (body == null) {
                ex.sendResponseHeaders(404, -1);
                ex.close();
                return;
            }
            String range = ex.getRequestHeaders().getFirst("Range");
            ranges.add(range == null ? "-" : range);
            int from = 0;
            int status = 200;
            if (range != null && !ignoreRanges && range.startsWith("bytes=") && range.endsWith("-")) {
                from = Integer.parseInt(range.substring(6, range.length() - 1));
                if (from >= body.length) {
                    ex.sendResponseHeaders(416, -1);
                    ex.close();
                    return;
                }
                status = 206;
                ex.getResponseHeaders().add("Content-Range",
                    "bytes " + from + "-" + (body.length - 1) + "/" + body.length);
            }
            int length = body.length - from;
            ex.sendResponseHeaders(status, length);
            OutputStream out = ex.getResponseBody();
            Integer stop = cut.remove(name);
            try {
                if (stop != null) {
                    out.write(body, from, Math.min(stop, length));
                    out.flush();
                    // Drop the connection mid-body.
                    ex.getHttpContext().getServer();
                    throw new IOException("cut");
                }
                out.write(body, from, length);
            } finally {
                if (stop != null) {
                    // Closing the raw exchange without finishing the body.
                    try {
                        ex.getResponseBody().close();
                    } catch (IOException ignored) {
                        // Expected: the body was short.
                    }
                } else {
                    out.close();
                }
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static byte[] bytes(int n, int seed) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) (i * 31 + seed);
        return b;
    }

    static String sha(String text) {
        return sha(text.getBytes(StandardCharsets.UTF_8));
    }

    static String sha(byte[] data) {
        try {
            StringBuilder s = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) s.append(String.format("%02x", b & 0xff));
            return s.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void download(Check check) throws Exception {
        File dir = Files.createTempDirectory("wave-download").toFile();
        try (Files2 server = new Files2()) {
            final byte[] data = bytes(300000, 7);
            server.files.put("a.zip", data);
            final File to = new File(dir, "a.zip");
            final File part = new File(dir, "a.zip.part");
            final long[] seen = {-1, -1};
            Download.fetch(server.url("a.zip"), data.length, sha(data), to, (done, total) -> {
                seen[0] = done;
                seen[1] = total;
            }, null);
            check.ok("saved", Arrays.equals(data, Files.readAllBytes(to.toPath())));
            check.same("progress end", (long) data.length, seen[0]);
            check.same("progress total", (long) data.length, seen[1]);
            check.ok("no part", !part.exists());

            // Cut off half way: the part stays, and the next try resumes.
            to.delete();
            server.cut.put("a.zip", 120000);
            server.ranges.clear();
            check.fails("cut short", () -> Download.fetch(server.url("a.zip"), data.length, sha(data), to, null, null));
            check.ok("part kept", part.isFile() && part.length() > 0 && part.length() < data.length);
            check.ok("nothing saved", !to.exists());
            Download.fetch(server.url("a.zip"), data.length, sha(data), to, null, null);
            check.ok("resumed whole", Arrays.equals(data, Files.readAllBytes(to.toPath())));
            check.ok("asked for the rest", server.ranges.size() == 2 && server.ranges.get(1).startsWith("bytes="));

            // A server that ignores ranges: starts again.
            to.delete();
            server.cut.put("a.zip", 1000);
            check.fails("cut again", () -> Download.fetch(server.url("a.zip"), data.length, sha(data), to, null, null));
            server.ignoreRanges = true;
            Download.fetch(server.url("a.zip"), data.length, sha(data), to, null, null);
            check.ok("restarted whole", Arrays.equals(data, Files.readAllBytes(to.toPath())));
            server.ignoreRanges = false;

            to.delete();
            check.fails("wrong hash", "doesn't match", () -> Download.fetch(server.url("a.zip"), data.length,
                sha("other"), to, null, null));
            check.ok("wrong hash leaves nothing", !to.exists() && !part.exists());
            check.fails("wrong size", "size", () -> Download.fetch(server.url("a.zip"), data.length - 1,
                sha(data), to, null, null));
            check.ok("wrong size leaves nothing", !to.exists() && !part.exists());
            check.fails("missing", "missing", () -> Download.fetch(server.url("nope.zip"), 10, sha("x"), to,
                null, null));
            check.fails("http elsewhere", "https", () -> Download.fetch("http://example.com/a", 10, sha("x"), to,
                null, null));
            check.fails("bad sha", "SHA-256", () -> Download.fetch(server.url("a.zip"), 10, "nothex", to, null, null));
            check.fails("stopped", "Stopped", () -> Download.fetch(server.url("a.zip"), data.length, sha(data), to,
                null, () -> true));
            check.ok("stopped leaves nothing", !to.exists() && !part.exists());
            check.fails("too big", "size", () -> Download.fetch(server.url("a.zip"), 4L << 30, sha(data), to,
                null, null));
            File refused = new File(dir, "refused");
            check.fails("no network", () -> Download.fetch("http://127.0.0.1:1/x", 10, sha("x"), refused, null, null));
            check.ok("no network leaves nothing", !refused.exists() && !new File(dir, "refused.part").exists());
        } finally {
            for (File f : dir.listFiles()) f.delete();
            dir.delete();
        }
    }

    // ------------------------------------------------------------------

    static File moduleZip(File dir, String id, String version) throws IOException {
        File zip = new File(dir, id + "-" + version + ".zip");
        writeModuleZip(zip, "id=" + id + "\nname=" + id + "\nversion=" + version + "\nversionCode=1\nauthor=t\n");
        return zip;
    }

    static void writeModuleZip(File zip, String prop) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            if (prop != null) {
                out.putNextEntry(new ZipEntry("module.prop"));
                out.write(prop.getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
            out.putNextEntry(new ZipEntry("system/app/X/X.apk"));
            out.write(new byte[] {1, 2, 3});
            out.closeEntry();
        }
    }

    private static void verify(Check check) throws Exception {
        File dir = Files.createTempDirectory("wave-verify").toFile();
        try {
            File zip = moduleZip(dir, "m1", "1.2.0");
            check.same("module ok", null, Verify.module(zip, "m1", "1.2.0"));
            check.same("module version parts", null, Verify.module(zip, "m1", "1.2"));
            check.same("module other id", "The download is module m1, not m2.", Verify.module(zip, "m2", "1.2.0"));
            check.ok("module other version", Verify.module(zip, "m1", "1.3").contains("version 1.2.0"));
            File noProp = new File(dir, "noprop.zip");
            writeModuleZip(noProp, null);
            check.same("no module.prop", "The download isn't a module.", Verify.module(noProp, "m1", "1"));
            File notZip = new File(dir, "not.zip");
            Sandbox.write(notZip, "hello");
            check.same("not a zip", "The download isn't a module.", Verify.module(notZip, "m1", "1"));
            File badId = new File(dir, "badid.zip");
            writeModuleZip(badId, "id=../x\nversion=1\n");
            check.same("bad id", "The module has no usable id.", Verify.module(badId, "../x", "1"));

            check.same("apk ok new", null, Verify.apk("com.a", "1.0", "com.a", "1.0", 3, -1, null, false));
            check.same("apk ok update", null, Verify.apk("com.a", "1.1", "com.a", "1.1", 4, 3, "1.0", true));
            check.same("apk unreadable", "The download isn't an app this phone can read.",
                Verify.apk("com.a", "1.0", null, null, 0, -1, null, false));
            check.same("apk other package", "The download is com.b, not com.a.",
                Verify.apk("com.a", "1.0", "com.b", "1.0", 3, -1, null, false));
            check.ok("apk other version", Verify.apk("com.a", "1.0", "com.a", "0.9", 3, -1, null, false)
                .contains("0.9"));
            check.same("apk other key", "The phone's copy is signed with a different key.",
                Verify.apk("com.a", "1.0", "com.a", "1.0", 3, 2, "0.9", false));
            check.ok("apk older code", Verify.apk("com.a", "1.0", "com.a", "1.0", 2, 3, "1.0", true)
                .contains("older"));
        } finally {
            for (File f : dir.listFiles()) f.delete();
            dir.delete();
        }
    }

    private static void receipts(Check check) throws Exception {
        File dir = Files.createTempDirectory("wave-receipts").toFile();
        try {
            File file = new File(dir, "receipts.json");
            Receipts r = Receipts.load(file);
            check.same("empty", 0, r.all().size());
            r.record(new Receipts.Receipt("m1", Plan.Kind.MODULE, "1.0", "https://src", sha("a"), 1000));
            r.record(new Receipts.Receipt("com.a", Plan.Kind.APK, "2.0", null, null, 2000));
            Receipts again = Receipts.load(file);
            check.same("kept", 2, again.all().size());
            check.same("kind", Plan.Kind.MODULE, again.get("m1").kind);
            check.same("source", "https://src", again.get("m1").source);
            check.same("time", 2000L, again.get("com.a").time);
            again.forget("m1");
            check.same("forgot", null, Receipts.load(file).get("m1"));
            Sandbox.write(file, "{not json");
            check.same("garbage is empty", 0, Receipts.load(file).all().size());
            Sandbox.write(file, "{\"schema\":1,\"packages\":{\"a\":{\"type\":\"apk\"},\"b\":{\"type\":\"module\","
                + "\"version\":\"1\"},\"c\":5}}");
            check.same("bad entries skipped", 1, Receipts.load(file).all().size());
        } finally {
            for (File f : dir.listFiles()) f.delete();
            dir.delete();
        }
    }

    private static void packages(Check check) throws Exception {
        String json = "{\"id\": \"messages_improved\", \"type\": \"module\", \"name\": \"Messages\","
            + " \"summary\": \"s\", \"version\": \"1.2.0\", \"download\": {\"url\": \"https://e.com/m.zip\","
            + " \"size\": 5, \"sha256\": \"" + sha("x") + "\"}, \"requires\": {\"device\": [\"XP3800\"], \"sdk\": 27},"
            + " \"depends\": [\"base\", {\"id\": \"lib\", \"min\": \"1.0\"}], \"conflicts\": [\"c\"],"
            + " \"replaces\": [\"messages_argon\"]}";
        Plan.Package p = com.wave.xp3.core.install.Packages.of(
            com.wave.xp3.core.sources.PackageManifest.parse(json.getBytes(StandardCharsets.UTF_8)));
        check.same("kind", Plan.Kind.MODULE, p.kind);
        check.same("url", "https://e.com/m.zip", p.url);
        check.same("deps", 2, p.depends.size());
        check.same("any version", null, p.depends.get(0).min);
        check.same("min", "1.0", p.depends.get(1).min);
        check.same("replaces", "messages_argon", p.replaces.get(0));
        check.same("sdk", 27, p.minSdk);
        check.same("device", "XP3800", p.devices.get(0));

        com.wave.xp3.core.install.Packages.PlanFile f = com.wave.xp3.core.install.Packages.planFile(
            "{\"source\": \"debug\", \"requests\": [{\"op\": \"install\", \"id\": \"a\"},"
            + " {\"op\": \"remove\", \"id\": \"b\"}], \"packages\": [{\"id\": \"a\", \"type\": \"module\","
            + " \"version\": \"1\", \"download\": {\"url\": \"http://127.0.0.1:8000/a.zip\", \"size\": 9,"
            + " \"sha256\": \"" + sha("y") + "\"}, \"depends\": [{\"id\": \"z\"}]}]}");
        check.same("plan source", "debug", f.source);
        check.same("plan requests", 2, f.requests.size());
        check.same("plan remove", Plan.Op.REMOVE, f.requests.get(1).op);
        check.same("plan size", 9L, f.packages.get("a").size);
        check.same("plan dep", "z", f.packages.get("a").depends.get(0).id);
        check.fails("plan bad op", () -> com.wave.xp3.core.install.Packages.planFile(
            "{\"requests\": [{\"op\": \"explode\", \"id\": \"a\"}]}"));
    }

    // ------------------------------------------------------------------
    // The engine, end to end
    // ------------------------------------------------------------------

    /**
     * The platform's view of APKs, faked: a test APK is the text
     * "APK <package> <version> <code>", and the phone's copies are a map.
     */
    static final class FakePlatform implements Engine.Platform {
        final File downloads;
        final Map<String, long[]> phoneCodes = new HashMap<>();
        boolean otherKey;

        FakePlatform(File downloads) {
            this.downloads = downloads;
            downloads.mkdirs();
        }

        @Override
        public File downloads() {
            return downloads;
        }

        @Override
        public Engine.Apk read(File apk, String packageName) {
            try {
                String[] parts = new String(Files.readAllBytes(apk.toPath()), StandardCharsets.UTF_8).split(" ");
                if (parts.length != 4 || !parts[0].equals("APK")) return new Engine.Apk(null, null, 0, -1, null, false);
                long[] phone = phoneCodes.get(parts[1]);
                return new Engine.Apk(parts[1], parts[2], Long.parseLong(parts[3]),
                    phone == null ? -1 : phone[0], phone == null ? null : "old", !otherKey);
            } catch (IOException e) {
                return new Engine.Apk(null, null, 0, -1, null, false);
            }
        }

        @Override
        public String selfActivity() {
            return ".MainActivity";
        }
    }

    static byte[] apk(String pkg, String version, int code) {
        return ("APK " + pkg + " " + version + " " + code).getBytes(StandardCharsets.UTF_8);
    }

    static Plan.Package serve(Files2 server, String id, Plan.Kind kind, String version, byte[] data) {
        String name = id + "-" + version + (kind == Plan.Kind.APK ? ".apk" : ".zip");
        server.files.put(name, data);
        return new Plan.Package(id, kind, id, version, server.url(name), data.length, sha(data));
    }

    private static void engine(Check check) throws Exception {
        try (Sandbox box = new Sandbox(); Files2 server = new Files2()) {
            File zip = moduleZip(box.root, "lib", "2.0");
            Plan.Package lib = serve(server, "lib", Plan.Kind.MODULE, "2.0", Files.readAllBytes(zip.toPath()));
            lib.replaces.add("old_mod");
            Plan.Package app = serve(server, "com.a.xp3", Plan.Kind.APK, "1.0", apk("com.a.xp3", "1.0", 3));
            app.depends.add(new Plan.Dep("lib", null));
            lib.source = "https://example.com/other";
            Plan.Package wave = serve(server, "com.wave.xp3", Plan.Kind.APK, "0.2.0", apk("com.wave.xp3", "0.2.0", 2));
            Plan.Package gone = pkg("com.gone.xp3", Plan.Kind.APK, "1");

            // The phone: an old module the new one replaces, an app to remove.
            box.file("modules/old_mod").mkdirs();
            Sandbox.write(box.file("modules/old_mod/module.prop"), "id=old_mod\nversion=dev\n");

            Plan plan = Plan.make(ask(Plan.Op.INSTALL, "com.wave.xp3", Plan.Op.INSTALL, "com.a.xp3",
                Plan.Op.REMOVE, "com.gone.xp3"), offer(lib, app, wave, gone),
                phone(new Plan.Installed("old_mod", Plan.Kind.MODULE, "dev", false),
                    new Plan.Installed("com.gone.xp3", Plan.Kind.APK, "1", false),
                    new Plan.Installed("com.wave.xp3", Plan.Kind.APK, "0.1.0", false)), null, "com.wave.xp3");
            check.same("the plan", "remove com.gone.xp3, install lib, remove old_mod, install com.a.xp3, "
                + "update com.wave.xp3", steps(plan));

            FakePlatform platform = new FakePlatform(new File(box.root, "cache/downloads"));
            platform.phoneCodes.put("com.wave.xp3", new long[] {1});
            File receiptsFile = new File(box.root, "files/receipts.json");
            Receipts receipts = Receipts.load(receiptsFile);
            receipts.record(new Receipts.Receipt("old_mod", Plan.Kind.MODULE, "dev", null, null, 1));
            final List<String> seen = Collections.synchronizedList(new ArrayList<String>());
            ProcessShell shell = new ProcessShell(box.su(), box.env());
            Engine engine = new Engine(plan, shell, platform, receipts, e -> {
                for (Engine.Item i : e.items()) {
                    String s = i.step.id + ":" + i.phase;
                    if (seen.isEmpty() || !seen.get(seen.size() - 1).equals(s)) seen.add(s);
                }
            }, "https://example.com/source", "com.wave.xp3");
            engine.run();
            shell.close();

            check.ok("finished", engine.finished());
            check.ok("all done: " + engine.items(), engine.succeeded());
            check.ok("reboot needed", engine.rebootNeeded());
            check.ok("self updating", engine.selfUpdating());
            check.ok("watchdog note", engine.watchdogNote() != null);
            check.ok("watchdog installed", box.file("post-fs-data.d/wave-watchdog.sh").canExecute()
                && box.file("service.d/wave-watchdog.sh").canExecute());
            check.ok("module waiting", box.file("modules_update/lib/module.prop").isFile());
            check.same("module under watch", "lib\n", box.read("wave/pending"));
            check.ok("replaced flagged", box.file("modules/old_mod/remove").isFile());
            String calls = box.calls();
            check.ok("uninstall", calls.contains("pm uninstall com.gone.xp3"));
            check.ok("streamed install", calls.contains("pm install -r -S " + app.size)
                && calls.contains("pm got " + app.size + " bytes"));
            check.ok("compiled", calls.contains("cmd package compile -m speed com.a.xp3"));
            check.ok("in order", calls.indexOf("pm uninstall") < calls.indexOf("magisk --install-module")
                && calls.indexOf("magisk --install-module") < calls.indexOf("pm install -r -S " + app.size));
            check.ok("downloads cleaned", platform.downloads.listFiles().length == 0);
            check.ok("progress seen", seen.contains("com.a.xp3:DOWNLOADING") && seen.contains("lib:INSTALLING"));

            Receipts after = Receipts.load(receiptsFile);
            check.same("app receipt", "1.0", after.get("com.a.xp3").version);
            check.same("app source", "https://example.com/source", after.get("com.a.xp3").source);
            check.same("module receipt", Plan.Kind.MODULE, after.get("lib").kind);
            check.same("package's own source", "https://example.com/other", after.get("lib").source);
            check.same("replaced forgotten", null, after.get("old_mod"));

            // Wave's own update ran detached; it finishes and starts Wave again.
            File result = box.file("wave/self-update");
            for (int i = 0; i < 100 && !result.isFile(); i++) Thread.sleep(50);
            ProcessShell later = new ProcessShell(box.su(), box.env());
            String said = later.run(Scripts.selfUpdateResult(), 5000).output;
            check.same("self update result", "Success", said.trim());
            check.ok("result read once", later.run(Scripts.selfUpdateResult(), 5000).output.isEmpty());
            check.ok("relaunched", box.calls().contains("am start -n com.wave.xp3/.MainActivity"));
            check.ok("copy removed", !new File(box.tmp, "wave-self-update.apk").exists());
            check.same("modules as the phone shows them", "[lib 2.0 PENDING_INSTALL, old_mod dev PENDING_REMOVAL]",
                Modules.parse(later.run(Scripts.listModules(), 5000).output).toString());
            later.close();
        }
    }

    private static Engine runOne(Sandbox box, FakePlatform platform, Plan.Package p) throws Exception {
        Plan plan = Plan.make(ask(Plan.Op.INSTALL, p.id), offer(p), phone(), null, "com.wave.xp3");
        ProcessShell shell = new ProcessShell(box.su(), box.env());
        Engine engine = new Engine(plan, shell, platform, Receipts.load(new File(box.root, "r.json")), null, null,
            "com.wave.xp3");
        engine.run();
        shell.close();
        return engine;
    }

    /** Apps through a stand-in for Installer, and no `su` at all. */
    private static void noRoot(Check check) throws Exception {
        try (Sandbox box = new Sandbox(); Files2 server = new Files2()) {
            FakePlatform platform = new FakePlatform(new File(box.root, "cache/downloads"));
            Plan.Package a = serve(server, "com.a.xp3", Plan.Kind.APK, "1", apk("com.a.xp3", "1", 1));
            Plan.Package b = pkg("com.b.xp3", Plan.Kind.APK, "1");
            Plan plan = Plan.make(ask(Plan.Op.REMOVE, "com.b.xp3", Plan.Op.INSTALL, "com.a.xp3"), offer(a, b),
                phone(new Plan.Installed("com.b.xp3", Plan.Kind.APK, "1", false)), null, "com.wave.xp3");
            final List<String> did = new ArrayList<>();
            ProcessShell none = new ProcessShell(Arrays.asList(new File(box.root, "no-su").getPath()), null);
            File receiptsFile = new File(box.root, "r.json");
            Engine e = new Engine(plan, none, platform, Receipts.load(receiptsFile), null, "src", "com.wave.xp3")
                .apps(new Engine.Apps() {
                    @Override
                    public com.wave.xp3.core.root.PmResult install(File apk, String pkg, boolean self) {
                        did.add("install " + pkg + " " + apk.isFile() + " " + self);
                        return com.wave.xp3.core.root.PmResult.success();
                    }

                    @Override
                    public com.wave.xp3.core.root.PmResult uninstall(String pkg) {
                        did.add("uninstall " + pkg);
                        return com.wave.xp3.core.root.PmResult.success();
                    }
                });
            e.run();
            check.ok("done: " + e.items(), e.succeeded());
            check.same("through the stand-in", "[uninstall com.b.xp3, install com.a.xp3 true false]", did.toString());
            check.same("receipt", "1", Receipts.load(receiptsFile).get("com.a.xp3").version);
            check.ok("nothing left", platform.downloads.listFiles().length == 0);

            Plan.Package m = serve(server, "mod_a", Plan.Kind.MODULE, "1.0",
                Files.readAllBytes(moduleZip(box.root, "mod_a", "1.0").toPath()));
            Engine e2 = new Engine(Plan.make(ask(Plan.Op.INSTALL, "mod_a"), offer(m), phone(), null, "com.wave.xp3"),
                none, platform, Receipts.load(receiptsFile), null, null, "com.wave.xp3")
                .apps(new Engine.Apps() {
                    @Override
                    public com.wave.xp3.core.root.PmResult install(File apk, String pkg, boolean self) {
                        return com.wave.xp3.core.root.PmResult.failure("X", "Refused.");
                    }

                    @Override
                    public com.wave.xp3.core.root.PmResult uninstall(String pkg) {
                        return com.wave.xp3.core.root.PmResult.failure("X", "Refused.");
                    }
                });
            e2.run();
            check.ok("modules still need root", e2.items().get(0).phase == Engine.Phase.FAILED
                && e2.items().get(0).message.contains("no root"));
        }
    }

    private static void failures(Check check) throws Exception {
        try (Sandbox box = new Sandbox(); Files2 server = new Files2()) {
            FakePlatform platform = new FakePlatform(new File(box.root, "cache/downloads"));

            Plan.Package wrongHash = serve(server, "com.h.xp3", Plan.Kind.APK, "1", apk("com.h.xp3", "1", 1));
            server.files.put("com.h.xp3-1.apk", apk("com.h.xp3", "1", 9));
            Engine e = runOne(box, platform, wrongHash);
            check.same("wrong hash fails", Engine.Phase.FAILED, e.items().get(0).phase);
            check.ok("wrong hash says so", e.items().get(0).message.contains("doesn't match"));

            Plan.Package wrongName = serve(server, "com.n.xp3", Plan.Kind.APK, "1", apk("com.other.xp3", "1", 1));
            e = runOne(box, platform, wrongName);
            check.same("wrong package", "The download is com.other.xp3, not com.n.xp3.", e.items().get(0).message);

            Plan.Package cut = serve(server, "com.c.xp3", Plan.Kind.APK, "1", bytes(200000, 3));
            server.cut.put("com.c.xp3-1.apk", 5000);
            e = runOne(box, platform, cut);
            check.same("network lost fails", Engine.Phase.FAILED, e.items().get(0).phase);

            Plan.Package full = serve(server, "com.f.xp3", Plan.Kind.APK, "1", apk("com.f.xp3", "1", 1));
            box.flag("pm-fail", "INSTALL_FAILED_INSUFFICIENT_STORAGE");
            e = runOne(box, platform, full);
            check.same("no space", "The phone is out of space.", e.items().get(0).message);
            new File(box.root, "pm-fail").delete();

            platform.phoneCodes.put("com.k.xp3", new long[] {1});
            platform.otherKey = true;
            Plan.Package key = serve(server, "com.k.xp3", Plan.Kind.APK, "1", apk("com.k.xp3", "1", 2));
            e = runOne(box, platform, key);
            check.same("other key", "The phone's copy is signed with a different key.", e.items().get(0).message);
            platform.otherKey = false;

            File zip = moduleZip(box.root, "mod_a", "1.0");
            Plan.Package wrongModule = serve(server, "mod_b", Plan.Kind.MODULE, "1.0", Files.readAllBytes(zip.toPath()));
            e = runOne(box, platform, wrongModule);
            check.same("wrong module", "The download is module mod_a, not mod_b.", e.items().get(0).message);

            check.ok("nothing installed by failures", !box.calls().contains("pm install")
                || box.calls().split("pm install").length == 2);
            check.ok("nothing left behind", platform.downloads.listFiles().length == 0);
            check.ok("no module installed", !box.file("modules_update").exists());

            box.flag("no-magisk");
            Plan.Package mod = serve(server, "mod_a", Plan.Kind.MODULE, "1.0", Files.readAllBytes(zip.toPath()));
            e = runOne(box, platform, mod);
            check.ok("no magisk", e.items().get(0).message.contains("Magisk isn't installed"));
            new File(box.root, "no-magisk").delete();

            // Two steps; the first fails, the second is not attempted.
            Plan.Package dep = serve(server, "mod_dep", Plan.Kind.MODULE, "1.0", "not a zip".getBytes("UTF-8"));
            Plan.Package user = serve(server, "com.u.xp3", Plan.Kind.APK, "1", apk("com.u.xp3", "1", 1));
            user.depends.add(new Plan.Dep("mod_dep", null));
            Plan plan = Plan.make(ask(Plan.Op.INSTALL, "com.u.xp3"), offer(dep, user), phone(), null, "com.wave.xp3");
            ProcessShell shell = new ProcessShell(box.su(), box.env());
            e = new Engine(plan, shell, platform, Receipts.load(new File(box.root, "r.json")), null, null,
                "com.wave.xp3");
            e.run();
            check.same("first fails", Engine.Phase.FAILED, e.items().get(0).phase);
            check.same("rest skipped", Engine.Phase.SKIPPED, e.items().get(1).phase);
            check.ok("skip says why", e.items().get(1).message.contains("mod_dep failed"));

            Engine stopped = new Engine(plan, shell, platform, Receipts.load(new File(box.root, "r.json")), null,
                null, "com.wave.xp3");
            stopped.stop();
            stopped.run();
            check.ok("stopped", stopped.items().get(1).phase == Engine.Phase.SKIPPED
                && "Stopped.".equals(stopped.items().get(1).message));
            shell.close();

            box.flag("deny");
            e = runOne(box, platform, user.depends.isEmpty() ? user : full);
            check.same("no root fails", Engine.Phase.FAILED, e.items().get(0).phase);
            check.ok("no root says what to do", e.items().get(0).message.contains("Magisk"));
            check.same("no receipts from failures", 0, Receipts.load(new File(box.root, "r.json")).all().size());
        }
    }
}
