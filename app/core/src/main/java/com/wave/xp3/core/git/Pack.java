package com.wave.xp3.core.git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** A verified, self-contained SHA-1 pack; uninteresting objects remain compressed. */
final class Pack {
    public static final int MAX_PACK = 8 * 1024 * 1024;
    private static final int MAX_OBJECTS = 64_000;
    private static final int MAX_DELTA_OBJECT = MAX_PACK;
    private static final int MAX_WORK = 128 * 1024 * 1024;
    private static final int CACHE_BYTES = 2 * 1024 * 1024;
    private static final int COMMIT = 1, TREE = 2, BLOB = 3, OFS_DELTA = 6, REF_DELTA = 7;
    private static final String[] TYPES = {"", "commit", "tree", "blob", "tag"};
    private static final String HEX = "0123456789abcdef";

    private final byte[] bytes;
    private final int end;
    private final Map<String, Entry> objects = new HashMap<>();
    private final LinkedHashMap<Entry, byte[]> cache = new LinkedHashMap<>(16, .75f, true);
    private int cachedBytes, work;

    private static final class Entry {
        int type, start, size, resultSize;
        Entry base;
        List<Entry> children;
    }

    private Pack(byte[] bytes, int length) {
        this.bytes = bytes;
        end = length - 20;
    }

    public static Pack parse(byte[] bytes) throws IOException {
        if (bytes.length > MAX_PACK) throw new SourceFailure(SourceFailure.Kind.TOO_BIG, "Git pack exceeds 8 MiB");
        return parse(bytes.clone(), bytes.length);
    }

    // Ownership of bytes moves from the sideband reader to the pack.
    static Pack parse(byte[] bytes, int length) throws IOException {
        if (length > MAX_PACK) throw new SourceFailure(SourceFailure.Kind.TOO_BIG, "Git pack exceeds 8 MiB");
        if (length < 32) throw new IOException("Truncated Git pack");
        Pack pack = new Pack(bytes, length);
        pack.read();
        return pack;
    }

    private void read() throws IOException {
        Cursor in = new Cursor(bytes, 0, end);
        if (in.u32() != 0x5041434bL) throw new IOException("Missing Git pack signature");
        long version = in.u32();
        if (version != 2 && version != 3) throw new IOException("Unsupported Git pack version");
        long count = in.u32();
        if (count > MAX_OBJECTS || count > (end - in.pos) / 9) throw new IOException("Invalid Git pack object count");
        MessageDigest digest = sha1();
        digest.update(bytes, 0, end);
        byte[] trailer = digest.digest();
        for (int i = 0; i < trailer.length; i++) {
            if (trailer[i] != bytes[end + i]) throw new IOException("Git pack checksum mismatch");
        }

        Map<Integer, Entry> byOffset = new HashMap<>();
        Map<String, List<Entry>> waiting = new HashMap<>();
        ArrayDeque<Entry> ready = new ArrayDeque<>();
        int unresolved = 0;
        byte[] scratch = new byte[8192];
        Inflater inflater = new Inflater();
        try {
            for (int i = 0; i < count; i++) {
                int offset = in.pos;
                int c = in.next();
                Entry e = new Entry();
                e.type = (c >>> 4) & 7;
                e.size = in.number(c, 4);
                int encoding = e.type;
                String ref = null;
                if (encoding == OFS_DELTA) {
                    int distance = in.distance();
                    e.base = byOffset.get(offset - distance);
                    if (e.base == null) throw new IOException("Invalid Git offset delta base");
                } else if (encoding == REF_DELTA) {
                    ref = in.id();
                } else if (encoding < COMMIT || encoding > 4) {
                    throw new IOException("Unsupported Git object type");
                }
                e.start = in.pos;
                boolean delta = encoding == OFS_DELTA || encoding == REF_DELTA;
                if (delta && e.size > MAX_DELTA_OBJECT) throw new SourceFailure(SourceFailure.Kind.TOO_BIG, "Git delta is too large");
                if (!delta) header(digest, e.type, e.size);
                in.pos += inflate(e, inflater, scratch, null, delta ? null : digest);
                byOffset.put(offset, e);
                if (!delta) {
                    e.resultSize = e.size;
                    publish(hex(digest.digest(), 0, 20), e, waiting, ready);
                } else {
                    unresolved++;
                    if (ref != null) {
                        e.base = objects.get(ref);
                        if (e.base == null) waiting.computeIfAbsent(ref, key -> new ArrayList<>()).add(e);
                    }
                    if (e.base != null) {
                        if (e.base.type < OFS_DELTA) ready.add(e);
                        else {
                            if (e.base.children == null) e.base.children = new ArrayList<>();
                            e.base.children.add(e);
                        }
                    }
                }
            }
            if (in.pos != end) throw new IOException("Unexpected data after Git pack objects");
            while (!ready.isEmpty()) {
                Entry e = ready.removeFirst();
                byte[] data = materialize(e, inflater, scratch);
                e.type = e.base.type;
                e.resultSize = data.length;
                header(digest, e.type, data.length);
                digest.update(data);
                publish(hex(digest.digest(), 0, 20), e, waiting, ready);
                if (e.children != null) {
                    ready.addAll(e.children);
                    e.children = null;
                }
                unresolved--;
            }
            if (unresolved != 0) throw new IOException("Git pack has a missing or cyclic delta base");
        } finally {
            inflater.end();
        }
    }

