package com.wave.xp3.core.sources;

/**
 * Lets one run of a job be in flight. A request made while it runs is not lost and not duplicated:
 * its flags are merged and handed to exactly one follow-up run.
 */
public final class InFlight {
    private boolean busy;
    private int pending;

    /** True when the caller must start the run now; otherwise the flags were saved for the follow-up. */
    public synchronized boolean begin(int flags) {
        if (busy) { pending |= flags; return false; }
        busy = true;
        return true;
    }

    /** True when a request with this flag is waiting behind the run in flight. */
    public synchronized boolean waiting(int flag) { return (pending & flag) != 0; }

    /**
     * Called once when a run is over. Zero means the gate is free again; anything else holds the gate and
     * is the merged flags (never zero) of the single follow-up run the caller must now start.
     */
    public synchronized int finish() {
        if (!busy) throw new IllegalStateException("No run in flight");
        int next = pending;
        pending = 0;
        busy = next != 0;
        return next;
    }

    public synchronized boolean busy() { return busy; }
}
