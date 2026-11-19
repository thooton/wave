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

    public static final class Source {
        public final SourceStore.Source info;
        /** Null if the source has not refreshed or its cached header is invalid. */
        public final SourceManifest manifest;
        public final File icon;
        public final List<Section> sections;
        public final List<Entry> packages;
        /** Cached paths and their validation/read errors; other packages are still usable. */
        public final Map<String, String> issues;

        private Source(SourceStore.Source info, SourceManifest manifest, File icon, List<Section> sections,
                       List<Entry> packages, Map<String, String> issues) {
            this.info = info;
            this.manifest = manifest;
            this.icon = icon;
            this.sections = Collections.unmodifiableList(sections);
            this.packages = sorted(packages);
            this.issues = Collections.unmodifiableMap(issues);
        }
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

    /** Reads a checkout or a cached source directory. Useful for offline validation, not Git fetching. */
    public static Source read(SourceStore.Source info, Path tree) {
        Map<String, String> issues = new LinkedHashMap<>();
        List<Entry> packages = new ArrayList<>();
        SourceManifest header = null;
        File icon = null;
        if (tree != null) {
            try { header = SourceManifest.parse(SourceStore.readBounded(tree.resolve("wave.json"), Tree.MAX_FILE)); }
            catch (IOException | IllegalArgumentException e) { issues.put("wave.json", e.getMessage()); }
            icon = icon(tree, tree.resolve("icon.png"), issues);
            if (header != null) readPackages(info, tree, header, packages, issues);
        }
        return new Source(info, header, icon, sections(packages, header == null ? Collections.emptyMap() : header.sections),
            packages, issues);
    }

    private static void readPackages(SourceStore.Source info, Path tree, SourceManifest header, List<Entry> packages,
                                     Map<String, String> issues) {
        Map<String, String> seen = new LinkedHashMap<>();
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
                        continue;
                    }
                    File art = icon(tree, folderPath.resolve("icon.png"), issues);
                    try {
                        PackageManifest item = PackageManifest.parse(SourceStore.readBounded(path, Tree.MAX_FILE));
                        String first = seen.putIfAbsent(item.id, relative);
                        if (first != null) issues.put(relative, "Duplicate package id; also in " + first);
                        else packages.add(new Entry(info, header, item, section, folder, art));
                    } catch (IOException | IllegalArgumentException e) { issues.put(relative, e.getMessage()); }
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

    private static File icon(Path root, Path icon, Map<String, String> issues) {
        if (!Files.exists(icon, LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            Png.check(SourceStore.readBounded(icon, Tree.MAX_FILE));
            return icon.toFile();
        } catch (IOException | IllegalArgumentException e) {
            issues.put(root.relativize(icon).toString(), e.getMessage());
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
