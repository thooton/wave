// Write the phone's own stock boot image back over EDL and verify the whole partition.
import { $, isWebUsb, problem, show } from './common.mjs';
import { hashPattern, manifestLimit, parseBackupManifest } from './backup-manifest.mjs';
import { connectPhone, openEdl } from './device.mjs';
import { isMagiskPatched } from './magisk.mjs';
import { bootBytes, checkBootImage, sha256 } from '../protocols/boot-image.mjs';
import { verifyBootSignature, xp3800BootCertSha256 } from '../protocols/boot-signature.mjs';
import { FirehoseBootWriter } from '../protocols/firehose-write.mjs';
import { checkProgrammer } from '../protocols/sahara.mjs';
import { fetchServed, servedFiles } from './served.mjs';

const progress = $('progress');
let busy = false, checked, programmer, beforeUrl;

function render() {
  const usb = isWebUsb();
  $('check-files').disabled = busy;
  $('to-edl').disabled = busy || !usb || !checked?.manifest;
  $('write').disabled = busy || !usb || !checked || !programmer || !$('same-phone').checked;
  for (const id of ['programmer', 'image', 'manifest', 'expected', 'same-phone']) $(id).disabled = busy;
}
function setProgress(fraction) {
  $('progress-label').hidden = progress.hidden = fraction === undefined;
  if (fraction !== undefined) progress.value = Math.floor(fraction * 100);
}
async function task(statusId, action) {
  if (busy) return;
  busy = true;
  render();
  try { await action(message => show($(statusId), message)); }
  catch (error) { show($(statusId), problem(error), true); }
  finally { busy = false; setProgress(); render(); }
}

for (const id of ['image', 'manifest', 'expected']) $(id).addEventListener('input', () => {
  checked = undefined;
  $('backup-detail').hidden = true;
  show($('files-status'), '');
  render();
});
$('same-phone').addEventListener('change', render);
$('programmer').addEventListener('change', async () => {
  programmer = undefined;
  try {
    const file = $('programmer').files[0];
    if (file) {
      const bytes = new Uint8Array(await file.arrayBuffer());
      await checkProgrammer(bytes);
      programmer = bytes;
      show($('files-status'), 'Programmer checked.');
    }
  } catch (error) {
    $('programmer').value = '';
    show($('files-status'), error.message, true);
  }
  render();
});

$('check-files').addEventListener('click', () => task('files-status', async say => {
  checked = undefined;
  const file = $('image').files[0], manifestFile = $('manifest').files[0];
  let expected = $('expected').value.trim().toLowerCase(), manifest;
  if (expected && !hashPattern.test(expected)) throw new Error('Enter a 64-character SHA-256 or leave it blank.');
  if (manifestFile) {
    if (manifestFile.size > manifestLimit) throw new Error('Backup manifest exceeds 4 KiB.');
    manifest = parseBackupManifest(await manifestFile.text());
    if (expected && expected !== manifest.sha256) throw new Error('The manifest and the entered SHA-256 disagree.');
    expected = manifest.sha256;
  }
  if (!expected) throw new Error('Choose the backup’s manifest or enter the SHA-256 you recorded when you saved it.');
  if (!file || file.size !== bootBytes) throw new Error('Choose the full 32 MiB boot image.');
  say('Checking the image.');
  const image = new Uint8Array(await file.arrayBuffer());
  checkBootImage(image);
  const hash = await sha256(image);
  if (hash !== expected) throw new Error('The image does not match its recorded SHA-256. Do not use it.');
  const signature = await verifyBootSignature(image);
  if (signature.certSha256 !== xp3800BootCertSha256) throw new Error('The image is not signed with the XP3800’s stock boot certificate.');
  if (await isMagiskPatched(image)) throw new Error('This image is patched by Magisk, so it is not a stock backup.');
  checked = { image, hash, manifest };
  $('backup-detail').textContent = `SHA-256: ${hash}\nStock signature verifies.` + (manifest ? `\nSerial: ${manifest.serial}\nBuild: ${manifest.fingerprint}\nSaved: ${manifest.date}` : '');
  $('backup-detail').hidden = false;
  say(manifest ? 'This is a stock-signed XP3800 boot image matching its manifest.' : 'This is a stock-signed XP3800 boot image matching the SHA-256. Check it came from this phone.');
}));

$('to-edl').addEventListener('click', () => task('edl-status', async say => {
  const { serial, fingerprint } = checked.manifest;
  const { adb } = await connectPhone(say, { expect: { serial, fingerprint }, minBattery: 25 });
  try {
    $('same-phone').checked = true;
    say('Serial and build match the manifest. Restarting into EDL; then write in step 3.');
    await adb.reboot('edl');
  } finally { await adb.close(); render(); }
}));

$('write').addEventListener('click', () => task('write-status', async say => {
  const { image } = checked;
  const link = await openEdl(say, programmer);
  const writer = new FirehoseBootWriter(link);
  let result;
  try {
    await writer.configure();
    await writer.readGpt();
    const phases = { check: [0, 'Reading what boot holds now.'], write: [1, 'Writing the stock image. Keep the phone connected.'], verify: [2, 'Reading the whole partition back.'] };
    let last;
    result = await writer.writeBoot(image, {
      onProgress(phase, done, total) {
        if (phase !== last) { say(phases[phase][1]); last = phase; }
        setProgress((phases[phase][0] + done / total) / 3);
      },
    });
  } catch (error) {
    if (!writer.touched) await writer.reset().catch(() => {});
    throw writer.touched ? new Error(`${error.message} Don’t restart Android. Unplug, take the battery out, start EDL cold and write again.`) : error;
  }
  if (beforeUrl) URL.revokeObjectURL(beforeUrl);
  beforeUrl = URL.createObjectURL(new Blob([result.before], { type: 'application/octet-stream' }));
  $('before-link').href = beforeUrl;
  $('before-link').download = `xp3800-boot-before-stock-${new Date().toISOString().slice(0, 10)}.img`;
  $('before-download').hidden = false;
  say('Restarting Android.');
  let resetNote = '';
  await writer.reset().catch(() => { resetNote = ' The restart was not acknowledged: if the screen stays dark, hold the power key.'; });
  say((result.written ? 'Stock boot written; all 32 MiB read back match the backup byte for byte.' : 'boot already held this exact stock image; nothing was written.') + resetNote);
}));

window.addEventListener('pagehide', () => { if (beforeUrl) URL.revokeObjectURL(beforeUrl); });
// The site may serve the programmer (build.py --programmer); then there is nothing to choose.
async function loadServedProgrammer() {
  const base = new URL('../files/', document.baseURI);
  const { programmer: entry } = await servedFiles(base);
  if (!entry) return;
  const bytes = await fetchServed(base, entry);
  await checkProgrammer(bytes);
  if (programmer) return;
  programmer = bytes;
  $('programmer-pick').hidden = true;
  render();
}
render();
loadServedProgrammer().catch(() => {});
if (!isWebUsb()) show($('write-status'), 'Writing needs WebUSB: Chrome or Edge on a secure (HTTPS) page.', true);
