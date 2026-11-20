import { bootBytes } from './boot-image.mjs?v=redesign2';

export const sectorBytes = 512;
const signature = (bytes, text) => [...text].every((c, i) => c.charCodeAt(0) === bytes[i]);
const fields = b => new DataView(b.buffer, b.byteOffset, b.byteLength);
const crc = bytes => {
  let value = -1;
  for (const b of bytes) {
    value ^= b;
    for (let bit = 0; bit < 8; bit++) value = (value >>> 1) ^ (0xedb88320 & -(value & 1));
  }
  return (value ^ -1) >>> 0;
};

// The disk GUID, as text (first three fields little-endian). It identifies this
// phone's eMMC to its own backup file and is never sent anywhere.
function guid(b) {
  const hex = [...b].map(x => x.toString(16).padStart(2, '0'));
  const le = (from, to) => hex.slice(from, to).reverse().join('');
  return `${le(0, 4)}-${le(4, 6)}-${le(6, 8)}-${hex.slice(8, 10).join('')}-${hex.slice(10).join('')}`;
}

export function readGptHeader(bytes) {
  if (bytes.length !== sectorBytes || !signature(bytes, 'EFI PART')) throw new Error('Primary GPT signature missing.');
  const view = fields(bytes), size = view.getUint32(12, true);
  if (view.getUint32(8, true) !== 0x00010000 || size < 92 || size > sectorBytes || view.getBigUint64(24, true) !== 1n) {
    throw new Error('Unsupported GPT header.');
  }
  const check = bytes.slice(0, size);
  fields(check).setUint32(16, 0);
  if (crc(check) !== view.getUint32(16, true)) throw new Error('GPT header CRC mismatch.');
  const entryLba = view.getBigUint64(72, true), count = view.getUint32(80, true), stride = view.getUint32(84, true);
  const min = view.getBigUint64(40, true), max = view.getBigUint64(48, true);
  const sectors = Math.ceil(count * stride / sectorBytes);
  if (entryLba !== 2n || count < 10 || count > 128 || stride !== 128 || entryLba + BigInt(sectors) > min
      || max <= min || max >= view.getBigUint64(32, true) || max > BigInt(Number.MAX_SAFE_INTEGER)) {
    throw new Error('Unsupported XP3800 GPT geometry.');
  }
  return { entryLba: Number(entryLba), count, stride, sectors, min, max, tableCrc: view.getUint32(88, true),
    diskGuid: guid(bytes.subarray(56, 72)) };
}

export function parseXp3800Gpt(gpt, table) {
  if (table.length !== gpt.sectors * sectorBytes || crc(table.subarray(0, gpt.count * gpt.stride)) !== gpt.tableCrc) {
    throw new Error('GPT partition array CRC mismatch.');
  }
  const entries = new Map();
  for (let i = 0; i < gpt.count; i++) {
    const part = table.subarray(i * gpt.stride, (i + 1) * gpt.stride);
    if (!part.subarray(0, 16).some(Boolean)) continue;
    const view = fields(part), start = view.getBigUint64(32, true), end = view.getBigUint64(40, true);
    const name = new TextDecoder('utf-16le').decode(part.subarray(56)).split('\0', 1)[0];
    if (!name || entries.has(name) || start < gpt.min || end > gpt.max || end < start) throw new Error('Invalid GPT partition entry.');
    entries.set(name, { index: i, start: Number(start), end: Number(end) });
  }
  const sorted = [...entries.values()].sort((a, b) => a.start - b.start);
  if (sorted.some((entry, i) => i > 0 && sorted[i - 1].end >= entry.start)) throw new Error('Overlapping GPT partitions.');
  const boot = entries.get('boot');
  if (!boot || boot.index !== 4 || boot.end - boot.start + 1 !== bootBytes / sectorBytes
      || !['DDR', 'sec', 'system', 'persist', 'recovery', 'devinfo', 'aboot', 'devcfg', 'vendor', 'userdata'].every(name => entries.has(name))
      || entries.get('sec').end + 1 !== boot.start || boot.end + 1 !== entries.get('system').start
      || entries.has('boot_a') || entries.has('boot_b') || entries.has('vbmeta')) {
    throw new Error('GPT is not a recognized single-slot XP3800 layout.');
  }
  // Expose named geometry without publishing per-phone/disk identifiers.
  return Object.freeze({
    boot: Object.freeze({ start: boot.start, sectors: bootBytes / sectorBytes }),
    partitions: Object.freeze([...entries].map(([name, entry]) => Object.freeze({
      name, start: entry.start, sectors: entry.end - entry.start + 1,
    }))),
  });
}
