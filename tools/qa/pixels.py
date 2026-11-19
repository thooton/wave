"""Read non-interlaced Android screencap PNGs without imaging dependencies."""
import struct
import zlib

WIDTH, HEIGHT = 240, 320
PNG_MAGIC = b'\x89PNG\r\n\x1a\n'


def paeth(a, b, c):
    pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
    return a if pa <= pb and pa <= pc else b if pb <= pc else c


def decode(png):
    """Return tightly packed RGB, supporting Android's RGB and RGBA encodings."""
    if not png.startswith(PNG_MAGIC):
        raise ValueError('screencap did not return a PNG')
    offset, channels = 8, 0
    image = bytearray()
    ended = False
    while len(png) - offset >= 12:
        length, kind = struct.unpack_from('>I4s', png, offset)
        end = offset + 8 + length
        if end + 4 > len(png):
            raise ValueError('truncated PNG chunk')
        chunk = png[offset + 8:end]
        expected, = struct.unpack_from('>I', png, end)
        if expected != zlib.crc32(png[offset + 4:end]):
            raise ValueError('bad PNG checksum')
        offset = end + 4
        if kind == b'IHDR':
            if channels or len(chunk) != 13:
                raise ValueError('bad PNG header')
            width, height, depth, color, compression, filters, interlace = struct.unpack('>IIBBBBB', chunk)
            if (width, height) != (WIDTH, HEIGHT):
                raise ValueError(f'expected a {WIDTH}x{HEIGHT} XP3800 capture, got {width}x{height}')
            if depth != 8 or color not in (2, 6) or any((compression, filters, interlace)):
                raise ValueError('expected non-interlaced RGB/RGBA PNG')
            channels = 4 if color == 6 else 3
        elif kind == b'IDAT':
            if not channels:
                raise ValueError('PNG has no header')
            image.extend(chunk)
        elif kind == b'IEND':
            ended = True
            break
    if not ended or not channels:
        raise ValueError('incomplete PNG')
    stride = WIDTH * channels
    expected_bytes = HEIGHT * (stride + 1)
    inflater = zlib.decompressobj()
    scanlines = inflater.decompress(image, expected_bytes + 1)
    if not inflater.eof or len(scanlines) != expected_bytes:
        raise ValueError('bad PNG scanline length')
    prev = bytearray(stride)
    rgb = bytearray(WIDTH * HEIGHT * 3)
    for y in range(HEIGHT):
        row_at = y * (stride + 1)
        mode = scanlines[row_at]
        if mode > 4:
            raise ValueError('unsupported PNG filter')
        row = bytearray(scanlines[row_at + 1:row_at + 1 + stride])
        if mode:
            for x in range(stride):
                left = row[x - channels] if x >= channels else 0
                up = prev[x]
                if mode == 1:
                    predictor = left
                elif mode == 2:
                    predictor = up
                elif mode == 3:
                    predictor = (left + up) // 2
                else:
                    predictor = paeth(left, up, prev[x - channels] if x >= channels else 0)
                row[x] = (row[x] + predictor) & 255
        rgb_at = y * WIDTH * 3
        if channels == 3:
            rgb[rgb_at:rgb_at + WIDTH * 3] = row
        else:
            for ch in range(3):
                rgb[rgb_at + ch:rgb_at + WIDTH * 3:3] = row[ch::4]
        prev = row
    return bytes(rgb)


def difference(first, second, region, channel_delta=8):
    """Fraction of cropped pixels with at least one RGB channel changed."""
    x, y, w, h = region
    count = 0
    for i in range(y, y + h):
        for at in range((i * WIDTH + x) * 3, (i * WIDTH + x + w) * 3, 3):
            count += (abs(first[at] - second[at]) >= channel_delta
                      or abs(first[at + 1] - second[at + 1]) >= channel_delta
                      or abs(first[at + 2] - second[at + 2]) >= channel_delta)
    return count / (w * h)
