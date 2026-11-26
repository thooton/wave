package com.wave.xp3.app;

import com.wave.xp3.data.WaveData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cydia's Continue Queuing: what the user asked for on a Confirm sheet but
 * didn't confirm yet, kept so the next Confirm takes it along. One action per
 * package, in the order they were first asked for; asking for a package again
 * replaces its action. Plain values (no View), so AppSelfTest checks it.
 */
final class Queued {

    private final Map<String, WaveData.Action> asked = new LinkedHashMap<>();

    boolean isEmpty() {
        return asked.isEmpty();
    }

    int size() {
        return asked.size();
    }

    boolean contains(String id) {
        return asked.containsKey(id);
    }

    void clear() {
        asked.clear();
    }

    /** The queued requests, oldest first. */
    List<WaveData.Request> requests() {
        List<WaveData.Request> out = new ArrayList<>(asked.size());
        for (Map.Entry<String, WaveData.Action> e : asked.entrySet()) out.add(new WaveData.Request(e.getKey(), e.getValue()));
        return Collections.unmodifiableList(out);
    }

    /** The queue with `more` asked for after it, without changing the queue. */
    List<WaveData.Request> with(List<WaveData.Request> more) {
        Queued next = copy();
        next.addAll(more);
        return next.requests();
    }

    /** Replaces the queue with `requests` (what a Confirm sheet showed). */
    void set(List<WaveData.Request> requests) {
        asked.clear();
        addAll(requests);
    }

    private void addAll(List<WaveData.Request> more) {
        for (WaveData.Request r : more) {
            // A package asked for again keeps its place and takes the new action.
            asked.put(r.id, r.action);
        }
    }

    private Queued copy() {
        Queued q = new Queued();
        q.asked.putAll(asked);
        return q;
    }

    /** The queue as one line ("id:GET,id:REMOVE"), for keeping it while Wave is closed. */
    String save() {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, WaveData.Action> e : asked.entrySet()) {
            if (b.length() > 0) b.append(',');
            b.append(e.getKey()).append(':').append(e.getValue().name());
        }
        return b.toString();
    }

    /** Reads {@link #save}'s line; anything it can't read is left out. */
    static Queued load(String line) {
        Queued q = new Queued();
        if (line == null || line.isEmpty()) return q;
        for (String part : line.split(",")) {
            int at = part.lastIndexOf(':');
            if (at <= 0) continue;
            try {
                q.asked.put(part.substring(0, at), WaveData.Action.valueOf(part.substring(at + 1)));
            } catch (IllegalArgumentException ignored) {
                // An action this Wave doesn't know: drop it.
            }
        }
        return q;
    }
}
