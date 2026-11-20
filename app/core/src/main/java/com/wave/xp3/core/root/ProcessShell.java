package com.wave.xp3.core.root;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * One long-lived shell process that scripts are sent to (design section 4,
 * "Root"): `su` on the phone, a fake `su` in the tests. Opened on first use,
 * checked with `id`, and reopened after a timeout or if it dies. Scripts run
 * one at a time ({@link Frame} finds where each one ends).
 *
 * A script Wave gives up on (too long, interrupted, or its shell died) is
 * stopped, not left behind: ending the `su` client doesn't end the root
 * subshell running it, so a fresh shell kills that subshell and everything
 * under it ({@link Scripts#stop}) before the failure is reported. Otherwise a
 * `pm install` or a module copy reported as failed could still finish.
 *
 * Plain Java (ProcessBuilder), so the same class runs on the phone and on a
 * desktop JVM against a fake.
 */
public final class ProcessShell implements Shell {

    /** Long enough for the user to answer Magisk's prompt, if one appears. */
    public static final long OPEN_TIMEOUT_MS = 60000;
    /** Output kept per script; anything beyond is dropped. */
    private static final int MAX_OUTPUT = 1024 * 1024;
    private static final String EOF = new String("\u0000eof");

    private final List<String> command;
    private final Map<String, String> environment;
    private final SecureRandom random = new SecureRandom();

    private Process process;
    private OutputStream stdin;
    private LinkedBlockingQueue<String> lines;
    /** How long stopping an abandoned script may take, its own shell's opening included. */
    static final long STOP_TIMEOUT_MS = 15000;

    public ProcessShell(List<String> command, Map<String, String> environment) {
        this.command = new ArrayList<>(command);
        this.environment = environment;
    }

    /** The phone's `su`. */
    public static ProcessShell su() {
        List<String> command = new ArrayList<>();
        command.add("su");
        return new ProcessShell(command, null);
    }

    @Override
    public synchronized Result run(String script, long timeoutMs) throws IOException {
        open();
        int[] pid = new int[1];
        try {
            return send(script, timeoutMs, pid);
        } catch (IOException e) {
            close();
            if (pid[0] > 0) stop(pid[0]);
            throw e;
        }
    }

    /** Opens the shell if it isn't open, and checks it is root. */
    public synchronized void open() throws IOException {
        if (process != null && alive(process)) return;
        close();
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        if (environment != null) builder.environment().putAll(environment);
        Process started;
        try {
            started = builder.start();
        } catch (IOException e) {
            throw new NoRoot("This phone has no root (no su). Jailbreak it first: see the Wave website.");
        }
        process = started;
        stdin = started.getOutputStream();
        final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
        lines = queue;
        final BufferedReader reader = new BufferedReader(new InputStreamReader(started.getInputStream(), "UTF-8"));
        Thread pump = new Thread(() -> {
            try {
                for (String line; (line = reader.readLine()) != null; ) queue.add(line);
            } catch (IOException ignored) {
                // The process went away; EOF says so below.
            }
            queue.add(EOF);
        }, "root-shell-reader");
        pump.setDaemon(true);
        pump.start();
        Result id;
        try {
            id = send(Scripts.whoami(), OPEN_TIMEOUT_MS, new int[1]);
        } catch (IOException e) {
            close();
            throw new NoRoot("Root was refused. Open Magisk, Superuser, and allow Wave.");
        }
        if (!Scripts.isRoot(id.output)) {
            close();
            throw new NoRoot("Root was refused. Open Magisk, Superuser, and allow Wave.");
        }
    }

    /**
     * Ends the abandoned script's subshell `pid` and its tree through a fresh
     * shell, which stays open for the next script. Best effort: if root is
     * gone by now, there is no way to.
     */
    private void stop(int pid) {
        try {
            open();
            send(Scripts.stop(pid), STOP_TIMEOUT_MS, new int[1]);
        } catch (IOException e) {
            close();
        }
    }

    /** Sends `script` and waits for its end; `pid[0]` gets its subshell's pid as soon as it is known. */
    private Result send(String script, long timeoutMs, int[] pid) throws IOException {
        String nonce = nonce();
        lines.clear();
        try {
            stdin.write(Frame.wrap(script, nonce).getBytes("UTF-8"));
            stdin.flush();
        } catch (IOException e) {
            throw new IOException("The root shell closed.");
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        List<String> out = new ArrayList<>();
        int size = 0;
        while (true) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) throw new IOException("The phone took too long to answer.");
            String line;
            try {
                line = lines.poll(left, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted.");
            }
            if (line == null) continue;
            if (line == EOF) throw new IOException("The root shell closed.");
            Integer started = Frame.pid(line, nonce);
            if (started != null) {
                pid[0] = started;
                continue;
            }
            Integer exit = Frame.end(line, nonce);
            if (exit != null) return new Result(exit, Frame.output(out));
            if (size < MAX_OUTPUT) {
                out.add(line);
                size += line.length() + 1;
            }
        }
    }

    private String nonce() {
        StringBuilder s = new StringBuilder(16);
        String digits = "abcdefghijklmnopqrstuvwxyz0123456789";
        for (int i = 0; i < 16; i++) s.append(digits.charAt(random.nextInt(digits.length())));
        return s.toString();
    }

    /** Ends the shell; the next script opens a new one. */
    public synchronized void close() {
        if (process == null) return;
        try {
            stdin.close();
        } catch (IOException ignored) {
            // Closing anyway.
        }
        process.destroy();
        process = null;
        stdin = null;
    }

    private static boolean alive(Process p) {
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }
}
