package com.wave.xp3.core.sources;

import java.nio.ByteBuffer;
import java.util.zip.CRC32;

/** Check the same basic PNG structure as wavesrc before offering an icon to the UI. */
final class Png {
    private static final long SIGNATURE = 0x89504e470d0a1a0aL;
    private static final int IHDR = 0x49484452, IDAT = 0x49444154, IEND = 0x49454e44;

    static void check(byte[] png) {
        ByteBuffer in = ByteBuffer.wrap(png);
        if (in.remaining() < 8 || in.getLong() != SIGNATURE) throw new IllegalArgumentException("Expected a PNG image");
        boolean pixels = false;
        CRC32 crc = new CRC32();
        while (in.remaining() >= 12) {
            boolean first = in.position() == 8;
            int length = in.getInt();
            int start = in.position();
            int type = in.getInt();
            if (length < 0 || length > in.remaining() - 4) throw new IllegalArgumentException("Truncated PNG chunk");
            int end = in.position() + length;
            crc.reset();
            crc.update(png, start, length + 4);
            if ((int) crc.getValue() != in.getInt(end)) throw new IllegalArgumentException("Corrupt PNG chunk");
            if (first) {
                if (type != IHDR || length != 13) throw new IllegalArgumentException("Missing PNG IHDR");
                int width = in.getInt(), height = in.getInt();
                if (width != height) throw new IllegalArgumentException("PNG icon must be square");
                int depth = in.get() & 255, color = in.get() & 255;
                int allowed = color == 0 ? 0x10116 : color == 3 ? 0x116 :
                    color == 2 || color == 4 || color == 6 ? 0x10100 : 0;
                if (width <= 0 || depth > 16 || (allowed & (1 << depth)) == 0 || in.get() != 0 || in.get() != 0 ||
                    (in.get() & 255) > 1) throw new IllegalArgumentException("Invalid PNG IHDR");
            } else if (type == IDAT && length > 0) pixels = true;
            else if (type == IEND) {
                if (pixels && length == 0 && end + 4 == in.limit()) return;
                break;
            }
            in.position(end + 4);
        }
        throw new IllegalArgumentException("Incomplete PNG image");
    }
}
