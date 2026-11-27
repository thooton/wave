package com.wave.xp3.core.sources;

import com.wave.xp3.core.Version;
import com.wave.xp3.core.git.Tree;
import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** A cached catalog and stable snapshot of the winning package candidates. */
public final class Index {
    private static final Comparator<Entry> BY_NAME = Comparator.comparing((Entry e) -> e.manifest.name,
        String.CASE_INSENSITIVE_ORDER).thenComparing(e -> e.manifest.id);

    public static final class Entry {
        public final SourceStore.Source source;
        public final SourceManifest sourceManifest;
        public final PackageManifest manifest;
        public final String section, folder;
        public final File icon;

        private Entry(SourceStore.Source source, SourceManifest sourceManifest, PackageManifest manifest,
                      String section, String folder, File icon) {
            this.source = source;
            this.sourceManifest = sourceManifest;
            this.manifest = manifest;
            this.section = section;
            this.folder = folder;
            this.icon = icon;
        }
    }

    public static final class Section {
        public final String folder, name;
        public final List<Entry> packages;
        private Section(String folder, String name, List<Entry> packages) {
            this.folder = folder;
            this.name = name;
            this.packages = sorted(packages);
        }
        public int count() { return packages.size(); }
    }

    /**
     * A package (or icon) the source's page lists as not read, with why in the
     * reader's words: for publishers, so field names are fine, raw errors aren't.
     */
    public static final class Problem {
        /** The package's folder, or "" for the source's own icon. */
        public final String folder, reason;
        /** An icon left out: its package is still listed. */
        public final boolean icon;
        Problem(String folder, String reason, boolean icon) {
            this.folder = folder;
            this.reason = reason;
            this.icon = icon;
        }
    }

    /** The most problems a source keeps to list; a hostile source can't make the index or the screen grow past it. */
    public static final int MAX_PROBLEMS = 200;

    /** Problems as they are found, kept up to the cap and counted past it. */
    static final class Problems {
        private final List<Problem> kept = new ArrayList<>();
        int more, packages;
        void add(Problem p) {
            if (!p.icon) packages++;
            if (kept.size() < MAX_PROBLEMS) kept.add(p);
            else more++;
        }
        List<Problem> sorted() {
            List<Problem> result = new ArrayList<>(kept);
            result.sort(Comparator.comparing((Problem p) -> p.icon).thenComparing(p -> p.folder));
            return Collections.unmodifiableList(result);
        }
    }

    public static final class Source {
        public final SourceStore.Source info;
        /** Null if the source has not refreshed or its cached header is invalid. */
        public final SourceManifest manifest;
        public final File icon;
        public final List<Section> sections;
        public final List<Entry> packages;
        /** Cached paths and their validation/read errors; other packages are still usable. */
        public final Map<String, String> issues;
        /** Packages left out, then icons ignored, in folder order: at most {@link #MAX_PROBLEMS}. */
        public final List<Problem> problems;
        /** Problems past the cap, not listed; and every package left out, listed or not. */
        public final int moreProblems, unreadable;
        /** Plain words for the source row when its own details can't be used, else null. */
        public final String headerProblem;

        private Source(SourceStore.Source info, SourceManifest manifest, File icon, List<Section> sections,
                       List<Entry> packages, Map<String, String> issues, Problems problems, String headerProblem) {
            this.info = info;
            this.manifest = manifest;
            this.icon = icon;
            this.sections = Collections.unmodifiableList(sections);
            this.packages = sorted(packages);
            this.issues = Collections.unmodifiableMap(issues);
            this.problems = problems.sorted();
            this.moreProblems = problems.more;
            this.unreadable = problems.packages;
            this.headerProblem = headerProblem;
        }
        /** How many packages the source has that Wave couldn't read (icons don't count). */
        public int unreadable() { return unreadable; }
        public List<Entry> section(String folder) {
            for (Section section : sections) if (section.folder.equals(folder)) return section.packages;
            return Collections.emptyList();
        }
    }

