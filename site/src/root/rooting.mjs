// The rooting flow (docs/root.md, "Root from the browser"). Each step proves
// the one before it; the stage is kept in localStorage and the two images in
// IndexedDB, so a reload resumes where the phone is.
import { $, isWebUsb, problem, show } from './common.mjs';
import { makeBackupManifest } from './backup-manifest.mjs';
import { connectPhone, differs, openEdl } from './device.mjs';
import { MAGISK, WAVE_PACKAGE, checkMagiskApk, installApk, installedVersion, isMagiskPatched, patchOnPhone } from './magisk.mjs';
import { bootBytes, sha256 } from '../protocols/boot-image.mjs';
import { checkPatchedBoot, verifyBootSignature, xp3800BootCertSha256 } from '../protocols/boot-signature.mjs';
import { FirehoseBootReader } from '../protocols/firehose.mjs';
import { FirehoseBootWriter, WriteCheckError } from '../protocols/firehose-write.mjs';
import { fetchProgrammer, fetchServed, servedFiles } from './served.mjs';

const KEY = 'wave-rooting-v1';
const STAGES = ['check', 'backup', 'save', 'patch', 'flash', 'finish', 'done'];
const VIEWS = ['intro', ...STAGES];
const progress = $('progress'), log = $('log'), activity = $('activity'), activityLog = $('activity-log');
const steps = [...document.querySelectorAll('[data-step]')];
const actions = [...document.querySelectorAll('[data-actions]')];
let state = load(), busy = false, urls = [];
let view = initialView(), activityView;
const SERVED = new URL('files/', document.baseURI);
let staged = {}, programmer;
const fetched = {};

function load() {
  try {
    const value = JSON.parse(localStorage.getItem(KEY));
    if (value && STAGES.includes(value.stage)) return value;
  } catch {}
  return { stage: 'check' };
}
function save(changes) {
  state = { ...state, ...changes };
  localStorage.setItem(KEY, JSON.stringify(state));
  render();
}

// IndexedDB keeps the stock backup and the patched image across a reload.
function db() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open('wave-rooting', 1);
    request.onupgradeneeded = () => request.result.createObjectStore('images');
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}
async function store(mode, use) {
  const handle = await db();
  try {
    return await new Promise((resolve, reject) => {
      const tx = handle.transaction('images', mode), request = use(tx.objectStore('images'));
      tx.oncomplete = () => resolve(request?.result);
      tx.onerror = tx.onabort = () => reject(tx.error ?? new Error('Browser storage error.'));
    });
  } finally { handle.close(); }
}
const putImage = (name, bytes) => store('readwrite', s => s.put(bytes, name));
const clearImages = () => store('readwrite', s => s.clear());
async function getImage(name, hash) {
  const bytes = await store('readonly', s => s.get(name));
  if (!(bytes instanceof Uint8Array) || await sha256(bytes) !== hash) {
    throw new Error(name === 'stock'
      ? 'Backup not found in this browser. Select your boot image again in Save backup.'
      : 'Patched image not found in this browser. Run Patch boot again.');
  }
  return bytes;
}

function note(text) {
  activityLog.hidden = false;
  log.textContent += (log.textContent ? '\n' : '') + text;
}
function setProgress(fraction) {
  $('progress-label').hidden = progress.hidden = fraction === undefined;
  if (fraction !== undefined) progress.value = Math.floor(fraction * 100);
}

function initialView() {
  return state.stage === 'check' && !state.serial ? 'intro' : state.stage;
}
function selectView(next) {
  view = next;
  render();
  $(`step-${view}`).querySelector('h1, h2').focus();
}
function canReview(step) {
  if (busy || step === view) return false;
  if (step === 'intro') return state.stage === 'check';
  if (step === 'check' && state.stage === 'check' && view === 'intro') return false;
  return step === state.stage || (step === 'check' && state.stage === 'backup')
    || (step === 'patch' && state.stage === 'flash') || (step === 'finish' && state.stage === 'done');
}

