package com.wave.xp3.core.sources;

import com.wave.xp3.core.git.SmartHttp;
import com.wave.xp3.core.git.Tree;
import com.xp3.common.Json;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

/** Source order and active trees, stored in Wave's private files directory. One instance per directory. */
public final class SourceStore {
    public static final String DEFAULT_URL = "https://github.com/thooton/wave_repository.git";
    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");
    private static final int MAX_SOURCES = 128, MAX_STORE = 1024 * 1024, MAX_URL = 4096;
    private final Path list, root;
    private final Fetcher fetcher;
    private Map<String, Source> sources;
    /** Generations whose files were checked against their digest in this process (ours, or verified on first read). */
    private final Set<Path> verified = new HashSet<>();
    private String notice;

    interface Fetcher { Update fetch(String url, String known) throws IOException; }

    static final class Update {
        final String commit;
        final Map<String, byte[]> files; // null when unchanged
        Update(String commit, Map<String, byte[]> files) { this.commit = commit; this.files = files; }
    }

    public static final class Source {
        /** Stable storage key; the display URL never has a username or token. */
        public final String id, url, name, commit;
        public final boolean builtIn;
        /** Milliseconds since epoch of the last successful refresh, or zero. */
        public final long lastRefresh;
        private final String fetchUrl;

        private Source(String fetchUrl, String name, String commit, long lastRefresh) throws IOException {
            if (fetchUrl.length() > MAX_URL) throw new IOException("Source URL is too long");
            this.fetchUrl = fetchUrl;
            url = identity(fetchUrl);
            id = sourceId(url);
            builtIn = DEFAULT_URL.equals(url);
            this.name = name.isEmpty() ? (builtIn ? "Wave" : url) : name;
            this.commit = commit;
            this.lastRefresh = lastRefresh;
        }
    }

    /** Fetched metadata for the trust dialog. Nothing is persisted until add(Preview). */
    public static final class Preview {
        public final String url;
        public final SourceManifest manifest;
        public final int packageCount;
        private final Source source;
        private final Map<String, byte[]> files;

        private Preview(Source source, SourceManifest manifest, Map<String, byte[]> files) {
            this.source = source;
            this.url = source.url;
            this.manifest = manifest;
            this.files = files;
            Set<String> packages = new HashSet<>();
            for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                if (!entry.getKey().endsWith("/package.json")) continue;
                try { packages.add(PackageManifest.parse(entry.getValue()).id); }
                catch (IllegalArgumentException ignored) { /* Broken package, not a broken source. */ }
            }
            packageCount = packages.size();
        }