    private void publish(String id, Entry e, Map<String, List<Entry>> waiting, ArrayDeque<Entry> ready) {
        objects.putIfAbsent(id, e);
        List<Entry> refs = waiting.remove(id);
        if (refs != null) {
            for (Entry child : refs) {
                child.base = e;
                ready.add(child);
            }
        }
    }

    byte[] commit(String id, int limit) throws IOException { return read(id, COMMIT, limit); }
    byte[] tree(String id, int limit) throws IOException { return read(id, TREE, limit); }
    byte[] blob(String id, int limit) throws IOException { return read(id, BLOB, limit); }

    private byte[] read(String id, int type, int limit) throws IOException {
        Entry e = objects.get(id);
        if (e == null) return null;
        if (e.type != type) throw new IOException("Git tree references the wrong object type");
        if (e.resultSize > limit) return null;
        Inflater inflater = new Inflater();
        try {
            return materialize(e, inflater, new byte[8192]);
        } finally {
            inflater.end();
        }
    }

    private byte[] materialize(Entry e, Inflater inflater, byte[] scratch) throws IOException {
        // Resolve from the nearest cached ancestor, without recursion or retaining the chain's bytes.
        ArrayDeque<Entry> chain = new ArrayDeque<>();
        byte[] data;
        while ((data = cache.get(e)) == null) {
            if (e.size > MAX_DELTA_OBJECT) throw new SourceFailure(SourceFailure.Kind.TOO_BIG, "Git delta base is too large");
            if (e.base == null) {
                charge(e.size);
                data = new byte[e.size];
                inflate(e, inflater, scratch, data, null);
                if (!chain.isEmpty()) remember(e, data);
                break;
            }
            chain.push(e);
            e = e.base;
        }
        while (!chain.isEmpty()) {
            e = chain.pop();
            charge(e.size);
            byte[] instructions = new byte[e.size];
            inflate(e, inflater, scratch, instructions, null);
            data = delta(data, instructions);
            remember(e, data);
        }
        return data;
    }

    private void remember(Entry e, byte[] data) {
        if (data.length > CACHE_BYTES) return;
        while (cachedBytes + data.length > CACHE_BYTES || cache.size() >= 128) {
            Entry oldest = cache.keySet().iterator().next();
            cachedBytes -= cache.remove(oldest).length;
        }
        cachedBytes += data.length;
        cache.put(e, data);
    }

    private int inflate(Entry e, Inflater inflater, byte[] scratch, byte[] target, MessageDigest digest)
        throws IOException {
        if (target == null) charge(e.size);
        inflater.reset();
        inflater.setInput(bytes, e.start, end - e.start);
        int done = 0;
        try {
            while (!inflater.finished()) {
                int n = inflater.inflate(scratch);
                if (n == 0 && !inflater.finished()) throw new IOException("Truncated or invalid Git zlib stream");
                if (n > e.size - done) throw new IOException("Git object size mismatch");
                if (target != null) System.arraycopy(scratch, 0, target, done, n);
                if (digest != null) digest.update(scratch, 0, n);
                done += n;
            }
        } catch (DataFormatException e1) {
            throw new IOException("Invalid Git zlib stream", e1);
        }
        if (done != e.size) throw new IOException("Git object size mismatch");
        return inflater.getTotalIn();
    }

