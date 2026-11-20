import { bootBytes } from '../protocols/boot-image.mjs';

export const hashPattern = /^[0-9a-f]{64}$/;
export const serialPattern = /^[A-Za-z0-9_-]{1,64}$/;
export const fingerprintPattern = /^Sonim\/[A-Za-z0-9._-]+\/XP3800:8\.1\.0\/[A-Za-z0-9._/-]+:[A-Za-z0-9._/-]+$/;
export const manifestLimit = 4096;

export function validateIdentity(serial, fingerprint) {
  if (typeof serial !== 'string' || !serialPattern.test(serial)) throw new Error('Enter the Android USB serial (letters, numbers, underscores, or hyphens).');
  if (typeof fingerprint !== 'string' || fingerprint.length > 256 || !fingerprintPattern.test(fingerprint)) {
    throw new Error('Enter the complete Sonim XP3800 Android 8.1 build fingerprint.');
  }
}

export function parseBackupManifest(text) {
  if (typeof text !== 'string' || text.length > manifestLimit) throw new Error('Backup manifest exceeds 4 KiB.');
  let value;
  try { value = JSON.parse(text); } catch { throw new Error('Backup manifest is not valid JSON.'); }
  if (!value || value.format !== 1 || value.model !== 'XP3800' || value.partition !== 'boot' || value.size !== bootBytes
      || typeof value.sha256 !== 'string' || !hashPattern.test(value.sha256) || !['android-usb', 'owner-entered'].includes(value.identitySource)) {
    throw new Error('Expected a Wave XP3800 boot backup manifest with a boot backup size of 32 MiB.');
  }
  const { serial, fingerprint, identitySource, date, sha256 } = value;
  validateIdentity(serial, fingerprint);
  if (typeof date !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(date)
      || !Number.isFinite(Date.parse(date)) || new Date(date).toISOString() !== date) throw new Error('Invalid backup date.');
  return { format: 1, model: 'XP3800', serial, fingerprint, identitySource, partition: 'boot', size: bootBytes, sha256, date };
}

export function makeBackupManifest(identity, sha256, date = new Date()) {
  return parseBackupManifest(JSON.stringify({ format: 1, model: 'XP3800', serial: identity.serial,
    fingerprint: identity.fingerprint, identitySource: identity.source, partition: 'boot', size: bootBytes,
    sha256, date: date.toISOString() }));
}
