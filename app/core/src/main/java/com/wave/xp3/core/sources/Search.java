package com.wave.xp3.core.sources;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Case-insensitive substring search; normalized fields are cached for each index snapshot. */
public final class Search {
    private static final class Text {
        final Index.Entry entry;
        final String name, summary, description;
        Text(Index.Entry entry) {
            this.entry = entry;
            name = entry.manifest.name.toLowerCase(Locale.ROOT);
            summary = entry.manifest.summary.toLowerCase(Locale.ROOT);
            description = entry.manifest.description.toLowerCase(Locale.ROOT);
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
            else if (item.description.contains(text)) descriptions.add(item.entry);
        }
        names.addAll(summaries);
        names.addAll(descriptions);
        return Collections.unmodifiableList(names);
    }
}
