import { bootBytes } from '../protocols/boot-image.mjs?v=redesign3';

export const hashPattern = /^[0-9a-f]{64}$/;
export const serialPattern = /^[A-Za-z0-9_-]{1,64}$/;
export const fingerprintPattern = /^Sonim\/[A-Za-z0-9._-]+\/XP3800:8\.1\.0\/[A-Za-z0-9._/-]+:[A-Za-z0-9._/-]+$/;
export const guidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
export const manifestLimit = 4096;

export function validateIdentity(serial, fingerprint) {
  if (typeof serial !== 'string' || !serialPattern.test(serial)) throw new Error('Invalid Android USB serial.');
  if (typeof fingerprint !== 'string' || fingerprint.length > 256 || !fingerprintPattern.test(fingerprint)) {
    throw new Error('Invalid Sonim XP3800 Android 8.1 build fingerprint.');
  }
}

// Format 1 is the old separate .json; format 2 is inside a .wavebackup and adds
// the eMMC's GPT disk GUID, so Recovery can recognise the phone in EDL.
export function parseBackupManifest(text) {
  if (typeof text !== 'string' || text.length > manifestLimit) throw new Error('Backup manifest exceeds 4 KiB.');
  let value;
  try { value = JSON.parse(text); } catch { throw new Error('Backup manifest is not valid JSON.'); }
  if (!value || ![1, 2].includes(value.format) || value.model !== 'XP3800' || value.partition !== 'boot' || value.size !== bootBytes
      || typeof value.sha256 !== 'string' || !hashPattern.test(value.sha256) || !['android-usb', 'owner-entered'].includes(value.identitySource)
      || (value.format === 2 && (typeof value.diskGuid !== 'string' || !guidPattern.test(value.diskGuid)))) {
    throw new Error('Expected a Wave XP3800 boot backup manifest with a boot backup size of 32 MiB.');
  }
  const { format, serial, fingerprint, identitySource, date, sha256, diskGuid } = value;
  validateIdentity(serial, fingerprint);
  if (typeof date !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(date)
      || !Number.isFinite(Date.parse(date)) || new Date(date).toISOString() !== date) throw new Error('Invalid backup date.');
  const manifest = { format, model: 'XP3800', serial, fingerprint, identitySource, partition: 'boot', size: bootBytes, sha256, date };
  if (format === 2) manifest.diskGuid = diskGuid;
  return manifest;
}

export function makeBackupManifest(identity, sha256, date = new Date()) {
  const value = { format: identity.diskGuid ? 2 : 1, model: 'XP3800', serial: identity.serial,
    fingerprint: identity.fingerprint, identitySource: identity.source, partition: 'boot', size: bootBytes,
    sha256, date: date.toISOString() };
  if (identity.diskGuid) value.diskGuid = identity.diskGuid;
  return parseBackupManifest(JSON.stringify(value));
}
