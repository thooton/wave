// The .wavebackup file: a plain (stored, uncompressed) ZIP holding manifest.json
// and boot.img, so one file carries the image and what it belongs to. Old
// backups are a separate .img and .json pair; readBackup accepts both.
import { parseBackupManifest, manifestLimit } from './backup-manifest.mjs?v=redesign3';
import { isMagiskPatched } from './magisk.mjs?v=redesign3';
import { bootBytes, checkBootImage, sha256 } from '../protocols/boot-image.mjs?v=redesign3';
import { verifyBootSignature, xp3800BootCertSha256 } from '../protocols/boot-signature.mjs?v=redesign3';
import { crc32, zipDirectory, zipEntry } from '../protocols/zip.mjs?v=redesign3';

export const backupExtension = '.wavebackup';
const encoder = new TextEncoder();

export function backupName(manifest) {
  return `xp3800-${manifest.serial}-stock-boot${backupExtension}`;
}

// Stored entries only; the fixed DOS date keeps equal backups byte-identical.
export function makeBackupFile(manifest, image) {
  const files = [
    ['manifest.json', encoder.encode(JSON.stringify(manifest, null, 2) + '\n')],
    ['boot.img', image],
  ];
  const parts = [], central = [];
  let offset = 0;
  for (const [name, data] of files) {
    const nameBytes = encoder.encode(name), crc = crc32(data);
    const local = new Uint8Array(30 + nameBytes.length), l = new DataView(local.buffer);
    l.setUint32(0, 0x04034b50, true); l.setUint16(4, 10, true); l.setUint16(12, 0x21, true);
    l.setUint32(14, crc, true); l.setUint32(18, data.length, true); l.setUint32(22, data.length, true);
    l.setUint16(26, nameBytes.length, true); local.set(nameBytes, 30);
    const entry = new Uint8Array(46 + nameBytes.length), c = new DataView(entry.buffer);
    c.setUint32(0, 0x02014b50, true); c.setUint16(4, 10, true); c.setUint16(6, 10, true); c.setUint16(14, 0x21, true);
    c.setUint32(16, crc, true); c.setUint32(20, data.length, true); c.setUint32(24, data.length, true);
    c.setUint16(28, nameBytes.length, true); c.setUint32(42, offset, true); entry.set(nameBytes, 46);
    parts.push(local, data);
    central.push(entry);
    offset += local.length + data.length;
  }
  const size = central.reduce((n, e) => n + e.length, 0);
  const end = new Uint8Array(22), e = new DataView(end.buffer);
  e.setUint32(0, 0x06054b50, true); e.setUint16(8, files.length, true); e.setUint16(10, files.length, true);
  e.setUint32(12, size, true); e.setUint32(16, offset, true);
  const out = new Uint8Array(offset + size + 22);
  let at = 0;
  for (const part of [...parts, ...central, end]) { out.set(part, at); at += part.length; }
  return out;
}

export class BackupError extends Error {
  constructor(message, kind) { super(message); this.name = 'BackupError'; this.kind = kind; }
}

// The image must match the manifest's SHA-256, be signed with the XP3800 stock
// certificate and not be patched. kind: 'file' (not a backup) or 'stock'.
async function checkStock(image, manifest) {
  try { checkBootImage(image); } catch { throw new BackupError('Not a full 32 MiB boot image.', 'file'); }
  if (await sha256(image) !== manifest.sha256) throw new BackupError('The image does not match its manifest.', 'file');
  let signature;
  try { signature = await verifyBootSignature(image); } catch (error) { throw new BackupError(error.message, 'stock'); }
  if (signature.certSha256 !== xp3800BootCertSha256) throw new BackupError('Not signed with the XP3800 stock certificate.', 'stock');
  if (await isMagiskPatched(image)) throw new BackupError('Patched by Magisk.', 'stock');
}

// files: the File objects the user chose (a .wavebackup, or an old .img + .json).
export async function readBackup(files) {
  const list = [...files];
  let manifest, image;
  try {
    if (list.length === 1) {
      const file = list[0];
      if (file.size > bootBytes + 64 * 1024) throw new Error('too large');
      const zip = new Uint8Array(await file.arrayBuffer());
      const entries = zipDirectory(zip);
      manifest = parseBackupManifest(new TextDecoder().decode(await zipEntry(zip, entries, 'manifest.json', manifestLimit)));
      image = await zipEntry(zip, entries, 'boot.img', bootBytes);
    } else if (list.length === 2) {
      const json = list.find(f => /\.json$/i.test(f.name)), img = list.find(f => f !== json);
      if (!json || json.size > manifestLimit || img.size !== bootBytes) throw new Error('not a pair');
      manifest = parseBackupManifest(await json.text());
      image = new Uint8Array(await img.arrayBuffer());
    } else throw new Error('wrong count');
  } catch (error) {
    throw new BackupError(`Not a Wave backup (${error.message}).`, 'file');
  }
  await checkStock(image, manifest);
  return { manifest, image };
}
