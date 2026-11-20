package com.wave.xp3.core.sources;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The data layer at the documented limits, on the desktop: serves the trees that
 * tools/qa/stress/sources.py generated through a fake fetcher (no network), refreshes
 * them all, reads the index back as a new process would, and searches.
 *
 *   StressBench TREES_DIR SOURCES FILES_DIR
 *
 * Prints one line per measurement. The files dir is left behind (it is a normal Wave
 * files dir, so the app module's AppBench and a phone can read it).
 */
public final class StressBench {
    public static void main(String[] args) throws Exception {
        Path trees = java.nio.file.Paths.get(args[0]).toAbsolutePath();
        File files = new File(args[1]).getAbsoluteFile();
        String[] steps = args[2].split(",");
        Map<String, Path> byUrl = new HashMap<>();
        for (int i = 0; i < 200; i++) byUrl.put(url(i), trees.resolve(String.format("s%03d", i)));
        SourceStore.Fetcher fetcher = (url, known) -> {
            Path tree = byUrl.get(url);
            if (tree == null) throw new IOException("no such source " + url);
            String commit = String.format("%040x", Math.abs(url.hashCode()) + 1);
            return commit.equals(known) ? new SourceStore.Update(commit, null) : new SourceStore.Update(commit, read(tree));
        };
        System.out.println("load average " + new String(Files.readAllBytes(java.nio.file.Paths.get("/proc/loadavg"))).trim());
        for (String step : steps) {
            int count = Integer.parseInt(step);
            for (String mode : new String[]{"each", "batch"}) {
                deleteAll(files.toPath());
                Files.createDirectories(files.toPath());
                SourceStore store = new SourceStore(files, fetcher);
                for (int i = 0; i < count; i++) store.add(url(i));
                List<String> ids = new ArrayList<>();
                for (SourceStore.Source s : store.sources()) if (!s.builtIn) ids.add(s.id);
                long start = System.nanoTime(), indexMs = 0, worst = 0;
                int reads = 0;
                if (mode.equals("each")) {
                    // What SourceController did: read the index again after every source's refresh.
                    for (String id : ids) {
                        store.refresh(id);
                        long t = System.nanoTime();
                        store.index();
                        long took = ms(t);
                        indexMs += took; reads++; worst = Math.max(worst, took);
                    }
                } else {
                    for (String id : ids) store.refresh(id);
                    long t = System.nanoTime();
                    store.index();
                    indexMs = ms(t); reads = 1; worst = indexMs;
                }
                Index index = store.index();
                System.out.printf("%3d sources, %-5s: %7d ms in all, %7d ms of it in %d index() calls (slowest %d ms); %d packages%n",
                    count, mode, ms(start), indexMs, reads, worst, index.all().size());
                if (step.equals(steps[steps.length - 1]) && mode.equals("batch")) full(store, files, fetcher, index);
            }
        }
    }

    private static void full(SourceStore store, File files, SourceStore.Fetcher fetcher, Index index) throws Exception {
        report(index);
        System.out.printf("heap with the index held: %d MB%n", usedMb());
        try { store.add(url(150)); System.out.println("one more source: ACCEPTED (store holds " + store.sources().size() + ")"); }
        catch (IOException e) { System.out.println("one more source (store holds " + store.sources().size() + "): " + e.getMessage()); }
        SourceStore again = new SourceStore(files, fetcher);
        long start = System.nanoTime();
        index = again.index();
        System.out.printf("index() first in a new process (digests checked): %d ms%n", ms(start));
        start = System.nanoTime();
        index = again.index();
        System.out.printf("index() again, nothing changed: %d ms%n", ms(start));
        for (String query : new String[]{"a", "note", "clock", "stress maker", "zzzzzz", "wallet scan"}) {
            long best = Long.MAX_VALUE;
            int n = 0;
            for (int rep = 0; rep < 5; rep++) {
                start = System.nanoTime();
                n = index.search(query).size();
                best = Math.min(best, System.nanoTime() - start);
            }
            System.out.printf("search \"%s\": %d results in %.1f ms%n", query, n, best / 1e6);
        }
    }

    private static void report(Index index) {
        int packages = index.all().size(), issues = 0, notLoaded = 0;
        for (Index.Source s : index.sources()) {
            issues += s.issues.size();
            for (String issue : s.issues.values()) if (issue.startsWith("Not loaded")) notLoaded++;
        }
        System.out.printf("index: %d sources, %d packages, %d issues (%d not loaded for the 16 MiB budget)%n",
            index.sources().size(), packages, issues, notLoaded);
    }

    private static String url(int i) { return String.format(Locale.ROOT, "https://localhost:4484/s%03d.git", i); }

    private static Map<String, byte[]> read(Path tree) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(tree)) {
            for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile).sorted()::iterator) {
                String rel = tree.relativize(p).toString();
                if (rel.equals("wave.json") || rel.equals("icon.png") || rel.endsWith("/package.json") || rel.endsWith("/icon.png")) {
                    out.put(rel, Files.readAllBytes(p));
                }
            }
        }
        return out;
    }

    private static long ms(long start) { return (System.nanoTime() - start) / 1_000_000; }

    private static long usedMb() {
        System.gc();
        return (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) >> 20;
    }

    private static void deleteAll(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path p : (Iterable<Path>) walk.sorted(java.util.Comparator.reverseOrder())::iterator) Files.delete(p);
        }
    }
}
