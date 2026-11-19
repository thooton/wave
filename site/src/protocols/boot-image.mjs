export const bootBytes = 32 * 1024 * 1024;
const signature = (bytes, text) => [...text].every((c, i) => c.charCodeAt(0) === bytes[i]);
const fields = b => new DataView(b.buffer, b.byteOffset, b.byteLength);

export function checkBootImage(bytes) {
  if (bytes.length !== bootBytes || !signature(bytes, 'ANDROID!')) throw new Error('Expected a full 32 MiB XP3800 boot backup starting with ANDROID!.');
  const header = fields(bytes), page = header.getUint32(36, true);
  const kernel = header.getUint32(8, true), ramdisk = header.getUint32(16, true);
  const round = size => Math.ceil(size / page) * page;
  if (page !== 2048 || !kernel || !ramdisk || page + round(kernel) + round(ramdisk) + round(header.getUint32(24, true)) > bytes.length) {
    throw new Error('XP3800 boot header has invalid component sizes.');
  }
}

export async function sha256(bytes) {
  return Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)), b => b.toString(16).padStart(2, '0')).join('');
}
