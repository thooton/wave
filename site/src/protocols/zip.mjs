// Reads named entries from a ZIP (an APK) in memory: stored or deflated, CRC-checked.
// Enough for Magisk's APK; no ZIP64, encryption or multi-disk archives.
const fields = b => new DataView(b.buffer, b.byteOffset, b.byteLength);
const table = Array.from({ length: 256 }, (_, n) => {
  for (let k = 0; k < 8; k++) n = n & 1 ? 0xedb88320 ^ (n >>> 1) : n >>> 1;
  return n >>> 0;
});
export function crc32(bytes) {
  let crc = -1;
  for (let i = 0; i < bytes.length; i++) crc = table[(crc ^ bytes[i]) & 255] ^ (crc >>> 8);
  return (crc ^ -1) >>> 0;
}

export function zipDirectory(zip) {
  const v = fields(zip);
  let end = -1;
  for (let at = zip.length - 22; at >= Math.max(0, zip.length - 22 - 65535); at--) {
    if (v.getUint32(at, true) === 0x06054b50) { end = at; break; }
  }
  if (end < 0) throw new Error('Not a ZIP archive.');
  const count = v.getUint16(end + 10, true), size = v.getUint32(end + 12, true), offset = v.getUint32(end + 16, true);
  if (offset + size > end || count === 0xffff) throw new Error('Unsupported ZIP directory.');
  const entries = new Map();
  for (let at = offset, i = 0; i < count; i++) {
    if (at + 46 > end || v.getUint32(at, true) !== 0x02014b50) throw new Error('Corrupt ZIP directory.');
    const method = v.getUint16(at + 10, true), crc = v.getUint32(at + 16, true);
    const packed = v.getUint32(at + 20, true), length = v.getUint32(at + 24, true);
    const nameLength = v.getUint16(at + 28, true), extra = v.getUint16(at + 30, true), comment = v.getUint16(at + 32, true);
    const local = v.getUint32(at + 42, true);
    const name = new TextDecoder().decode(zip.subarray(at + 46, at + 46 + nameLength));
    if (v.getUint16(at + 8, true) & 1) throw new Error('Encrypted ZIP entries are not supported.');
    entries.set(name, { name, method, crc, packed, length, local });
    at += 46 + nameLength + extra + comment;
  }
  return entries;
}

async function inflate(bytes) {
  const stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream('deflate-raw'));
  return new Uint8Array(await new Response(stream).arrayBuffer());
}

export async function zipEntry(zip, entries, name, limit = 64 * 1024 * 1024) {
  const entry = entries.get(name);
  if (!entry) throw new Error(`The archive has no ${name}.`);
  if (entry.length > limit) throw new Error(`${name} is too large.`);
  const v = fields(zip), at = entry.local;
  if (at + 30 > zip.length || v.getUint32(at, true) !== 0x04034b50) throw new Error(`Corrupt ZIP entry ${name}.`);
  const start = at + 30 + v.getUint16(at + 26, true) + v.getUint16(at + 28, true);
  if (start + entry.packed > zip.length) throw new Error(`Truncated ZIP entry ${name}.`);
  const packed = zip.subarray(start, start + entry.packed);
  let data;
  if (entry.method === 0) data = packed.slice();
  else if (entry.method === 8) data = await inflate(packed);
  else throw new Error(`Unsupported compression for ${name}.`);
  if (data.length !== entry.length || crc32(data) !== entry.crc) throw new Error(`ZIP entry ${name} failed its CRC check.`);
  return data;
}