    private final Map<String, Source> sources;
    private final Map<String, Entry> byId;
    private final List<Entry> all;
    private final List<Section> sections;
    private final Search search;

    Index(List<Source> orderedSources) {
        Map<String, Source> order = new LinkedHashMap<>();
        Map<String, Entry> winners = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();
        for (Source source : orderedSources) {
            order.put(source.info.id, source);
            if (source.manifest != null) source.manifest.sections.forEach(labels::putIfAbsent);
            for (Entry entry : source.packages) {
                Entry other = winners.get(entry.manifest.id);
                if (other == null || Version.compare(entry.manifest.version, other.manifest.version) > 0) {
                    winners.put(entry.manifest.id, entry);
                }
            }
        }
        sources = Collections.unmodifiableMap(order);
        byId = Collections.unmodifiableMap(winners);
        all = sorted(new ArrayList<>(winners.values()));
        sections = Collections.unmodifiableList(sections(all, labels));
        search = new Search(all);
    }

    public List<Source> sources() { return Collections.unmodifiableList(new ArrayList<>(sources.values())); }
    public Source source(String id) { return sources.get(id); }
    public Entry get(String id) { return byId.get(id); }
    public List<Entry> all() { return all; }
    public List<Section> sections() { return sections; }
    public List<Entry> section(String folder) {
        for (Section section : sections) if (section.folder.equals(folder)) return section.packages;
        return Collections.emptyList();
    }
    public List<Entry> search(String query) { return search.find(query); }

    /** Manifest bytes all sources together may keep in memory (a source's own tree is already limited to 16 MiB). */
    static final long MAX_MANIFEST_BYTES = 16L * 1024 * 1024;

    /** What is left of the memory a whole index may use for manifests; the sources read first are served first. */
    static final class Budget {
        private long left;
        private boolean refused;
        Budget(long bytes) { left = bytes; }
        boolean take(long bytes) {
            if (bytes > left) { left = 0; refused = true; return false; }
            left -= bytes;
            return true;
        }
        long left() { return left; }
        /** Whether a package was left out for want of room (the budget stays empty from then on). */
        boolean refused() { return refused; }
        /** Takes what a source read earlier would take, without reading it again. */
        void skip(long leftAfter, boolean wasRefused) { left = leftAfter; refused = refused || wasRefused; }
    }

    /** A source whose saved copy failed its integrity check: shown with the reason and no packages until it is refreshed. */
    static Source damaged(SourceStore.Source info) {
        Map<String, String> issues = new LinkedHashMap<>();
        String says = "The saved copy of this source is damaged. Refresh it.";
        issues.put("wave.json", says);
        return new Source(info, null, null, Collections.emptyList(), Collections.emptyList(), issues,
            new Problems(), says);
    }

    /** Reads a checkout or a cached source directory. Useful for offline validation, not Git fetching. */
    public static Source read(SourceStore.Source info, Path tree) {
        return read(info, tree, new Budget(MAX_MANIFEST_BYTES));
    }

    static Source read(SourceStore.Source info, Path tree, Budget budget) {
        Map<String, String> issues = new LinkedHashMap<>();
        List<Entry> packages = new ArrayList<>();
        Problems problems = new Problems();
        SourceManifest header = null;
        File icon = null;
        String headerProblem = null;
        if (tree != null) {
            List<String> dropped = dropped(tree);
            try { header = SourceManifest.parse(SourceStore.readBounded(tree.resolve("wave.json"), Tree.MAX_FILE)); }
            catch (IOException | IllegalArgumentException e) {
                boolean big = dropped.contains("wave.json");
                issues.put("wave.json", big ? "wave.json is over 64 KB" : e.getMessage());
                headerProblem = big ? OWN_DETAILS_BIG : e instanceof IllegalArgumentException
                    ? ownDetails(e.getMessage()) : com.wave.xp3.core.git.SourceFailure.Kind.UNREADABLE.says;
            }
            icon = icon(tree, tree.resolve("icon.png"), issues, problems, "");
            if (header != null) readPackages(info, tree, header, packages, issues, problems, budget, dropped);
        }
        return new Source(info, header, icon, sections(packages, header == null ? Collections.emptyMap() : header.sections),
            packages, issues, problems, headerProblem);
    }