    private byte[] delta(byte[] base, byte[] instructions) throws IOException {
        Cursor in = new Cursor(instructions, 0, instructions.length);
        if (in.number(in.next(), 7) != base.length) throw new IOException("Git delta base size mismatch");
        int size = in.number(in.next(), 7);
        if (size > MAX_DELTA_OBJECT) throw new SourceFailure(SourceFailure.Kind.TOO_BIG, "Git delta result is too large");
        charge(size);
        byte[] result = new byte[size];
        int out = 0;
        while (in.pos < in.end) {
            int op = in.next();
            if ((op & 128) != 0) {
                int offset = in.selected(op, 0, 4);
                int n = in.selected(op, 4, 3);
                if (n == 0) n = 65536;
                if (offset < 0 || offset > base.length - n || n > size - out) {
                    throw new IOException("Git delta copy is out of bounds");
                }
                System.arraycopy(base, offset, result, out, n);
                out += n;
            } else {
                if (op == 0 || op > size - out || op > in.end - in.pos) {
                    throw new IOException("Git delta insert is out of bounds");
                }
                System.arraycopy(instructions, in.pos, result, out, op);
                in.pos += op;
                out += op;
            }
        }
        if (out != size) throw new IOException("Git delta result size mismatch");
        return result;
    }

    private void charge(int n) throws IOException {
        if (n > MAX_WORK - work) throw new SourceFailure(SourceFailure.Kind.TOO_BIG, "Git pack expands to too much data");
        if (Thread.currentThread().isInterrupted()) throw new SourceFailure(SourceFailure.Kind.STOPPED, "Git fetch cancelled");
        work += n;
    }

    private static MessageDigest sha1() {
        try { return MessageDigest.getInstance("SHA-1"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    private static void header(MessageDigest digest, int type, int size) {
        digest.update((TYPES[type] + " " + size + '\0').getBytes(StandardCharsets.US_ASCII));
    }

    static String hex(byte[] data, int start, int length) {
        char[] chars = new char[length * 2];
        for (int i = 0; i < length; i++) {
            int b = data[start + i] & 255;
            chars[2 * i] = HEX.charAt(b >>> 4);
            chars[2 * i + 1] = HEX.charAt(b & 15);
        }
        return new String(chars);
    }

    static boolean isId(String value) {
        if (value.length() != 40) return false;
        for (int i = 0; i < value.length(); i++) if (HEX.indexOf(value.charAt(i)) < 0) return false;
        return true;
    }

    private static final class Cursor {
        final byte[] data;
        final int end;
        int pos;

        Cursor(byte[] data, int pos, int end) { this.data = data; this.pos = pos; this.end = end; }

        int next() throws IOException {
            if (pos >= end) throw new IOException("Truncated Git object data");
            return data[pos++] & 255;
        }

        long u32() throws IOException {
            return ((long) next() << 24) | (next() << 16) | (next() << 8) | next();
        }

        String id() throws IOException {
            if (end - pos < 20) throw new IOException("Truncated Git object id");
            String id = hex(data, pos, 20);
            pos += 20;
            return id;
        }

        int number(int c, int bits) throws IOException {
            long n = c & ((1 << bits) - 1);
            for (int shift = bits; (c & 128) != 0; shift += 7) {
                c = next();
                if (shift >= 32) throw new IOException("Git object length is too large");
                n |= (long) (c & 127) << shift;
            }
            if (n > Integer.MAX_VALUE) throw new IOException("Git object length is too large");
            return (int) n;
        }

        int distance() throws IOException {
            int c = next();
            long n = c & 127;
            while ((c & 128) != 0) {
                c = next();
                n = ((n + 1) << 7) | (c & 127);
                if (n > MAX_PACK) throw new IOException("Invalid Git offset delta distance");
            }
            return (int) n;
        }

        int selected(int op, int start, int count) throws IOException {
            int n = 0;
            for (int i = 0; i < count; i++) if ((op & (1 << (start + i))) != 0) n |= next() << (i * 8);
            return n;
        }
    }
}
