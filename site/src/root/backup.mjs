import { $, connectAndroid, isWebUsb, problem, show } from './common.mjs';
import { makeBackupManifest, validateIdentity } from './backup-manifest.mjs';
import { sha256 } from '../protocols/boot-image.mjs';
import { FirehoseBootReader } from '../protocols/firehose.mjs';
import { checkProgrammer, startReadOnlyProgrammer } from '../protocols/sahara.mjs';
import { claimBulk, pickDevice } from '../protocols/webusb.mjs';

const edl = { vendorId: 0x05c6, productId: 0x9008 };
const dump = { vendorId: 0x05c6, productId: 0x900e };
const status = $('backup-status');
const identityStatus = $('identity-status');
const progress = $('backup-progress');
const identify = $('identify'), read = $('read-backup');
let busy = false, captured, objectUrls = [];

function controls() {
  identify.disabled = busy || !isWebUsb();
  read.disabled = busy || !isWebUsb() || !$('same-phone').checked || !$('programmer').files.length;
  for (const id of ['serial', 'fingerprint', 'same-phone', 'programmer']) $(id).disabled = busy;
}

function forgetResult() {
  for (const url of objectUrls) URL.revokeObjectURL(url);
  objectUrls = [];
  for (const id of ['downloads', 'backup-result', 'backup-progress', 'progress-label']) $(id).hidden = true;
  progress.value = 0;
}

function setDownload(id, blob, filename) {
  const url = URL.createObjectURL(blob);
  objectUrls.push(url);
  $(id).href = url;
  $(id).download = filename;
}

function identity() {
  const serial = $('serial').value.trim();
  const fingerprint = $('fingerprint').value.trim();
  validateIdentity(serial, fingerprint);
  const source = captured?.serial === serial && captured?.fingerprint === fingerprint ? 'android-usb' : 'owner-entered';
  return { serial, fingerprint, source };
}

async function selectEdl() {
  const loopback = ['localhost', '127.0.0.1', '[::1]'].includes(location.hostname);
  if (loopback && new URL(location.href).searchParams.get('policy') === '1') {
    const matches = (await navigator.usb.getDevices()).filter(d => d.vendorId === edl.vendorId && [edl.productId, dump.productId].includes(d.productId));
    if (matches.length !== 1) throw new Error('Loopback QA policy must grant exactly one EDL device.');
    return matches[0];
  }
  return pickDevice([edl, dump]);
}

function readableError(error, stage) {
  if (stage === 'chooser' && error.name === 'NotFoundError') return 'No device selected. Check the cable and confirm the phone appears as 05c6:9008.';
  if (stage === 'claim') return 'Could not claim the EDL interface. Close desktop service tools and check udev permissions and the bound driver in the USB guide.';
  if (/timed out/i.test(error.message)) return `${stage} timed out. No partition was written. Check the cable, close other USB tools, and return the phone to Android or re-enter EDL before retrying.`;
  if (/GPT|partition array|sector/i.test(error.message)) return `XP3800 layout not verified. ${error.message} Do not use this tool on an unknown layout.`;
  return error.message || 'The backup failed.';
}

identify.addEventListener('click', async () => {
  busy = true;
  controls();
  let connection;
  try {
    show(identityStatus, 'Select your Sonim Android device.');
    connection = await connectAndroid(identityStatus);
    const { serial, fingerprint, battery } = connection;
    validateIdentity(serial, fingerprint);
    captured = { serial, fingerprint };
    forgetResult();
    $('serial').value = serial;
    $('fingerprint').value = fingerprint;
    $('same-phone').checked = false;
    show(identityStatus, `Android identity recorded. Battery: ${battery}%. ${battery < 40 ? 'Charge to at least 40% before continuing.' : 'Connect the same device in EDL next.'}`, battery < 40);
  } catch (error) {
    show(identityStatus, `${problem(error)} You may enter identity from your existing records instead.`, true);
  } finally {
    await connection?.adb.close();
    busy = false;
    controls();
  }
});
for (const id of ['serial', 'fingerprint', 'same-phone', 'programmer']) $(id).addEventListener('input', () => {
  if (id === 'serial' || id === 'fingerprint') { captured = undefined; $('same-phone').checked = false; }
  forgetResult();
  controls();
});

read.addEventListener('click', async () => {
  busy = true;
  controls();
  forgetResult();
  let stage = 'input check', link, reader;
  try {
    const details = identity(), file = $('programmer').files[0];
    if (!$('same-phone').checked) throw new Error('Confirm this is the same phone before continuing.');
    if (!file || file.size !== 285524) throw new Error('Select the pinned XP3800 Sonim-signed programmer.');
    stage = 'chooser';
    show(status, 'Select the XP3800 in Qualcomm EDL mode.');
    const device = await selectEdl();
    if (device.productId === dump.productId) throw new Error('05c6:900e is crash-dump mode, not EDL. Disconnect and use the cold-start instructions.');
    stage = 'programmer check';
    const image = new Uint8Array(await file.arrayBuffer());
    await checkProgrammer(image);
    stage = 'claim';
    link = await claimBulk(device, { filter: edl, timeoutMs: 8000 });
    stage = 'Sahara handoff';
    show(status, 'Starting the pinned Sonim service programmer.');
    await startReadOnlyProgrammer(link, image);
    reader = new FirehoseBootReader(link);
    stage = 'GPT check';
    show(status, 'Checking the eMMC partition table and XP3800 layout.');
    await reader.configure();
    const { partitions, boot } = await reader.readGpt();
    $('progress-label').hidden = progress.hidden = false;
    function update(pass, done, total) {
      const percent = Math.floor(done / total * 50) + (pass - 1) * 50;
      if (progress.value !== percent) progress.value = percent;
    }
    stage = 'first boot read';
    show(status, 'Reading the 32 MiB boot partition (1 of 2).');
    const first = await reader.readBoot((...args) => update(1, ...args));
    stage = 'second boot read';
    show(status, 'Reading the same partition again (2 of 2).');
    const second = await reader.readBoot((...args) => update(2, ...args));
    stage = 'verification';
    show(status, 'Comparing the complete copies and computing SHA-256.');
    if (first.some((byte, index) => byte !== second[index])) throw new Error('The two independent boot reads differ. No backup was offered.');
    const manifest = makeBackupManifest(details, await sha256(first));
    let resetWarning = '';
    try { await reader.reset(); }
    catch { resetWarning = ' Android reset was not acknowledged; check the phone before disconnecting or retrying.'; }
    reader = undefined;
    const name = `xp3800-${manifest.serial}-boot`;
    setDownload('boot-download', new Blob([first], { type: 'application/octet-stream' }), `${name}.img`);
    setDownload('manifest-download', new Blob([JSON.stringify(manifest, null, 2) + '\n'], { type: 'application/json' }), `${name}.json`);
    $('backup-result').textContent = `Partition table: ${partitions.length} entries, boot at LBA ${boot.start}\nBytes: ${manifest.size}\nSHA-256: ${manifest.sha256}`;
    $('backup-result').hidden = $('downloads').hidden = false;
    show(status, 'Two full reads match byte for byte. Save the image and manifest.' + resetWarning, !!resetWarning);
  } catch (error) {
    if (reader) await reader.reset().catch(() => {});
    show(status, readableError(error, stage), true);
  } finally {
    await link?.close();
    busy = false;
    controls();
  }
});
window.addEventListener('pagehide', forgetResult);
controls();