        /** Optional source icon for the trust dialog. No file is created before approval. */
        public byte[] icon() {
            byte[] icon = files.get("icon.png");
            if (icon == null) return null;
            try { Png.check(icon); return icon.clone(); }
            catch (IllegalArgumentException e) { return null; }
        }
    }

    public static final class Refresh {
        public final Source source;
        public final boolean changed;
        /** Null on success. The cached tree is retained on failure. */
        public final String error;
        private Refresh(Source source, boolean changed, String error) {
            this.source = source;
            this.changed = changed;
            this.error = error;
        }
    }

    public SourceStore(File filesDir) throws IOException {
        this(filesDir, (url, known) -> {
            SmartHttp.Result result = SmartHttp.fetchTree(url, known);
            return new Update(result.commit, result.unchanged ? null : result.files);
        });
    }

    SourceStore(File filesDir, Fetcher fetcher) throws IOException {
        list = filesDir.toPath().resolve("sources.json");
        root = filesDir.toPath().resolve("sources");
        this.fetcher = fetcher;
        sources = load();
        sweep();
    }

    /** Set when the saved source list could not be read as a whole and part or all of it was set aside, else null. */
    public synchronized String notice() { return notice; }

    public synchronized List<Source> sources() {
        return Collections.unmodifiableList(new ArrayList<>(sources.values()));
    }

    /** Fetches an untrusted source for the confirmation dialog, without changing disk or the index. */
    public Preview preview(String url) throws IOException {
        Source prospective = new Source(url, "", "", 0);
        synchronized (this) { checkNew(prospective); }
        Update update = fetcher.fetch(prospective.fetchUrl, null);
        if (update.files == null || !COMMIT.matcher(update.commit).matches()) throw new IOException("Source preview did not return a tree");
        SourceManifest header = header(update);
        return new Preview(new Source(url, header.name, update.commit, System.currentTimeMillis()), header, update.files);
    }

    /** Persists the exact fetched tree only after the user accepts the root-trust dialog. */
    public synchronized Source add(Preview preview) throws IOException {
        Source source = preview.source;
        checkNew(source);
        publish(null, source, preview.files);
        return source;
    }

    /** Persists a trusted URL; call refresh on a worker to check it and populate its tree. */
    public synchronized Source add(String url) throws IOException {
        Source source = new Source(url, "", "", 0);
        checkNew(source);
        replace(source);
        return source;
    }

    private void checkNew(Source source) throws IOException {
        if (sources.containsKey(source.id)) throw new IOException("That source is already added");
        if (sources.size() >= MAX_SOURCES) throw new IOException("Too many Wave sources");
    }

    public synchronized void remove(String id) throws IOException {
        Source source = require(id);
        if (source.builtIn) throw new IOException("The default Wave source cannot be removed");
        Map<String, Source> next = new LinkedHashMap<>(sources);
        next.remove(id);
        save(next);
        eraseQuietly(root.resolve(id));
    }

    /** Atomically publishes a checked tree, or only updates the refresh time when HEAD is unchanged. */
    public Refresh refresh(String id) throws IOException {
        Source before;
        String known;
        synchronized (this) {
            before = require(id);
            Path tree = active(before);
            known = tree != null && intact(tree) ? before.commit : null;
        }
        Update update = fetcher.fetch(before.fetchUrl, known);
        if (!COMMIT.matcher(update.commit).matches()) throw new IOException("Invalid source HEAD");
        synchronized (this) {
            if (sources.get(id) != before) throw new IOException("The source changed during refresh");
            if (update.files == null) {
                if (!update.commit.equals(known)) throw new IOException("Source refresh did not return a tree");
                Source next = new Source(before.fetchUrl, before.name, before.commit, System.currentTimeMillis());
                replace(next);
                return new Refresh(next, false, null);
            }
            SourceManifest header = header(update);
            Source next = new Source(before.fetchUrl, header.name, update.commit, System.currentTimeMillis());
            publish(before, next, update.files);
            return new Refresh(next, true, null);
        }
    }

    private static SourceManifest header(Update update) throws IOException {
        try { return SourceManifest.parse(update.files.get("wave.json")); }
        catch (IllegalArgumentException e) { throw new IOException(e.getMessage()); }
    }

    /** Refreshes sources independently with two bounded workers; results remain in source order. */
    public List<Refresh> refreshAll() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Source> order = sources();
            List<Future<Refresh>> jobs = new ArrayList<>();
            for (Source source : order) jobs.add(pool.submit(() -> {
                try { return refresh(source.id); }
                catch (IOException e) { return new Refresh(source, false, e.getMessage()); }
            }));
            List<Refresh> results = new ArrayList<>();
            for (int i = 0; i < jobs.size(); i++) {
                try { results.add(jobs.get(i).get()); }
                catch (ExecutionException e) { results.add(new Refresh(order.get(i), false, "Could not refresh this source")); }
            }
            return Collections.unmodifiableList(results);
        } finally { pool.shutdownNow(); }
    }

    /** Immutable package/index metadata rebuilt from the currently committed cached trees; no network. */
    public synchronized Index index() {
        List<Index.Source> views = new ArrayList<>();
        Index.Budget budget = new Index.Budget(Index.MAX_MANIFEST_BYTES);
        for (Source source : sources.values()) {
            Path tree = active(source);
            views.add(tree != null && Files.isDirectory(tree, LinkOption.NOFOLLOW_LINKS) && !intact(tree) ?
                Index.damaged(source) : Index.read(source, tree, budget));
        }
        return new Index(views);
    }

    private Path active(Source source) {
        return source.commit.isEmpty() ? null : root.resolve(source.id).resolve("tree").resolve(source.commit);
    }

    private void publish(Source before, Source next, Map<String, byte[]> files) throws IOException {
        Path destination = active(next);
        Path old = before == null ? null : active(before);
        Path parent = destination.getParent();
        Files.createDirectories(parent);
        Path staging = Files.createTempDirectory(parent, "staging-");
        try {
            for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                String relative = entry.getKey();
                String[] parts = relative.split("/", -1);
                boolean allowed = parts.length == 1 && ("wave.json".equals(relative) || "icon.png".equals(relative)) ||
                    parts.length == 3 && Fields.FOLDER.matcher(parts[0]).matches() && Fields.FOLDER.matcher(parts[1]).matches() &&
                    ("package.json".equals(parts[2]) || "icon.png".equals(parts[2]));
                if (!allowed || entry.getValue().length > Tree.MAX_FILE) throw new IOException("Invalid cached source path or file size");
                Path path = staging.resolve(relative);
                Files.createDirectories(path.getParent());
                // No per-file sync: a thousand fsyncs take minutes on flash. The digest below makes a torn write detectable.
                try (FileOutputStream out = new FileOutputStream(path.toFile())) { out.write(entry.getValue()); }
            }
            MessageDigest sum = digest();
            List<String> names = new ArrayList<>(files.keySet());
            Collections.sort(names);
            for (String name : names) feed(sum, name, files.get(name));
            write(staging.resolve(SUM), hex(sum.digest()).getBytes(StandardCharsets.UTF_8));
            // Staging is wholly written before any reader can see it. A failed list rename leaves the old generation active.
            // The only same-HEAD rebuild is repair of an already missing/corrupt snapshot.
            erase(destination);
            verified.remove(destination);
            Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
            replace(next);
            verified.add(destination);
        } finally { eraseQuietly(staging); }
        // The previous generation stays for one more switch: a snapshot still on screen holds paths into it for its icons.
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent)) {
            for (Path child : stream) if (!child.equals(destination) && !child.equals(old)) { eraseQuietly(child); verified.remove(child); }
        } catch (IOException ignored) { /* The live tree is already published. */ }
    }

    private static final String SUM = ".sum", DAMAGED = "damaged.json";

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    private static void feed(MessageDigest sum, String name, byte[] data) {
        sum.update(name.getBytes(StandardCharsets.UTF_8));
        sum.update((byte) 0);
        sum.update(java.nio.ByteBuffer.allocate(8).putLong(data.length).array());
        sum.update(data);
    }

    private static String hex(byte[] hash) {
        StringBuilder out = new StringBuilder(hash.length * 2);
        for (byte b : hash) { out.append(Character.forDigit((b & 255) >>> 4, 16)); out.append(Character.forDigit(b & 15, 16)); }
        return out.toString();
    }

    /** True when the generation holds exactly the files its digest was made from. Checked once per process. */
    private boolean intact(Path tree) {
        if (verified.contains(tree)) return true;
        try {
            Map<String, Path> found = new java.util.TreeMap<>();
            Path marker = tree.resolve(SUM);
            // A generation without a digest was written by an earlier Wave, which synced every file; trust it.
            if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) return Files.isRegularFile(tree.resolve("wave.json"), LinkOption.NOFOLLOW_LINKS);
            if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) return false;
            try (DirectoryStream<Path> top = Files.newDirectoryStream(tree)) {
                for (Path a : top) {
                    String name = a.getFileName().toString();
                    if (SUM.equals(name)) continue;
                    if (Files.isRegularFile(a, LinkOption.NOFOLLOW_LINKS)) found.put(name, a);
                    else if (Files.isDirectory(a, LinkOption.NOFOLLOW_LINKS)) {
                        try (DirectoryStream<Path> middle = Files.newDirectoryStream(a)) {
                            for (Path b : middle) {
                                if (!Files.isDirectory(b, LinkOption.NOFOLLOW_LINKS)) return false;
                                try (DirectoryStream<Path> leaves = Files.newDirectoryStream(b)) {
                                    for (Path c : leaves) {
                                        if (!Files.isRegularFile(c, LinkOption.NOFOLLOW_LINKS)) return false;
                                        found.put(name + "/" + b.getFileName() + "/" + c.getFileName(), c);
                                    }
                                }
                            }
                        }
                    } else return false;
                }
            }
            MessageDigest sum = digest();
            for (Map.Entry<String, Path> file : found.entrySet()) feed(sum, file.getKey(), readBounded(file.getValue(), Tree.MAX_FILE));
            String expected = new String(readBounded(marker, 128), StandardCharsets.UTF_8);
            if (!expected.equals(hex(sum.digest()))) return false;
            verified.add(tree);
            return true;
        } catch (IOException | RuntimeException e) { return false; }
    }

    /** Removes what an interrupted run left: staging folders, older generations, folders of removed sources. */
    private void sweep() {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (DirectoryStream<Path> children = Files.newDirectoryStream(root)) {
            for (Path child : children) {
                if (DAMAGED.equals(child.getFileName().toString())) continue;
                Source source = sources.get(child.getFileName().toString());
                if (source == null) { eraseQuietly(child); continue; }
                Path trees = child.resolve("tree");
                Path live = active(source);
                if (!Files.isDirectory(trees, LinkOption.NOFOLLOW_LINKS)) continue;
                try (DirectoryStream<Path> generations = Files.newDirectoryStream(trees)) {
                    for (Path generation : generations) if (!generation.equals(live)) eraseQuietly(generation);
                }
            }
        } catch (IOException ignored) { /* Leftovers are harmless; the next switch cleans them. */ }
    }

    private static void write(Path path, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(path.toFile())) {
            out.write(data);
            out.getFD().sync();
        }
    }

    private static void erase(Path path) throws IOException {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(path)) {
                for (Path child : entries) erase(child);
            }
        }
        Files.deleteIfExists(path);
    }

    private static void eraseQuietly(Path path) {
        try { erase(path); } catch (IOException ignored) { /* An orphan is not an active source. */ }
    }

    private Source require(String id) throws IOException {
        Source source = sources.get(id);
        if (source == null) throw new IOException("That source was removed");
        return source;
    }

    private void replace(Source source) throws IOException {
        Map<String, Source> next = new LinkedHashMap<>(sources);
        next.put(source.id, source);
        save(next);
    }

    private Map<String, Source> load() throws IOException {
        Source builtIn = new Source(DEFAULT_URL, "Wave", "", 0);
        Map<String, Source> result = new LinkedHashMap<>();
        result.put(builtIn.id, builtIn);
        if (!Files.exists(list, LinkOption.NOFOLLOW_LINKS)) return result;
        List<Object> entries;
        try { entries = Json.array(Json.parse(new String(readBounded(list, MAX_STORE), StandardCharsets.UTF_8))); }
        catch (IOException | RuntimeException e) {
            // Truncated, corrupt or not ours: keep it for the owner to inspect and start from the default source.
            setAside(true);
            notice = "Wave could not read its list of sources, so only the default source is shown.";
            return result;
        }
        int skipped = Math.max(0, entries.size() - MAX_SOURCES);
        for (Object item : entries.subList(0, entries.size() - skipped)) {
            try {
                Map<String, Object> values = Json.object(item);
                String commit = (String) values.get("commit");
                long lastRefresh = ((java.math.BigDecimal) values.get("lastRefresh")).longValueExact();
                if (lastRefresh < 0 || !commit.isEmpty() && !COMMIT.matcher(commit).matches()) throw new IllegalArgumentException();
                Source source = new Source((String) values.get("url"), (String) values.get("name"), commit, lastRefresh);
                Source before = result.get(source.id);
                if (before != null && !source.builtIn) throw new IllegalArgumentException();
                result.put(source.id, source);
            } catch (IOException | RuntimeException e) { skipped++; }
        }
        if (skipped > 0) {
            setAside(false);
            notice = skipped == 1 ? "Wave could not read one of its saved sources and left it out." :
                "Wave could not read " + skipped + " of its saved sources and left them out.";
            // Write the usable part back, so this is told once; the original stays in damaged.json.
            try { save(result); } catch (IOException ignored) { /* The next change saves it. */ }
        }
        return result;
    }

    /** Keeps the unreadable list as sources/damaged.json (inside the folder the backup rules exclude), moving it when nothing could be used from it. */
    private void setAside(boolean move) {
        Path copy = root.resolve(DAMAGED);
        try {
            Files.createDirectories(root);
            if (move) Files.move(list, copy, StandardCopyOption.REPLACE_EXISTING);
            else Files.copy(list, copy, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) { /* Reading continues without it; nothing is lost that was readable. */ }
    }

    private void save(Map<String, Source> next) throws IOException {
        List<Object> entries = new ArrayList<>();
        for (Source source : next.values()) entries.add(Json.map("url", source.fetchUrl, "name", source.name,
            "commit", source.commit, "lastRefresh", source.lastRefresh));
        byte[] data = Json.write(entries).getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_STORE) throw new IOException("Wave source list is too large");
        // Keep the credential-bearing temporary file inside the backup-excluded sources directory.
        Files.createDirectories(root);
        Path temporary = Files.createTempFile(root, "catalog-", ".tmp");
        try {
            // App-private directory; also keep this credential-bearing file owner-only.
            File file = temporary.toFile();
            file.setReadable(false, false);
            file.setReadable(true, true);
            file.setWritable(false, false);
            file.setWritable(true, true);
            write(temporary, data);
            Files.move(temporary, list, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            sources = next;
        } finally { eraseQuietly(temporary); }
    }

    static byte[] readBounded(Path path, int limit) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > limit) {
            throw new IOException("Expected a regular file of at most " + (limit / 1024) + " KiB");
        }
        try (InputStream in = Files.newInputStream(path)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) != -1;) {
                if (n > limit - out.size()) throw new IOException("File exceeds " + (limit / 1024) + " KiB");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static String identity(String url) throws IOException {
        URI uri = SmartHttp.repositoryUri(url);
        String clean = SmartHttp.publicUrl(url);
        String path = URI.create(clean).getRawPath();
        int port = uri.getPort();
        String identity = "https://" + uri.getHost().toLowerCase(Locale.ROOT) + (port >= 0 && port != 443 ? ":" + port : "") + path;
        return DEFAULT_URL.equals(identity + ".git") ? DEFAULT_URL : identity;
    }

    private static String sourceId(String url) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte b : hash) {
                out.append(Character.forDigit((b & 255) >>> 4, 16));
                out.append(Character.forDigit(b & 15, 16));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}
