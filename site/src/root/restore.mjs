import { bootBytes, checkBootImage, sha256 } from '../protocols/boot-image.mjs';
import { hashPattern, manifestLimit, parseBackupManifest } from './backup-manifest.mjs';

const $ = id => document.getElementById(id);
const button = $('verify-backup'), status = $('verify-status'), output = $('verification');
const fields = ['stock', 'manifest', 'expected', 'second'];

if (globalThis.crypto?.subtle) {
  button.disabled = false;
  status.textContent = 'Choose a backup and, if available, enter the SHA-256 recorded when it was made.';
}
for (const id of fields) $(id).addEventListener('input', () => {
  output.hidden = true;
  status.textContent = 'The selection changed. Run the check again.';
  status.removeAttribute('data-error');
});
button.addEventListener('click', async () => {
  button.disabled = true;
  output.hidden = true;
  const selected = [$('stock').files[0], $('second').files[0]];
  let expected = $('expected').value.trim().toLowerCase();
  const manifestFile = $('manifest').files[0];
  for (const id of fields) $(id).disabled = true;
  try {
    status.removeAttribute('data-error');
    status.textContent = 'Checking the boot image locally…';
    if (expected && !hashPattern.test(expected)) throw new Error('Enter a 64-character SHA-256 or leave it blank.');
    if (!selected[0] || selected.filter(Boolean).some(file => file.size !== bootBytes)) throw new Error('Choose a full 32 MiB XP3800 boot image for each file.');
    let manifest;
    if (manifestFile) {
      if (manifestFile.size > manifestLimit) throw new Error('Backup manifest exceeds 4 KiB.');
      manifest = parseBackupManifest(await manifestFile.text());
      if (expected && expected !== manifest.sha256) throw new Error('The manifest and the entered SHA-256 disagree.');
      expected = manifest.sha256;
    }
    const original = new Uint8Array(await selected[0].arrayBuffer());
    checkBootImage(original);
    const actual = await sha256(original);
    output.textContent = `Bytes: ${original.length}\nSHA-256: ${actual}`;
    if (manifest) output.textContent += `\nAndroid USB serial: ${manifest.serial}\nBuild: ${manifest.fingerprint}\nIdentity source: ${manifest.identitySource}\nSaved: ${manifest.date}`;
    output.hidden = false;
    if (expected && expected !== actual) throw new Error('SHA-256 does not match the original record. Do not use this backup.');
    if (selected[1]) {
      const comparison = new Uint8Array(await selected[1].arrayBuffer());
      if (original.some((byte, index) => byte !== comparison[index])) throw new Error('The two whole-partition files differ. Do not treat this as a verified restore.');
      output.textContent += '\nThe two files match byte for byte.';
    }
    status.textContent = expected ? (manifest ? 'Backup matches the manifest. Check its serial and build against the phone.' : 'Backup matches the recorded SHA-256.') : 'Image structure checked. No recorded hash supplied; verify provenance before using it.';
  } catch (error) {
    status.textContent = error.message;
    status.setAttribute('data-error', '');
  } finally {
    for (const id of fields) $(id).disabled = false;
    button.disabled = false;
  }
});
