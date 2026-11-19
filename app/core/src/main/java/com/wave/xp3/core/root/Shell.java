package com.wave.xp3.core.root;

/** Somewhere to run Wave's scripts as root: the one `su` shell, or a test's fake. */
public interface Shell {

    final class Result {
        public final int exit;
        public final String output;

        public Result(int exit, String output) {
            this.exit = exit;
            this.output = output;
        }

        public boolean ok() {
            return exit == 0;
        }
    }

    /** There is no root: no `su`, or it refused Wave. The message says what to do. */
    final class NoRoot extends java.io.IOException {
        public NoRoot(String message) {
            super(message);
        }
    }

    /**
     * Runs `script` and returns its exit status and output (stdout and
     * stderr together). Throws {@link NoRoot} without root, and an
     * IOException when the script outran `timeoutMs` (the shell is then
     * restarted for the next one).
     */
    Result run(String script, long timeoutMs) throws java.io.IOException;
}