function render() {
  const supported = isWebUsb();
  const can = stages => !busy && supported && stages.includes(state.stage);
  const installerReady = !!(programmer && staged.magisk && staged.wave);
  const reached = state.stage === 'check' ? VIEWS.indexOf(view) : VIEWS.indexOf(state.stage);
  $('where').hidden = supported;
  $('start').disabled = !supported || busy || !installerReady;
  $('start-over').hidden = initialView() === 'intro';
  $('back').hidden = state.stage !== 'check' || view !== 'check';
  $('back').disabled = busy;
  $('resume').hidden = state.stage === 'check' || view === state.stage;
  $('resume').disabled = busy;
  for (const step of steps) {
    const name = step.dataset.step;
    const selected = name === view;
    $(`step-${name}`).hidden = !selected;
    if (selected) step.setAttribute('aria-current', 'step');
    else step.removeAttribute('aria-current');
    step.toggleAttribute('data-past', VIEWS.indexOf(name) < reached);
    step.disabled = !canReview(name);
  }
  for (const group of actions) group.hidden = group.dataset.actions !== view;
  activity.hidden = activityView !== view;
  activityLog.hidden = activity.hidden || !log.textContent;
  $('check').disabled = !can(['check', 'backup']) || !installerReady;
  $('backup').disabled = !can(['backup']) || !programmer;
  $('saved').disabled = !can(['save']);
  $('patch').disabled = !can(['patch', 'flash']) || !installerReady;
  $('flash').disabled = !can(['flash']) || !programmer;
  $('finish').disabled = !can(['finish', 'done']);
  $('grant').disabled = $('open-wave').disabled = !can(['done']);
  $('start-over').disabled = busy;
  $('save-image').hidden = !$('save-image').hasAttribute('href');
  $('save-manifest').hidden = !$('save-manifest').hasAttribute('href');
}

async function task(action) {
  if (busy) return;
  busy = true;
  const previousStage = state.stage;
  const previousFocus = document.activeElement;
  let succeeded = false;
  activityView = view;
  show(activity, '');
  log.textContent = '';
  activityLog.open = false;
  render();
  try {
    await action(message => show(activity, message));
    succeeded = true;
  } catch (error) {
    show(activity, problem(error), true);
    note(`Stopped: ${problem(error)}`);
  } finally {
    setProgress();
    busy = false;
    if (state.stage !== previousStage || (succeeded && view !== state.stage)) {
      activityView = state.stage;
      selectView(state.stage);
    } else {
      render();
      if (document.activeElement === document.body && !previousFocus.disabled) previousFocus.focus();
    }
  }
}

for (const step of steps) step.addEventListener('click', () => selectView(step.dataset.step));
$('start').addEventListener('click', () => selectView('check'));
$('back').addEventListener('click', () => selectView('intro'));
$('resume').addEventListener('click', () => selectView(state.stage));

// Verify the bundled programmer before allowing a reboot into EDL.
async function loadStaged() {
  const status = $('installer-status');
  show(status, 'Checking installer files…');
  try {
    staged = await servedFiles(SERVED);
    programmer = await fetchProgrammer(SERVED, staged.programmer);
    if (!staged.magisk || !staged.wave) throw new Error('This site build is missing the Magisk or Wave APK.');
    show(status, '');
  } catch (error) {
    show(status, `${problem(error)} Reload the page or contact the site maintainer.`, true);
  } finally { render(); }
}
async function apkFor(name) {
  if (!fetched[name]) {
    const bytes = await fetchServed(SERVED, staged[name]);
    if (name === 'magisk') await checkMagiskApk(bytes);
    else if (bytes.length < 22 || bytes[0] !== 0x50 || bytes[1] !== 0x4b) throw new Error('The bundled Wave file is not an APK.');
    fetched[name] = bytes;
  }
  return fetched[name];
}

const connect = say => connectPhone(say, { expect: state.serial ? { serial: state.serial, fingerprint: state.fingerprint } : undefined });

// ---- Steps ----
$('check').addEventListener('click', () => task(async say => {
  const { adb, serial, fingerprint, battery } = await connect(say);
  try {
    const rooted = await adb.shell('command -v su; ls /sbin/magisk /sbin/su 2>/dev/null');
    if (rooted.stdout.trim()) throw new Error('This phone already has su and is likely rooted. To return to stock, first flash the stock boot image (Recovery, linked in the steps).');
    note(`XP3800, serial ${serial}, battery ${battery}%.\n${fingerprint}`);
    await clearImages();
    save({ stage: 'backup', serial, fingerprint, stockSha: undefined, patchedSha: undefined });
    say('Restarting into EDL. The screen will stay dark; then continue with Read boot.');
    await adb.reboot('edl');
  } finally { await adb.close(); }
}));

