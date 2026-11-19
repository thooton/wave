package com.wave.xp3.core.sources;

import com.xp3.common.Check;
import com.xp3.common.Json;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class SourcesSelfTest {
    private static final String HEAD_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String HEAD_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String LIST = "applications/list/package.json";
    private static final String LIST_ICON = "applications/list/icon.png";
    private static final String APP_ID = "com.list.xp3";

    public static void run(Check check) {
        check.run("Source manifests", () -> manifests(check));
        check.run("Source store", () -> store(check));
        check.run("Parallel source refresh", () -> independent(check));
        check.run("Index and search", () -> index(check));
        check.run("Source compatibility and phone status", () -> compatibility(check));
        check.run("One run in flight, one follow-up", () -> inFlight(check));
        check.run("Icons that are not what they claim", () -> icons(check));
        check.run("Damaged source list and trees", () -> damage(check));
        check.run("Tree generations under readers and a removal", () -> generations(check));
        check.run("Empty, maximal and very large sources", () -> extremes(check));
        check.run("Store failures in plain words", () -> plainFailures(check));
    }

    /** Refused with a SourceFailure whose detail (not shown on screen) mentions `detail`, and whose message is plain. */
    static void failsBecause(Check check, String name, String detail, Check.Body body) {
        try {
            body.run();
            check.ok(name + ": refused", false);
        } catch (com.wave.xp3.core.git.SourceFailure e) {
            check.ok(name + ": " + e.detail(), e.detail().contains(detail));
            check.same(name + ": the screen's words", e.kind.says, e.getMessage());
        } catch (Exception e) {
            check.ok(name + ": not a SourceFailure: " + e, false);
        }
    }

    /** Words a person holding the phone shouldn't meet in an error. */
    public static final java.util.regex.Pattern JARGON = java.util.regex.Pattern.compile(
        "Git|HTTP\\b|\\bpack|PNG|HEAD|SHA|[KM]iB|JSON|\\bURL|wave\\.json|manifest|repository|null|Exception|/data/");

    private static void plainFailures(Check check) throws Exception {
        Path root = Files.createTempDirectory("wave-plain-test");
        try {
            AtomicReference<Exception> next = new AtomicReference<>();
            SourceStore store = new SourceStore(root.toFile(), (url, commit) -> {
                Exception e = next.get();
                if (e instanceof IOException) throw (IOException) e;
                throw (RuntimeException) e;
            });
            Exception[] fetchFailures = {new IOException("Git pack checksum mismatch"), new IllegalStateException("broken"),
                new IOException((String) null)};
            for (Exception e : fetchFailures) {
                next.set(e);
                try {
                    store.preview("https://sample.test/plain");
                    check.ok("a failed fetch is refused", false);
                } catch (com.wave.xp3.core.git.SourceFailure f) {
                    check.same("a fetcher's own words become the detail: " + e, com.wave.xp3.core.git.SourceFailure.Kind.UNREADABLE, f.kind);
                    check.ok("and stay off the screen: " + f.getMessage(), !JARGON.matcher(f.getMessage()).find());
                }
            }
            next.set(new com.wave.xp3.core.git.SourceFailure(com.wave.xp3.core.git.SourceFailure.Kind.SIGN_IN, "HTTP 401"));
            check.fails("a typed failure passes through", "needs a sign-in", () -> store.preview("https://sample.test/plain"));
            String defaultId = store.sources().get(0).id;
            Check.Body[] refusals = {
                () -> store.remove(defaultId),
                () -> store.add(SourceStore.DEFAULT_URL),
                () -> store.add("http://example.org/source"),
                () -> store.add("https://example.org/" + new String(new char[5000]).replace('\0', 'x')),
                () -> store.refresh("no-such-source"),
            };
            for (Check.Body refusal : refusals) {
                try {
                    refusal.run();
                    check.ok("refused", false);
                } catch (Exception e) {
                    String said = String.valueOf(e.getMessage());
                    check.ok("the store's own refusal is plain: " + said, !JARGON.matcher(said).find() && said.length() <= 70);
                }
            }
        } finally { erase(root); }
    }

    private static void manifests(Check check) throws Exception {
        Map<String, byte[]> valid = example();
        SourceManifest header = SourceManifest.parse(valid.get("wave.json"));
        check.same("source name", "Example Source", header.name);
        check.same("ordered sections", Arrays.asList("applications", "system"), new ArrayList<>(header.sections.keySet()));
        PackageManifest pkg = PackageManifest.parse(valid.get(LIST));
        check.same("id", APP_ID, pkg.id);
        check.ok("download typed", pkg.download.size > 0 && pkg.download.sha256.length() == 64);
        check.same("source v2 retained", true, SourceManifest.parse(utf8("{\"wave\":2,\"name\":\"New\",\"unknown\":true}")).needsNewerWave());
        check.fails("duplicate keys", "Invalid JSON", () -> SourceManifest.parse(variant("bad-header/wave.json")));
        check.fails("bad package URL", "download.url", () -> PackageManifest.parse(variant("bad-package/package.json")));
        check.fails("bad calendar date", "calendar date", () -> PackageManifest.parse(variant("bad-date/package.json")));
        check.fails("bad icon", "PNG", () -> Png.check(variant("bad-icon/icon.png")));
        check.fails("summary must be a line", "summary", () -> PackageManifest.parse(change(valid.get(LIST), "summary", "one\ntwo")));
        check.fails("invalid UTF-8", "UTF-8", () -> SourceManifest.parse(new byte[] {(byte) 0xc0}));
        check.fails("unpaired JSON surrogate", "Unicode", () -> SourceManifest.parse(utf8("{\"wave\":1,\"name\":\"\\ud800\"}")));
        check.fails("required child field", "download.sha256", () -> PackageManifest.parse(change(valid.get(LIST), "download", Json.map("url", "https://example.org/a", "size", 12))));
        check.fails("typed optional field", "author", () -> PackageManifest.parse(change(valid.get(LIST), "author", null)));
        check.fails("manifest cap", "64 KiB", () -> PackageManifest.parse(new byte[65537]));
        check.fails("numeric range", "range", () -> SourceManifest.parse(utf8("{\"wave\":1e9999,\"name\":\"Too far\"}")));
        check.ok("unknown type is visible", !PackageManifest.parse(variant("unknown-type/package.json")).supportedType());
    }

    private static void store(Check check) throws Exception {
        Path root = Files.createTempDirectory("wave-source-test");
        try {
            Map<String, byte[]> valid = example();
            AtomicReference<SourceStore.Update> payload = new AtomicReference<>(new SourceStore.Update(HEAD_A, valid));
            AtomicReference<String> known = new AtomicReference<>(), requested = new AtomicReference<>();
            SourceStore.Fetcher fetcher = (url, commit) -> { requested.set(url); known.set(commit); return payload.get(); };
            SourceStore store = new SourceStore(root.toFile(), fetcher);
            check.same("default present", 1, store.sources().size());
            check.ok("default is protected", store.sources().get(0).builtIn);
            check.fails("cannot remove default", "can't be removed", () -> store.remove(store.sources().get(0).id));
            check.fails("default alias is protected", "already added", () -> store.add(SourceStore.DEFAULT_URL.substring(0, SourceStore.DEFAULT_URL.length() - 4)));
            SourceStore.Preview preview = store.preview("https://sample.test/preview");
            check.same("preview identifies source", "Example Source", preview.manifest.name);
            check.same("preview counts working packages", 2, preview.packageCount);
            check.ok("preview exposes valid PNG without persisting any tree", Arrays.equals(valid.get("icon.png"), preview.icon()));
            check.same("preview is not persisted", 1, store.sources().size());
            SourceStore.Source approved = store.add(preview);
            check.same("approval uses fetched tree", HEAD_A, store.index().source(approved.id).info.commit);
            check.fails("preview cannot be approved twice", "already added", () -> store.add(preview));
            store.remove(approved.id);
            check.fails("HTTPS only", "https://", () -> store.add("http://example.org/source"));
            String privateUrl = "https://user:token@Example.org:443/source/";
            SourceStore.Source added = store.add(privateUrl);
            check.same("private URL redacted", "https://example.org/source", added.url);
            check.fails("credentials and slashes don't make a duplicate", "already added", () -> store.add("https://example.org/source"));
            check.ok("first refresh changed", store.refresh(added.id).changed);
            check.same("no known HEAD on first fetch", null, known.get());
            check.same("private URL used only on fetch", privateUrl, requested.get());
            check.same("committed tree", HEAD_A, store.index().source(added.id).info.commit);
            check.same("got real package", APP_ID, store.index().get(APP_ID).manifest.id);
            check.ok("good package icon available", store.index().get(APP_ID).icon.isFile());
            payload.set(new SourceStore.Update(HEAD_A, null));
            check.ok("unchanged result", !store.refresh(added.id).changed);
            check.same("unchanged uses cached HEAD", HEAD_A, known.get());
            check.ok("stored refresh time", store.index().source(added.id).info.lastRefresh > 0);

            Map<String, byte[]> invalid = new LinkedHashMap<>(valid);
            invalid.put("wave.json", variant("bad-header/wave.json"));
            payload.set(new SourceStore.Update(HEAD_B, invalid));
            failsBecause(check, "failed validation leaves active tree", "wave.json", () -> store.refresh(added.id));
            check.same("old HEAD survives validation failure", HEAD_A, store.index().source(added.id).info.commit);
            invalid.put("wave.json", valid.get("wave.json"));
            invalid.put("../../escape.png", new byte[0]);
            failsBecause(check, "failed staging leaves active tree", "Invalid source path", () -> store.refresh(added.id));
            check.same("previous package still readable", APP_ID, store.index().get(APP_ID).manifest.id);
            invalid.remove("../../escape.png");
            invalid.put(LIST, variant("bad-package/package.json"));
            invalid.put(LIST_ICON, variant("bad-icon/icon.png"));
            check.ok("one bad package does not prevent refresh", store.refresh(added.id).changed);
            SourceStore reopened = new SourceStore(root.toFile(), fetcher);
            Index.Source cached = reopened.index().source(added.id);
            check.same("HEAD survives restart", HEAD_B, cached.info.commit);
            check.same("bad package hidden, other package retained", 1, cached.packages.size());
            check.ok("path errors surfaced", cached.issues.containsKey(LIST) && cached.issues.containsKey(LIST_ICON));
            check.same("built-in source still first", true, reopened.sources().get(0).builtIn);
            reopened.remove(added.id);
            check.same("removed source absent", null, reopened.index().source(added.id));
            check.same("removal persists", 1, new SourceStore(root.toFile(), fetcher).sources().size());
        } finally { erase(root); }
    }

    private static void independent(Check check) throws Exception {
        Path root = Files.createTempDirectory("wave-refresh-test");
        try {
            Map<String, byte[]> tree = example();
            CountDownLatch otherStarted = new CountDownLatch(1);
            SourceStore store = new SourceStore(root.toFile(), (url, known) -> {
                if (SourceStore.DEFAULT_URL.equals(url)) {
                    try { if (!otherStarted.await(3, TimeUnit.SECONDS)) throw new IOException("blocked other source"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
                    throw new com.wave.xp3.core.git.SourceFailure(com.wave.xp3.core.git.SourceFailure.Kind.SERVER, "default unavailable");
                }
                otherStarted.countDown();
                return new SourceStore.Update(HEAD_A, tree);
            });
            SourceStore.Source second = store.add("https://sample.org/source");
            List<SourceStore.Refresh> results = store.refreshAll();
            check.same("failure reported independently", com.wave.xp3.core.git.SourceFailure.Kind.SERVER.says, results.get(0).error);
            check.same("other source did not wait", null, results.get(1).error);
            check.same("other source last-good committed", HEAD_A, store.index().source(second.id).info.commit);
        } finally { erase(root); }
    }

    private static void index(Check check) throws Exception {
        Path root = Files.createTempDirectory("wave-index-test");
        try {
            Map<String, byte[]> first = example(), second = example();
            second.put(LIST, change(first.get(LIST), "type", "module"));
            first.put("system/messages-improved/package.json", change(first.get("system/messages-improved/package.json"), "summary", "A list of groups"));
            SourceStore store = new SourceStore(root.toFile(), (url, known) ->
                new SourceStore.Update(known == null ? HEAD_A : HEAD_B, url.contains("second") ? second : first));
            String one = store.add("https://first.test/source").id, two = store.add("https://second.test/source").id;
            store.refresh(one);
            store.refresh(two);
            Index index = store.index();
            check.same("deduplicated candidate count", 2, index.all().size());
            check.same("tie chooses earliest source", one, index.get(APP_ID).source.id);
            check.same("tie chooses winner's type", "apk", index.get(APP_ID).manifest.type);
            check.same("source-specific rows retain own type", "module", index.source(two).section("applications").get(0).manifest.type);
            check.same("section count", 1, index.sections().get(0).count());
            check.same("section display order", "Applications", index.sections().get(0).name);
            check.same("name matches rank ahead of summary", APP_ID, index.search(" LIST ").get(0).manifest.id);
            check.same("summary is searched too", 2, index.search("list").size());
            check.same("empty search", 0, index.search("  ").size());
            second.put(LIST, change(second.get(LIST), "version", "1.1000.7", "description", "Search this unique phrase."));
            store.refresh(two);
            Index later = store.index();
            check.same("higher numeric version wins", two, later.get(APP_ID).source.id);
            check.same("higher version's type wins", "module", later.get(APP_ID).manifest.type);
            check.same("description searched", APP_ID, later.search("unique phrase").get(0).manifest.id);
        } finally { erase(root); }
    }

    private static void compatibility(Check check) throws Exception {
        byte[] list = example().get(LIST);
        PackageManifest pkg = PackageManifest.parse(change(list, "version", "1.10", "requires", Json.map(
            "device", Json.list("XP3800"), "sdk", 27, "wave", 1, "firmware", Json.list("tested"))));
        Compat.Phone phone = new Compat.Phone("argon", "XP3800", 27, "unlisted", 1);
        Compat ok = Compat.of(pkg, 1, phone);
        check.ok("model matches, unlike firmware", ok.fits && ok.reason == null);
        check.same("firmware is only advisory", "Not tested on this firmware", ok.note);
        check.same("device can match instead of model", null, Compat.of(pkg, 1, new Compat.Phone("XP3800", "other", 27, "tested", 1)).note);
        check.same("device mismatch", "Not compatible with this device", Compat.of(pkg, 1, new Compat.Phone("not", "either", 27, "tested", 1)).reason);
        check.same("SDK mismatch", "Needs Android 8.1 or later", Compat.of(pkg, 1, new Compat.Phone("argon", "XP3800", 26, "tested", 1)).reason);
        check.same("future source format", "Needs a newer Wave", Compat.of(pkg, 2, phone).reason);
        PackageManifest unknown = PackageManifest.parse(variant("unknown-type/package.json"));
        check.same("future package type", "Needs a newer Wave", Compat.of(unknown, 1, phone).reason);
        check.same("future requires.wave", "Needs a newer Wave", Compat.of(PackageManifest.parse(change(list,
            "requires", Json.map("wave", 2))), 1, phone).reason);
        check.same("absent", Status.NOT_INSTALLED, Status.of(pkg, (id, type) -> null));
        check.same("current", Status.INSTALLED, Status.of(pkg, (id, type) -> new Status.Installed("1.10.0", false, false)));
        check.same("update", Status.UPDATE, Status.of(pkg, (id, type) -> new Status.Installed("1.9", false, false)));
        check.same("newer", Status.NEWER, Status.of(pkg, (id, type) -> new Status.Installed("1.11", false, false)));
        check.same("pending install", Status.PENDING_INSTALL, Status.of(pkg, (id, type) -> new Status.Installed("1.9", true, false)));
        check.same("pending removal takes precedence", Status.PENDING_REMOVAL, Status.of(pkg, (id, type) -> new Status.Installed("1.9", true, true)));
    }

    private static void inFlight(Check check) throws Exception {
        InFlight gate = new InFlight();
        check.ok("first request runs", gate.begin(1));
        check.ok("second request waits", !gate.begin(2));
        check.ok("third merges into the same follow-up", !gate.begin(4));
        check.ok("waiting sees a flag", gate.waiting(2) && gate.waiting(4) && !gate.waiting(8));
        check.same("one follow-up carries every flag", 6, gate.finish());
        check.ok("gate is held for the follow-up", gate.busy() && !gate.begin(8));
        check.same("last follow-up", 8, gate.finish());
        check.same("then free", 0, gate.finish());
        check.ok("free after the last run", !gate.busy() && gate.begin(0));
        gate.finish();
        check.fails("finish with nothing running", "No run in flight", () -> gate.finish());
        java.util.concurrent.atomic.AtomicInteger started = new java.util.concurrent.atomic.AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            Thread thread = new Thread(() -> {
                try { go.await(); } catch (InterruptedException e) { return; }
                if (gate.begin(1)) started.incrementAndGet();
            });
            thread.start();
            threads.add(thread);
        }
        go.countDown();
        for (Thread thread : threads) thread.join();
        check.same("sixteen racing requests start one run", 1, started.get());
        check.same("the other fifteen share one follow-up", 1, gate.finish());
    }

    private static byte[] chunk(String type, byte[] data) {
        java.nio.ByteBuffer out = java.nio.ByteBuffer.allocate(12 + data.length);
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(name);
        crc.update(data);
        out.putInt(data.length).put(name).put(data).putInt((int) crc.getValue());
        return out.array();
    }

    /** A structurally valid PNG that declares side x side pixels in a handful of bytes, like a decompression bomb. */
    private static byte[] claimedPng(int side) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] header = java.nio.ByteBuffer.allocate(13).putInt(side).putInt(side).put((byte) 1).put((byte) 0).put((byte) 0)
            .put((byte) 0).put((byte) 0).array();
        out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10}, 0, 8);
        for (byte[] part : new byte[][] {chunk("IHDR", header), chunk("IDAT", new byte[] {0x78, (byte) 0x9c, 0x63, 0, 0, 0, 2, 0, 1}), chunk("IEND", new byte[0])}) {
            out.write(part, 0, part.length);
        }
        return out.toByteArray();
    }

    private static void icons(Check check) throws Exception {
        Png.check(claimedPng(1024));
        check.fails("one pixel over the cap", "at most 1024", () -> Png.check(claimedPng(1025)));
        check.fails("billions of pixels in a few bytes", "at most 1024", () -> Png.check(claimedPng(65535)));
        check.ok("the bomb is tiny", claimedPng(65535).length < 100);
        check.fails("text with a PNG name", "Expected a PNG image", () -> Png.check("not an image at all".getBytes(StandardCharsets.US_ASCII)));
        check.fails("empty file", "Expected a PNG image", () -> Png.check(new byte[0]));
        check.fails("a JPEG", "Expected a PNG image", () -> Png.check(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 0, 16, 'J', 'F', 'I', 'F', 0}));
        Path root = Files.createTempDirectory("wave-icon-test");
        try {
            Map<String, byte[]> files = example();
            files.put(LIST_ICON, claimedPng(30000));
            files.put("icon.png", "GIF89a".getBytes(StandardCharsets.US_ASCII));
            SourceStore store = new SourceStore(root.toFile(), (url, known) -> new SourceStore.Update(HEAD_A, files));
            SourceStore.Source added = store.add("https://icons.test/source");
            store.refresh(added.id);
            Index.Source source = store.index().source(added.id);
            check.same("bomb icon dropped, package kept", null, store.index().get(APP_ID).icon);
            check.ok("package still listed", store.index().get(APP_ID) != null && source.packages.size() == 2);
            check.ok("both bad icons reported", source.issues.containsKey(LIST_ICON) && source.issues.containsKey("icon.png"));
            check.same("source icon dropped", null, source.icon);
            check.ok("a good icon beside them survives", store.index().get("messages_improved") == null || store.index().get("messages_improved").icon != null);
        } finally { erase(root); }
    }

    private static final String HEAD_C = "cccccccccccccccccccccccccccccccccccccccc";

    private static void damage(Check check) throws Exception {
        Path root = Files.createTempDirectory("wave-damage-test");
        try {
            Map<String, byte[]> files = example();
            AtomicReference<String> known = new AtomicReference<>("unset");
            SourceStore.Fetcher fetcher = (url, commit) -> { known.set(commit); return new SourceStore.Update(HEAD_A, commit == null ? files : null); };
            SourceStore store = new SourceStore(root.toFile(), fetcher);
            String id = store.add("https://damaged.test/one").id;
            String other = store.add("https://damaged.test/two").id;
            store.refresh(id);
            store.refresh(other);
            Path list = root.resolve("sources.json");
            byte[] saved = Files.readAllBytes(list);

            // A torn or garbled list must not lock the owner out of Wave.
            Files.write(list, Arrays.copyOf(saved, saved.length / 2));
            SourceStore truncated = new SourceStore(root.toFile(), fetcher);
            check.same("truncated list: default source only", 1, truncated.sources().size());
            check.ok("truncated list: reader told", truncated.notice() != null && truncated.notice().contains("could not read"));
            check.ok("truncated list: kept for inspection", Files.exists(root.resolve("sources/damaged.json")));
            check.ok("truncated list: moved aside", !Files.exists(list));
            check.ok("sources can be added again", truncated.add("https://damaged.test/three").url.endsWith("/three"));
            Files.write(list, new byte[] {0, 1, 2, (byte) 0xff});
            check.same("binary garbage", 1, new SourceStore(root.toFile(), fetcher).sources().size());
            Files.write(list, new byte[0]);
            check.same("empty file", 1, new SourceStore(root.toFile(), fetcher).sources().size());
            Files.write(list, "{\"url\":\"x\"}".getBytes(StandardCharsets.UTF_8));
            check.same("wrong JSON shape", 1, new SourceStore(root.toFile(), fetcher).sources().size());
            Files.write(list, new byte[2 * 1024 * 1024]);
            check.same("oversize file", 1, new SourceStore(root.toFile(), fetcher).sources().size());

            // One bad entry costs only that entry.
            Files.write(list, saved);
            List<Object> entries = Json.array(Json.parse(new String(saved, StandardCharsets.UTF_8)));
            List<Object> mixed = new ArrayList<>(entries);
            mixed.add(2, Json.map("url", "http://insecure.test/x", "name", "x", "commit", "", "lastRefresh", 0));
            mixed.add(Json.map("url", "https://ok.test/x", "name", "x", "commit", "not-a-commit", "lastRefresh", 0));
            Files.write(list, Json.write(mixed).getBytes(StandardCharsets.UTF_8));
            SourceStore partial = new SourceStore(root.toFile(), fetcher);
            check.same("good entries survive bad ones", 3, partial.sources().size());
            check.ok("partial list: reader told", partial.notice() != null && partial.notice().contains("2 of"));
            check.ok("partial list: original kept", Files.exists(root.resolve("sources/damaged.json")));
            SourceStore clean = new SourceStore(root.toFile(), fetcher);
            check.same("clean list has no notice", null, clean.notice());

            // A tree whose files do not match its digest (a torn write after a power cut) is never served as good data.
            // Folders of sources that were not in the list were swept; restoring the list refetches them in full.
            Files.write(list, saved);
            SourceStore restored = new SourceStore(root.toFile(), fetcher);
            check.same("restored list lost its trees, not its sources", 3, restored.sources().size());
            restored.refresh(id);
            check.same("missing tree is fetched in full", null, known.get());
            restored.refresh(other);
            Path trees = root.resolve("sources").resolve(id).resolve("tree");
            Path generation;
            try (DirectoryStream<Path> children = Files.newDirectoryStream(trees)) { generation = children.iterator().next(); }
            Path manifest = generation.resolve(LIST);
            byte[] original = Files.readAllBytes(manifest);
            Files.write(manifest, new byte[0]);
            SourceStore reopened = new SourceStore(root.toFile(), fetcher);
            Index.Source torn = reopened.index().source(id);
            check.ok("torn tree is reported", torn.issues.get("wave.json").contains("damaged"));
            check.same("torn tree offers no packages", 0, torn.packages.size());
            check.ok("the other source is fine", reopened.index().source(other).packages.size() == 2);
            reopened.refresh(id);
            check.same("damaged tree is fetched in full, not trusted as unchanged", null, known.get());
            check.same("repaired", 2, reopened.index().source(id).packages.size());
            check.ok("repaired file is the real one", Arrays.equals(original, Files.readAllBytes(generation.resolve(LIST))));
            Files.write(generation.resolve("extra.json"), new byte[] {'{', '}'});
            check.ok("an extra file also fails the check", new SourceStore(root.toFile(), fetcher).index().source(id).issues.containsKey("wave.json"));
            Files.delete(generation.resolve("extra.json"));
            Files.delete(generation.resolve(".sum"));
            check.same("tree from before digests is accepted", 2, new SourceStore(root.toFile(), fetcher).index().source(id).packages.size());

            // Leftovers of an interrupted run are cleared on start.
            Path stray = Files.createDirectories(root.resolve("sources").resolve("not-a-source").resolve("tree"));
            Files.createDirectories(trees.resolve("staging-123"));
            Files.createDirectories(trees.resolve(HEAD_C));
            Files.write(root.resolve("sources").resolve("catalog-1.tmp"), new byte[] {1});
            new SourceStore(root.toFile(), fetcher);
            check.ok("stray source folder removed", !Files.exists(stray.getParent()));
            check.ok("staging and old generations removed", !Files.exists(trees.resolve("staging-123")) && !Files.exists(trees.resolve(HEAD_C)));
            check.ok("temporary list removed", !Files.exists(root.resolve("sources").resolve("catalog-1.tmp")));
            check.ok("live generation kept", Files.isDirectory(generation));
            check.ok("damaged list kept through the sweep", Files.exists(root.resolve("sources/damaged.json")));
        } finally { erase(root); }
    }

    private static void generations(Check check) throws Exception {
        Path root = Files.createTempDirectory("wave-generation-test");
        try {
            Map<String, byte[]> files = example();
            AtomicReference<String> head = new AtomicReference<>(HEAD_A);
            CountDownLatch insideFetch = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicReference<Boolean> hold = new AtomicReference<>(false);
            SourceStore store = new SourceStore(root.toFile(), (url, known) -> {
                if (hold.get()) {
                    insideFetch.countDown();
                    try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("stuck"); }
                    catch (InterruptedException e) { throw new IOException("interrupted"); }
                }
                return new SourceStore.Update(head.get(), files);
            });
            String id = store.add("https://generations.test/source").id;
            store.refresh(id);
            Index.Entry first = store.index().get(APP_ID);
            head.set(HEAD_B);
            store.refresh(id);
            check.ok("a snapshot's icon outlives one switch", first.icon.isFile());
            head.set(HEAD_C);
            store.refresh(id);
            check.ok("and goes after the next", !first.icon.isFile());
            check.ok("current icon is there", store.index().get(APP_ID).icon.isFile());

            // Readers see whole generations only, while the head changes under them.
            java.util.concurrent.atomic.AtomicInteger bad = new java.util.concurrent.atomic.AtomicInteger(), reads = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
            List<Thread> readers = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                Thread reader = new Thread(() -> {
                    while (!stop.get()) {
                        Index.Source source = store.index().source(id);
                        reads.incrementAndGet();
                        if (source.manifest == null || source.packages.size() != 2 || !source.issues.isEmpty()) bad.incrementAndGet();
                    }
                });
                reader.start();
                readers.add(reader);
            }
            String[] heads = {HEAD_A, HEAD_B, HEAD_C};
            for (int i = 0; i < 30; i++) { head.set(i % 2 == 0 ? heads[0] : heads[1]); store.refresh(id); }
            stop.set(true);
            for (Thread reader : readers) reader.join();
            check.same("no reader saw a partial or damaged tree", 0, bad.get());
            check.ok("readers really ran", reads.get() > 10);

            // A source removed while its refresh is in flight does not come back and leaves no files.
            hold.set(true);
            head.set(HEAD_C);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread refreshing = new Thread(() -> { try { store.refresh(id); } catch (Throwable e) { failure.set(e); } });
            refreshing.start();
            check.ok("refresh is in its fetch", insideFetch.await(5, TimeUnit.SECONDS));
            store.remove(id);
            release.countDown();
            refreshing.join();
            check.ok("late refresh is refused", failure.get() instanceof IOException && failure.get().getMessage().contains("changed while refreshing"));
            check.same("source stays removed", 1, store.sources().size());
            check.ok("and left no files", !Files.exists(root.resolve("sources").resolve(id)));
            check.ok("the list on disk agrees", new SourceStore(root.toFile(), (u, k) -> null).sources().size() == 1);
            check.fails("refreshing what is gone", "removed", () -> store.refresh(id));
        } finally { erase(root); }
    }

    private static byte[] packageOf(byte[] template, String id, String name, String description, String padding) {
        return change(template, "id", id, "name", name, "description", description, "padding", padding);
    }

    private static void extremes(Check check) throws Exception {
        Path root = Files.createTempDirectory("wave-extreme-test");
        try {
            // A source with no packages at all.
            Map<String, byte[]> empty = new LinkedHashMap<>();
            empty.put("wave.json", example().get("wave.json"));
            AtomicReference<Map<String, byte[]>> tree = new AtomicReference<>(empty);
            SourceStore store = new SourceStore(root.toFile(), (url, known) -> new SourceStore.Update(HEAD_A, tree.get()));
            String id = store.add("https://extremes.test/empty").id;
            store.refresh(id);
            Index none = store.index();
            check.same("zero packages: no issues", 0, none.source(id).issues.size());
            check.same("zero packages: source readable", "Example Source", none.source(id).manifest.name);
            check.ok("zero packages: nothing anywhere", none.all().isEmpty() && none.sections().isEmpty() && none.search("anything").isEmpty());

            // A manifest of exactly 64 KiB reads; one byte more does not.
            byte[] template = example().get(LIST);
            byte[] big = packageOf(template, APP_ID, "Big", "Plain", "");
            StringBuilder padding = new StringBuilder();
            while (big.length + padding.length() < 65536) padding.append('x');
            byte[] exact = packageOf(template, APP_ID, "Big", "Plain", padding.toString());
            check.same("manifest at the limit", 65536, exact.length);
            check.same("limit manifest parses", "Big", PackageManifest.parse(exact).name);
            byte[] over = packageOf(template, APP_ID, "Big", "Plain", padding + "x");
            check.same("one byte over", 65537, over.length);
            check.fails("over the limit", "64 KiB", () -> PackageManifest.parse(over));
            Map<String, byte[]> limit = new LinkedHashMap<>(empty);
            limit.put(LIST, exact);
            failsBecause(check, "the store refuses an oversize file outright", "file size limit", () -> {
                Map<String, byte[]> refused = new LinkedHashMap<>(limit);
                refused.put("applications/oversized/package.json", over);
                tree.set(refused);
                store.refresh(id);
            });
            tree.set(limit);
            tree.set(limit);
            store.refresh(id);
            Index atLimit = store.index();
            check.same("exact-size package is served", "Big", atLimit.get(APP_ID).manifest.name);
            check.same("one over never reached the cache", null, atLimit.get("oversized"));

            // The search field on a large index: bounded results, bounded copies, tight memory budget.
            Map<String, byte[]> large = new LinkedHashMap<>(empty);
            for (int i = 0; i < 400; i++) {
                String name = String.format("pack-%03d", i);
                large.put("applications/" + name + "/package.json", packageOf(template, "com.large." + name, "Pack " + name,
                    i == 399 ? padding.substring(0, 3000) + "NeedleInALongDescription" : "Describes number " + i, ""));
            }
            tree.set(large);
            store.refresh(id);
            Index big400 = store.index();
            check.same("400 packages indexed", 400, big400.all().size());
            check.same("a one-letter query is capped", Search.MAX_RESULTS, big400.search("p").size());
            check.same("best matches come first", "Pack pack-000", big400.search("pack").get(0).manifest.name);
            check.same("a phrase deep in a long description is found, any case", "com.large.pack-399", big400.search("needleinalongdescription").get(0).manifest.id);
            check.same("no match", 0, big400.search("zzzzzz").size());
            SourceStore.Source info = store.sources().get(1);
            Path generation;
            try (DirectoryStream<Path> children = Files.newDirectoryStream(root.resolve("sources").resolve(id).resolve("tree"))) { generation = children.iterator().next(); }
            Index.Source limited = Index.read(info, generation, new Index.Budget(20_000));
            check.ok("a small budget loads some packages", limited.packages.size() > 0 && limited.packages.size() < 400);
            long refused = limited.issues.values().stream().filter(text -> text.startsWith("Not loaded")).count();
            check.same("every other package says why", 400 - limited.packages.size(), (int) refused);
            check.ok("the source itself still reads", limited.manifest != null);
        } finally { erase(root); }
    }

    /** Probe used by source fixture parity checks; exits 1 on checkout validation issues. */
    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("wave-lint-probe");
        int result = 0;
        try {
            SourceStore store = new SourceStore(temporary.toFile());
            Index.Source source = Index.read(store.sources().get(0), new File(args[0]).toPath());
            source.issues.forEach((path, problem) -> System.out.println(path + ": " + problem));
            if (!source.issues.isEmpty()) result = 1;
        } finally { erase(temporary); }
        System.exit(result);
    }

    private static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private static byte[] change(byte[] data, Object... pairs) {
        Map<String, Object> json = Json.object(Json.parse(new String(data, StandardCharsets.UTF_8)));
        json.putAll(Json.map(pairs));
        return utf8(Json.write(json));
    }

    private static Path spec() throws IOException {
        for (Path here = new File(".").getCanonicalFile().toPath(); here != null; here = here.getParent()) {
            if (Files.isDirectory(here.resolve("spec/example-source"))) return here.resolve("spec");
        }
        throw new IOException("Cannot locate the example source fixture");
    }

    private static byte[] variant(String file) throws IOException { return Files.readAllBytes(spec().resolve("source-fixtures").resolve(file)); }

    private static Map<String, byte[]> example() throws IOException {
        Path dir = spec().resolve("example-source");
        Map<String, byte[]> result = new LinkedHashMap<>();
        for (String name : new String[] {"wave.json", "icon.png", LIST, LIST_ICON,
                                       "system/messages-improved/package.json", "system/messages-improved/icon.png"}) {
            result.put(name, Files.readAllBytes(dir.resolve(name)));
        }
        return result;
    }

    private static void erase(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(path)) {
                for (Path child : files) erase(child);
            }
        }
        Files.deleteIfExists(path);
    }
}