    static final String OWN_DETAILS_BIG = "The source details are larger than 64 KB.";
    private static final java.util.regex.Pattern FIELD_PROBLEM =
        java.util.regex.Pattern.compile("[a-z][A-Za-z0-9_.\\[\\]]* (is required|must .+|contains .+)");

    /**
     * The source row's words for a wave.json the reader refused: the field to
     * fix when it's one field, never the file's name or the parser's terms.
     */
    public static String ownDetails(String readerText) {
        String reason = reason(readerText, "wave.json");
        return FIELD_PROBLEM.matcher(reason).matches() ? "The source details are not valid: " + reason + "."
            : "Wave can't read the source details.";
    }

    /** At most {@code max} of the lines, then "and N more" for the rest of {@code total}. */
    public static List<String> capped(List<String> lines, int max, int total) {
        List<String> result = new ArrayList<>(lines.subList(0, Math.min(max, lines.size())));
        if (total > result.size()) result.add("and " + (total - result.size()) + " more");
        return Collections.unmodifiableList(result);
    }

    /**
     * The source page's two lists, as "folder: reason" lines of at most
     * {@code max} each, then "and N more": packages left out, and icons ignored.
     */
    public static List<List<String>> pageLines(Source s, int max) {
        List<String> packages = new ArrayList<>(), icons = new ArrayList<>();
        for (Problem p : s.problems) (p.icon ? icons : packages).add((p.folder.isEmpty() ? "Source icon" : p.folder) + ": " + p.reason);
        int iconTotal = icons.size() + s.moreProblems - (s.unreadable - packages.size());
        List<List<String>> result = new ArrayList<>();
        result.add(capped(packages, max, s.unreadable));
        result.add(capped(icons, max, iconTotal));
        return Collections.unmodifiableList(result);
    }

    /** The format's files the fetch left out for size (see {@link Tree#DROPPED}); none if the record is missing or torn. */
    private static List<String> dropped(Path tree) {
        Path record = tree.resolve(Tree.DROPPED);
        if (!Files.isRegularFile(record, LinkOption.NOFOLLOW_LINKS)) return Collections.emptyList();
        try { return Tree.dropped(SourceStore.readBounded(record, Tree.MAX_FILE)); }
        catch (IOException | RuntimeException e) { return Collections.emptyList(); }
    }

    /** A reader's message without the file name it starts with, and sizes in plain units. */
    static String reason(String readerText, String file) {
        String text = readerText == null ? "" : readerText;
        if (text.startsWith(file + ": ")) text = text.substring(file.length() + 2);
        else if (text.startsWith(file + " ")) text = text.substring(file.length() + 1);
        if (text.startsWith("exceeds 64 KiB")) text = "The file is larger than 64 KB";
        else if (text.startsWith("is not UTF-8")) text = "The file isn't UTF-8 text";
        else if (text.startsWith("PNG icon ")) text = "The icon " + text.substring(9);
        else if (text.contains("PNG")) text = "The icon isn't a readable PNG image";
        return text.isEmpty() ? "Wave can't read the file" : text;
    }