$('backup').addEventListener('click', () => task(async say => {
  const link = await openEdl(say, programmer);
  const reader = new FirehoseBootReader(link);
  let first;
  try {
    say('Checking partition table.');
    await reader.configure();
    const { boot, partitions } = await reader.readGpt();
    note(`XP3800 partition table: ${partitions.length} entries, boot partition at sector ${boot.start}.`);
    say('Reading boot image (1 of 2).');
    first = await reader.readBoot((done, total) => setProgress(done / total / 2));
    say('Reading boot image again (2 of 2).');
    const second = await reader.readBoot((done, total) => setProgress(0.5 + done / total / 2));
    if (differs(first, second)) throw new Error('The two reads of the boot partition returned different data. Nothing was saved; check the cable and try again.');
  } catch (error) {
    await reader.reset().catch(() => {});
    throw error;
  }
  say('Restarting Android.');
  let resetNote = '';
  await reader.reset().catch(() => { resetNote = ' Restart not acknowledged: if the screen stays dark, hold the power key.'; });
  const signature = await verifyBootSignature(first);
  if (signature.certSha256 !== xp3800BootCertSha256) throw new Error('Boot image is not signed with the XP3800 stock certificate. Stopping.');
  if (await isMagiskPatched(first)) throw new Error('Boot image is already patched by Magisk and is not stock. Stopping.');
  const hash = await sha256(first);
  await putImage('stock', first);
  note(`Stock boot image: ${first.length} bytes, SHA-256 ${hash}. Two reads match; stock signature verified.`);
  save({ stage: 'save', stockSha: hash, date: new Date().toISOString() });
  await offerBackup(first);
  say(`Backup read.${resetNote} Save it while Android starts.`);
}));

async function offerBackup(bytes) {
  for (const url of urls) URL.revokeObjectURL(url);
  const manifest = makeBackupManifest({ serial: state.serial, fingerprint: state.fingerprint, source: 'android-usb' }, state.stockSha, new Date(state.date));
  const name = `xp3800-${state.serial}-boot`;
  const link = (id, blob, file) => {
    const url = URL.createObjectURL(blob);
    urls.push(url);
    $(id).href = url;
    $(id).download = file;
  };
  link('save-image', new Blob([bytes], { type: 'application/octet-stream' }), `${name}.img`);
  link('save-manifest', new Blob([JSON.stringify(manifest, null, 2) + '\n'], { type: 'application/json' }), `${name}.json`);
  render();
}

$('saved').addEventListener('change', () => task(async say => {
  const file = $('saved').files[0];
  if (!file) return;
  if (file.size !== bootBytes) throw new Error('This file is not the 32 MiB boot image.');
  const bytes = new Uint8Array(await file.arrayBuffer());
  if (await sha256(bytes) !== state.stockSha) throw new Error('File does not match the backup. Save the image again and select the saved copy.');
  // A reload may have lost the browser's copy; the checked file replaces it.
  await putImage('stock', bytes);
  save({ stage: 'patch' });
  say('The saved file matches the backup byte for byte. Keep it. Continue with Patch boot once Android has started.');
}));

$('patch').addEventListener('click', () => task(async say => {
  const stock = await getImage('stock', state.stockSha);
  const [magisk, wave] = await Promise.all([apkFor('magisk'), apkFor('wave')]);
  const { adb } = await connect(say);
  try {
    say('Patching on the phone. Keep it connected.');
    const patched = await patchOnPhone(adb, { stock, apk: magisk, log: note });
    say('Installing Magisk.');
    await installApk(adb, magisk, '/data/local/tmp/wave-magisk.apk', 'Magisk');
    if (await installedVersion(adb, MAGISK.package) !== MAGISK.version) throw new Error('Magisk version 24.0 did not install.');
    say('Installing Wave.');
    await installApk(adb, wave, '/data/local/tmp/wave-app.apk', 'Wave');
    const version = await installedVersion(adb, WAVE_PACKAGE);
    if (!version) throw new Error(`The bundled APK is not Wave (${WAVE_PACKAGE}).`);
    note(`Installed Magisk ${MAGISK.version} and Wave ${version}.`);
    await putImage('patched', patched);
    save({ stage: 'flash', patchedSha: await sha256(patched), patchedSize: patched.length });
    say('Patched and verified. Restarting into EDL; then Write boot.');
    await adb.reboot('edl');
  } finally { await adb.close(); }
}));

