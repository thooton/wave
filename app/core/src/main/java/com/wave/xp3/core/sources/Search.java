package com.wave.xp3.core.sources;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Case-insensitive substring search; normalized fields are cached for each index snapshot. */
public final class Search {
    /** Most results a query returns, best first; a one-letter query on a huge index must not build a huge list. */
    public static final int MAX_RESULTS = 200;
    /** Longer descriptions are searched in place instead of keeping a lowercase copy (a second copy of up to 64 KiB each). */
    private static final int CACHED_DESCRIPTION = 2048;

    private static final class Text {
        final Index.Entry entry;
        final String name, summary, description, original;
        Text(Index.Entry entry) {
            this.entry = entry;
            name = entry.manifest.name.toLowerCase(Locale.ROOT);
            summary = entry.manifest.summary.toLowerCase(Locale.ROOT);
            String full = entry.manifest.description;
            description = full.length() <= CACHED_DESCRIPTION ? full.toLowerCase(Locale.ROOT) : null;
            original = full;
        }
        boolean describes(String text) {
            if (description != null) return description.contains(text);
            for (int i = 0, last = original.length() - text.length(); i <= last; i++) {
                if (original.regionMatches(true, i, text, 0, text.length())) return true;
            }
            return false;
        }
    }

    private final List<Text> items = new ArrayList<>();
    Search(List<Index.Entry> alphabetical) {
        for (Index.Entry entry : alphabetical) items.add(new Text(entry));
    }

    public List<Index.Entry> find(String query) {
        String text = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) return Collections.emptyList();
        List<Index.Entry> names = new ArrayList<>(), summaries = new ArrayList<>(), descriptions = new ArrayList<>();
        for (Text item : items) {
            if (item.name.contains(text)) names.add(item.entry);
            else if (item.summary.contains(text)) summaries.add(item.entry);
            else if (item.describes(text)) descriptions.add(item.entry);
        }
        names.addAll(summaries);
        names.addAll(descriptions);
        return Collections.unmodifiableList(names.size() > MAX_RESULTS ? new ArrayList<>(names.subList(0, MAX_RESULTS)) : names);
    }
}
