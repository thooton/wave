package com.wave.xp3.core.root;

import java.util.List;

/**
 * How a script travels through the one long-lived `su` shell and how its end
 * is found in the stream that comes back.
 *
 * The shell is a pipe, not a terminal: there is no prompt to wait for and
 * nothing marks where one script's output stops. So each script runs in a
 * subshell (an `exit` in it ends only the subshell, and its working
 * directory and variables don't leak into the next), with stdin from
 * /dev/null so it can't swallow the next script, stderr folded into stdout,
 * and is followed by a line no output can contain by accident: a marker with
 * a fresh nonce and the subshell's exit status.
 *
 * Its first line is the subshell's own pid (`$$` would be the shell's: a
 * child `sh` started with exec from a command substitution reports it as its
 * parent). When Wave gives up on a script, ending the `su` client doesn't
 * end the subshell, which goes on with whatever it was doing; with its pid,
 * {@link Scripts#stop} can end it and everything it started.
 */
public final class Frame {

    private Frame() {}

    private static final String MARK = ":wave-end:";
    private static final String PID = ":wave-pid:";

    /** The text to write to the shell for `script`. */
    public static String wrap(String script, String nonce) {
        if (!nonce.matches("[A-Za-z0-9]{8,64}")) throw new IllegalArgumentException("bad nonce");
        return "(\necho \"" + PID + nonce + ":$(exec sh -c 'echo $PPID')\"\n" + script + "\n) </dev/null 2>&1\n"
            + "echo \"\n" + MARK + nonce + ":$?\"\n";
    }

    /** The pid of the subshell running the script sent with `nonce`, if `line` says it, or null. */
    public static Integer pid(String line, String nonce) {
        String prefix = PID + nonce + ":";
        if (!line.startsWith(prefix)) return null;
        String n = line.substring(prefix.length()).trim();
        if (!n.matches("[0-9]{1,9}") || Integer.parseInt(n) < 2) return null;
        return Integer.parseInt(n);
    }

    /** The exit status if `line` ends the script sent with `nonce`, or null. */
    public static Integer end(String line, String nonce) {
        String prefix = MARK + nonce + ":";
        if (!line.startsWith(prefix)) return null;
        try {
            return Integer.parseInt(line.substring(prefix.length()).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * The script's output from the lines read before its end marker. The
     * marker's own leading newline leaves one empty line after output that
     * ended in a newline; that line is not the script's.
     */
    public static String output(List<String> lines) {
        int n = lines.size();
        if (n > 0 && lines.get(n - 1).isEmpty()) n--;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) out.append('\n');
            out.append(lines.get(i));
        }
        return out.toString();
    }
}