$('flash').addEventListener('click', () => task(async say => {
  const stock = await getImage('stock', state.stockSha), patched = await getImage('patched', state.patchedSha);
  await checkPatchedBoot(patched, stock);
  const link = await openEdl(say, programmer);
  const writer = new FirehoseBootWriter(link);
  let result;
  try {
    say('Checking partition table.');
    await writer.configure();
    await writer.readGpt();
    const phases = { check: [0, 'Verifying that the current contents of the boot partition exactly match your backup.'], write: [1, 'Writing patched boot image. Keep the phone connected.'], verify: [2, 'Reading the whole partition back.'] };
    let last;
    result = await writer.writeBoot(patched, {
      current: stock,
      onProgress(phase, done, total) {
        if (phase !== last) { say(phases[phase][1]); last = phase; }
        setProgress((phases[phase][0] + done / total) / 3);
      },
    });
  } catch (error) {
    if (error instanceof WriteCheckError && error.written) {
      // The session is still open: put the stock image back now, verified.
      note(`${error.message} Restoring stock backup.`);
      say('Write verification failed. Restoring stock backup.');
      try {
        await writer.writeBoot(stock, { onProgress: (phase, done, total) => setProgress(done / total) });
        await writer.reset().catch(() => {});
        save({ stage: 'patch' });
        throw new Error('The patched write failed verification, so the stock image was written back and verified. Android is restarting; no changes were made to the boot image. Run Patch boot and Write boot again.');
      } catch (restoreError) {
        if (/written back and verified/.test(restoreError.message)) throw restoreError;
        throw new Error(`The patched write failed verification, and restoring the stock image also failed (${restoreError.message}). Do not restart Android: remove the battery, perform a cold start into EDL, and write your saved backup on the Recovery page.`);
      }
    }
    if (!writer.touched) await writer.reset().catch(() => {});
    else throw new Error(`${error.message} The write may be incomplete. Remove the battery, cold boot into EDL, and write your backup on the Recovery page.`);
    throw error;
  }
  note(result.written ? `Wrote ${state.patchedSize} bytes at boot; the whole partition reads back as expected.` : 'Boot partition already contained the patched image; nothing was written.');
  save({ stage: 'finish' });
  say('Restarting Android.');
  await writer.reset().catch(() => note('Restart not acknowledged. If the screen remains dark, hold the power button.'));
  say('Written and verified. The first rooted boot may take a little longer. Then, continue with Verify root.');
}));

$('finish').addEventListener('click', () => task(async say => {
  const { adb } = await connect(say);
  try {
    const su = await adb.shell('ls -l /sbin/su');
    if (su.code !== 0 || !/->\s*\.?\/?magisk/.test(su.stdout)) {
      throw new Error('Magisk failed to start: /sbin/su is missing. If Android booted normally, the stock image may still be in the boot partition; run Patch boot and Write boot again.');
    }
    note('Magisk is running from the patched boot image (/sbin/su → magisk).');
    await adb.shell(`monkey -p ${MAGISK.package} -c android.intent.category.LAUNCHER 1`);
    save({ stage: 'done' });
    say('Root is working. Magisk is open on the phone: if it displays “Requires Additional Setup”, select OK and let the phone restart. Then, open Wave.');
  } finally { await adb.close(); }
}));

$('grant').addEventListener('click', () => task(async say => {
  const { adb } = await connect(say);
  try {
    say('Respond to the Magisk prompt on the phone: Grant (select Forever to remember).');
    const result = await adb.shell('su -c id', undefined, 40000);
    if (result.code !== 0 || !result.stdout.includes('uid=0(root)')) {
      throw new Error('Magisk did not grant root to USB debugging. You can allow Shell later in Magisk\'s Superuser tab.');
    }
    const env = await adb.shell(`su -c 'test -f /data/adb/magisk/util_functions.sh && test -x /data/adb/magisk/busybox'`, undefined, 40000);
    say(env.code === 0 ? 'USB debugging has root access. Magisk setup is complete.'
      : 'USB debugging has root access, but Magisk setup is not complete. Open Magisk and select OK on “Requires Additional Setup”; this additional setup is required for mods in Wave.');
  } finally { await adb.close(); }
}));

$('open-wave').addEventListener('click', () => task(async say => {
  const { adb } = await connect(say);
  try {
    const result = await adb.shell(`monkey -p ${WAVE_PACKAGE} -c android.intent.category.LAUNCHER 1`);
    if (result.code !== 0 || /No activities found|monkey aborted/i.test(result.stdout + result.stderr)) throw new Error('Could not open Wave. Open it from the phone\'s launcher.');
    await clearImages();
    say('Wave is open. Your phone is rooted. Keep the saved stock image; this page no longer has copies.');
  } finally { await adb.close(); }
}));

$('start-over').addEventListener('click', async () => {
  if (!confirm('Discard progress and cached images? Saved backups will not be affected.')) return;
  busy = true;
  render();
  await clearImages().catch(() => {});
  localStorage.removeItem(KEY);
  for (const url of urls) URL.revokeObjectURL(url);
  urls = [];
  for (const id of ['save-image', 'save-manifest']) $(id).removeAttribute('href');
  $('saved').value = '';
  state = load();
  activityView = undefined;
  show(activity, '');
  log.textContent = '';
  busy = false;
  selectView(initialView());
});

window.addEventListener('pagehide', () => { for (const url of urls) URL.revokeObjectURL(url); });
show($('where'), 'Use Chrome or Edge on a secure page for WebUSB.', true);
render();
if (isWebUsb()) loadStaged();
if (state.stage === 'save') getImage('stock', state.stockSha).then(offerBackup).catch(error => {
  if (state.stage !== 'save') return;
  activityView = 'save';
  show(activity, error.message, true);
  render();
});
