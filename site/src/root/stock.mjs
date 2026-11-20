// Write the phone's own stock boot image back over EDL and verify the whole partition.
import { $, chooserClosed, isWebUsb, problem, show } from './common.mjs';
import { hashPattern, manifestLimit, parseBackupManifest } from './backup-manifest.mjs';
import { connectPhone, openEdl } from './device.mjs';
import { isMagiskPatched } from './magisk.mjs';
import { bootBytes, checkBootImage, sha256 } from '../protocols/boot-image.mjs';
import { verifyBootSignature, xp3800BootCertSha256 } from '../protocols/boot-signature.mjs';
import { FirehoseBootWriter } from '../protocols/firehose-write.mjs';
import { fetchProgrammer, servedFiles } from './served.mjs?v=device-copy';

const progress = $('progress');
let busy = false, checked, programmer, beforeUrl;

function render() {
  const usb = isWebUsb();
  $('check-files').disabled = busy;
  $('to-edl').disabled = busy || !usb || !programmer || !checked?.manifest;
  $('write').disabled = busy || !usb || !checked || !programmer || !$('same-phone').checked;
  for (const id of ['image', 'manifest', 'expected', 'same-phone']) $(id).disabled = busy;
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
  catch (error) { show($(statusId), chooserClosed(error) ? '' : problem(error), !chooserClosed(error)); }
  finally { busy = false; setProgress(); render(); }
}

for (const id of ['image', 'manifest', 'expected']) $(id).addEventListener('input', () => {
  checked = undefined;
  $('backup-detail').hidden = true;
  show($('files-status'), '');
  render();
});
$('same-phone').addEventListener('change', render);

$('check-files').addEventListener('click', () => task('files-status', async say => {
  checked = undefined;
  const file = $('image').files[0], manifestFile = $('manifest').files[0];
  let expected = $('expected').value.trim().toLowerCase(), manifest;
  if (expected && !hashPattern.test(expected)) throw new Error('Enter a 64-character SHA-256 or leave it blank.');
  if (manifestFile) {
    if (manifestFile.size > manifestLimit) throw new Error('Backup manifest exceeds 4 KiB.');
    manifest = parseBackupManifest(await manifestFile.text());
    if (expected && expected !== manifest.sha256) throw new Error('Manifest and SHA-256 do not match.');
    expected = manifest.sha256;
  }
  if (!expected) throw new Error('Select the backup manifest or enter the recorded SHA-256.');
  if (!file || file.size !== bootBytes) throw new Error('Select the full 32 MiB boot image.');
  say('Checking image...');
  const image = new Uint8Array(await file.arrayBuffer());
  checkBootImage(image);
  const hash = await sha256(image);
  if (hash !== expected) throw new Error('Image does not match recorded SHA-256. Do not use.');
  const signature = await verifyBootSignature(image);
  if (signature.certSha256 !== xp3800BootCertSha256) throw new Error('Image is not signed with the XP3800 stock boot certificate.');
  if (await isMagiskPatched(image)) throw new Error('Image is patched by Magisk and is not a stock backup.');
  checked = { image, hash, manifest };
  $('backup-detail').textContent = `SHA-256: ${hash}\nStock signature verified.` + (manifest ? `\nSerial: ${manifest.serial}\nBuild: ${manifest.fingerprint}\nSaved: ${manifest.date}` : '');
  $('backup-detail').hidden = false;
  say(manifest ? 'Stock-signed XP3800 boot image matching manifest.' : 'Stock-signed XP3800 boot image matching SHA-256. Ensure it came from this device.');
}));

$('to-edl').addEventListener('click', () => task('edl-status', async say => {
  const { serial, fingerprint } = checked.manifest;
  const { adb } = await connectPhone(say, { expect: { serial, fingerprint } });
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
    const phases = { check: [0, 'Reading boot partition.'], write: [1, 'Writing stock image. Keep device connected.'], verify: [2, 'Reading the whole partition back.'] };
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
  await writer.reset().catch(() => { resetNote = ' Restart not acknowledged. If the screen remains dark, hold the power key.'; });
  say((result.written ? 'Stock boot image written; all 32 MiB read back match the backup exactly.' : 'The boot partition already contains this stock image; nothing was written.') + resetNote);
}));

window.addEventListener('pagehide', () => { if (beforeUrl) URL.revokeObjectURL(beforeUrl); });
async function loadServedProgrammer() {
  const status = [$('edl-status'), $('write-status')];
  for (const element of status) show(element, 'Checking the bundled Sonim programmer…');
  try {
    const base = new URL('../files/', document.baseURI);
    const entries = await servedFiles(base);
    programmer = await fetchProgrammer(base, entries.programmer);
    for (const element of status) show(element, '');
  } catch (error) {
    for (const element of status) show(element, `${problem(error)} Reload the page or contact the site maintainer.`, true);
  } finally { render(); }
}
render();
if (isWebUsb()) loadServedProgrammer();
else show($('write-status'), 'Writing requires WebUSB: use Chrome or Edge on a secure (HTTPS) page.', true);
