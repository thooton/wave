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
            check.fails("cannot remove default", "cannot be removed", () -> store.remove(store.sources().get(0).id));
            check.fails("default alias is protected", "already added", () -> store.add(SourceStore.DEFAULT_URL.substring(0, SourceStore.DEFAULT_URL.length() - 4)));
            SourceStore.Preview preview = store.preview("https://sample.test/preview");
            check.same("preview identifies source", "Example Source", preview.manifest.name);
            check.same("preview counts working packages", 2, preview.packageCount);
            check.same("preview is not persisted", 1, store.sources().size());
            SourceStore.Source approved = store.add(preview);
            check.same("approval uses fetched tree", HEAD_A, store.index().source(approved.id).info.commit);
            check.fails("preview cannot be approved twice", "already added", () -> store.add(preview));
            store.remove(approved.id);
            check.fails("HTTPS only", "HTTPS", () -> store.add("http://example.org/source"));
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
            check.fails("failed validation leaves active tree", "wave.json", () -> store.refresh(added.id));
            check.same("old HEAD survives validation failure", HEAD_A, store.index().source(added.id).info.commit);
            invalid.put("wave.json", valid.get("wave.json"));
            invalid.put("../../escape.png", new byte[0]);
            check.fails("failed staging leaves active tree", "Invalid cached source path", () -> store.refresh(added.id));
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
                    throw new IOException("default unavailable");
                }
                otherStarted.countDown();
                return new SourceStore.Update(HEAD_A, tree);
            });
            SourceStore.Source second = store.add("https://sample.org/source");
            List<SourceStore.Refresh> results = store.refreshAll();
            check.same("failure reported independently", "default unavailable", results.get(0).error);
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
        check.same("SDK mismatch", "Requires Android SDK 27 or later", Compat.of(pkg, 1, new Compat.Phone("argon", "XP3800", 26, "tested", 1)).reason);
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
