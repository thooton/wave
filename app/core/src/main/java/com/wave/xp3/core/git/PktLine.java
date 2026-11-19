package com.wave.xp3.core.git;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Git's binary-safe packet-line framing. */
public final class PktLine {
    public static final int FLUSH = 0, DELIMITER = 1, RESPONSE_END = 2, DATA = 4;
    public static final int MAX_PAYLOAD = 65516;
    private static final String HEX = "0123456789abcdef";
    public final int kind;
    public final byte[] bytes;

    private PktLine(int kind, byte[] bytes) {
        this.kind = kind;
        this.bytes = bytes;
    }

    /** Returns null at a clean EOF, but rejects a partial packet. */
    public static PktLine read(InputStream in) throws IOException {
        int c = in.read();
        if (c < 0) return null;
        int length = nibble(c);
        for (int i = 1; i < 4; i++) {
            c = in.read();
            if (c < 0) throw new EOFException("Truncated Git packet length");
            length = (length << 4) | nibble(c);
        }
        if (length <= RESPONSE_END) return new PktLine(length, new byte[0]);
        if (length < DATA || length > MAX_PAYLOAD + DATA) throw new IOException("Invalid Git packet length");
        byte[] bytes = new byte[length - DATA];
        for (int pos = 0; pos < bytes.length;) {
            int n = in.read(bytes, pos, bytes.length - pos);
            if (n < 0) throw new EOFException("Truncated Git packet");
            pos += n;
        }
        return new PktLine(DATA, bytes);
    }

    public String text() {
        int length = bytes.length;
        if (length > 0 && bytes[length - 1] == '\n') length--;
        return new String(bytes, 0, length, StandardCharsets.UTF_8);
    }

    public static void write(OutputStream out, String text) throws IOException {
        write(out, text.getBytes(StandardCharsets.UTF_8));
    }

    public static void write(OutputStream out, byte[] bytes) throws IOException {
        if (bytes.length > MAX_PAYLOAD) throw new IOException("Git packet is too large");
        header(out, bytes.length + DATA);
        out.write(bytes);
    }

    public static void control(OutputStream out, int kind) throws IOException {
        if (kind < FLUSH || kind > RESPONSE_END) throw new IllegalArgumentException("Unknown Git control packet");
        header(out, kind);
    }

    private static void header(OutputStream out, int length) throws IOException {
        for (int shift = 12; shift >= 0; shift -= 4) out.write(HEX.charAt((length >>> shift) & 15));
    }

    private static int nibble(int c) throws IOException {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        throw new IOException("Invalid Git packet length");
    }
}