    private static void readPackages(SourceStore.Source info, Path tree, SourceManifest header, List<Entry> packages,
                                     Map<String, String> issues, Problems problems, Budget budget,
                                     List<String> dropped) {
        Map<String, String> seen = new LinkedHashMap<>();
        for (String path : dropped) {
            String[] parts = path.split("/");
            if (parts.length != 3) continue;
            issues.put(path, "Over 64 KB");
            problems.add(new Problem(parts[1], "The file is larger than 64 KB", "icon.png".equals(parts[2])));
        }
        try {
            for (Path sectionPath : directories(tree)) {
                String section = sectionPath.getFileName().toString();
                if (".git".equals(section)) continue;
                for (Path folderPath : directories(sectionPath)) {
                    String folder = folderPath.getFileName().toString();
                    Path path = folderPath.resolve("package.json");
                    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) continue;
                    String relative = tree.relativize(path).toString();
                    if (!Fields.FOLDER.matcher(section).matches() || !Fields.FOLDER.matcher(folder).matches()) {
                        issues.put(relative, "Invalid section or package folder name");
                        problems.add(new Problem(folder, "The folder name isn't valid", false));
                        continue;
                    }
                    try {
                        if (!budget.take(Files.size(path))) {
                            issues.put(relative, "Not loaded: the sources hold more package data than Wave can keep");
                            problems.add(new Problem(folder, "Wave didn't load it because its sources have too many packages", false));
                            continue;
                        }
                    } catch (IOException e) {
                        issues.put(relative, "Could not read package file");
                        problems.add(new Problem(folder, "Wave couldn't read the file", false));
                        continue;
                    }
                    File art = icon(tree, folderPath.resolve("icon.png"), issues, problems, folder);
                    try {
                        PackageManifest item = PackageManifest.parse(SourceStore.readBounded(path, Tree.MAX_FILE));
                        String first = seen.putIfAbsent(item.id, relative);
                        if (first != null) {
                            issues.put(relative, "Duplicate package id; also in " + first);
                            String[] other = first.split("/");
                            problems.add(new Problem(folder, "It has the same id as " + (other.length == 3 ? other[1] : first), false));
                        } else packages.add(new Entry(info, header, item, section, folder, art));
                    } catch (IOException | IllegalArgumentException e) {
                        issues.put(relative, e.getMessage());
                        problems.add(new Problem(folder, e instanceof IllegalArgumentException
                            ? reason(e.getMessage(), "package.json") : "Wave couldn't read the file", false));
                    }
                }
            }
        } catch (IOException e) { issues.put(".", "Could not read cached source folders"); }
    }

    private static List<Path> directories(Path path) throws IOException {
        List<Path> result = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(path)) {
            for (Path file : files) if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) result.add(file);
        }
        Collections.sort(result);
        return result;
    }

    private static File icon(Path root, Path icon, Map<String, String> issues, Problems problems, String folder) {
        if (!Files.exists(icon, LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            Png.check(SourceStore.readBounded(icon, Tree.MAX_FILE));
            return icon.toFile();
        } catch (IOException | IllegalArgumentException e) {
            issues.put(root.relativize(icon).toString(), e.getMessage());
            problems.add(new Problem(folder, e instanceof IllegalArgumentException
                ? reason(e.getMessage(), "icon.png") : "Wave couldn't read the file", true));
            return null;
        }
    }

    private static List<Section> sections(List<Entry> entries, Map<String, String> labels) {
        Map<String, List<Entry>> groups = new TreeMap<>();
        for (Entry entry : entries) groups.computeIfAbsent(entry.section, key -> new ArrayList<>()).add(entry);
        List<Section> result = new ArrayList<>();
        for (Map.Entry<String, String> label : labels.entrySet()) {
            List<Entry> group = groups.remove(label.getKey());
            if (group != null) result.add(new Section(label.getKey(), label.getValue(), group));
        }
        groups.forEach((folder, group) -> result.add(new Section(folder,
            folder.substring(0, 1).toUpperCase(Locale.ROOT) + folder.substring(1), group)));
        return result;
    }

    private static List<Entry> sorted(List<Entry> entries) {
        List<Entry> result = new ArrayList<>(entries);
        result.sort(BY_NAME);
        return Collections.unmodifiableList(result);
    }
}
