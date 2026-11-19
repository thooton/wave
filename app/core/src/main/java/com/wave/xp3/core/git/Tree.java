package com.wave.xp3.core.git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Walks only the source format's paths. Symlinks and submodules are never followed. */
public final class Tree {
    public static final int MAX_FILE = 64 * 1024;
    private static final int MAX_FILES_BYTES = 16 * 1024 * 1024;
    private static final Pattern FOLDER = Pattern.compile("[a-z0-9][a-z0-9-]*");

    private Tree() {}

    /** Missing or oversized blobs are omitted, including blobs left out by a server filter. */
    static Map<String, byte[]> files(Pack pack, String commit) throws IOException {
        byte[] head = pack.commit(commit, 1024 * 1024);
        if (head == null || head.length < 46 || head[45] != '\n') throw new IOException("Git pack has no usable HEAD commit");
        String header = new String(head, 0, 45, StandardCharsets.US_ASCII);
        if (!header.startsWith("tree ") || !Pack.isId(header.substring(5))) throw new IOException("Invalid Git commit tree id");
        Map<String, byte[]> files = new LinkedHashMap<>();
        walk(pack, header.substring(5), "", 0, files, 0);
        return Collections.unmodifiableMap(files);
    }

    private static int walk(Pack pack, String id, String path, int depth, Map<String, byte[]> files, int size)
        throws IOException {
        byte[] tree = pack.tree(id, Pack.MAX_PACK);
        if (tree == null) throw new IOException("Git pack is missing a source tree");
        Set<String> names = new HashSet<>();
        for (int pos = 0; pos < tree.length;) {
            int start = pos;
            while (pos < tree.length && tree[pos] >= '0' && tree[pos] <= '7') pos++;
            if (pos == tree.length || pos == start || pos - start > 6 || tree[pos++] != ' ') {
                throw new IOException("Invalid Git tree entry mode");
            }
            String mode = new String(tree, start, pos - start - 1, StandardCharsets.US_ASCII);
            start = pos;
            while (pos < tree.length && tree[pos] != 0) pos++;
            if (pos == start || tree.length - pos < 21) throw new IOException("Truncated Git tree entry");
            // The format uses ASCII names. Non-ASCII names (and any other unrelated files) are ignored.
            String name = new String(tree, start, pos - start, StandardCharsets.US_ASCII);
            pos++;
            boolean folder = depth < 2 && "40000".equals(mode) && FOLDER.matcher(name).matches();
            boolean file = ("100644".equals(mode) || "100755".equals(mode)) &&
                (depth == 0 && ("wave.json".equals(name) || "icon.png".equals(name)) ||
                 depth == 2 && ("package.json".equals(name) || "icon.png".equals(name)));
            if (folder || file) {
                if (!names.add(name)) throw new IOException("Duplicate Git source tree entry");
                String child = Pack.hex(tree, pos, 20);
                if (folder) {
                    size = walk(pack, child, path + name + '/', depth + 1, files, size);
                } else {
                    byte[] blob = pack.blob(child, MAX_FILE);
                    if (blob != null) {
                        if (blob.length > MAX_FILES_BYTES - size || files.size() >= 12_000) {
                            throw new SourceFailure(SourceFailure.Kind.TOO_BIG, "Git source contains too much manifest and icon data");
                        }
                        size += blob.length;
                        files.put(path + name, blob.clone());
                    }
                }
            }
            pos += 20;
        }
        return size;
    }
}
