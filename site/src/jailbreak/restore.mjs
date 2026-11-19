import { bootBytes, checkBootImage } from '../protocols/gpt.mjs';

const $ = id => document.getElementById(id);
const button = $('verify-backup'), status = $('verify-status'), output = $('verification');
const sha256 = async bytes => Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)), b => b.toString(16).padStart(2, '0')).join('');

if (globalThis.crypto?.subtle) {
  button.disabled = false;
  status.textContent = 'Choose a backup and, if available, enter the SHA-256 recorded when it was made.';
}
for (const id of ['stock', 'expected', 'second']) $(id).addEventListener('input', () => {
  output.hidden = true;
  status.textContent = 'The selection changed. Run the check again.';
  status.removeAttribute('data-error');
});
button.addEventListener('click', async () => {
  button.disabled = true;
  output.hidden = true;
  const selected = [$('stock').files[0], $('second').files[0]];
  const expected = $('expected').value.trim().toLowerCase();
  for (const id of ['stock', 'expected', 'second']) $(id).disabled = true;
  try {
    status.removeAttribute('data-error');
    status.textContent = 'Checking the boot image locally…';
    if (expected && !/^[a-f0-9]{64}$/.test(expected)) throw new Error('Enter a 64-character SHA-256 or leave it blank.');
    if (!selected[0] || selected.filter(Boolean).some(file => file.size !== bootBytes)) throw new Error('Choose a full 32 MiB XP3800 boot image for each file.');
    const original = new Uint8Array(await selected[0].arrayBuffer());
    checkBootImage(original);
    const actual = await sha256(original);
    output.textContent = `Bytes: ${original.length}\nSHA-256: ${actual}`;
    output.hidden = false;
    if (expected && expected !== actual) throw new Error('SHA-256 does not match the original record. Do not use this backup.');
    if (selected[1]) {
      const comparison = new Uint8Array(await selected[1].arrayBuffer());
      if (original.some((byte, index) => byte !== comparison[index])) throw new Error('The two whole-partition files differ. Do not treat this as a verified restore.');
      output.textContent += '\nThe two files match byte for byte.';
    }
    status.textContent = expected ? 'Backup matches the recorded SHA-256.' : 'Image structure checked. No recorded hash supplied; verify provenance before using it.';
  } catch (error) {
    status.textContent = error.message;
    status.setAttribute('data-error', '');
  } finally {
    for (const id of ['stock', 'expected', 'second']) $(id).disabled = false;
    button.disabled = false;
  }
});
