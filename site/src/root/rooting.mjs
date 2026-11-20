// The rooting flow (docs/root.md, "Root from the browser"). Each step proves
// the one before it; the stage is kept in localStorage and the two images in
// IndexedDB, so a reload resumes where the phone is.
import { $, isWebUsb, problem, show } from './common.mjs';
import { makeBackupManifest } from './backup-manifest.mjs';
import { connectPhone, differs, openEdl as openEdlWith } from './device.mjs';
import { MAGISK, WAVE_PACKAGE, checkMagiskApk, installApk, installedVersion, isMagiskPatched, patchOnPhone } from './magisk.mjs';
import { bootBytes, sha256 } from '../protocols/boot-image.mjs';
import { checkPatchedBoot, verifyBootSignature, xp3800BootCertSha256 } from '../protocols/boot-signature.mjs';
import { FirehoseBootReader } from '../protocols/firehose.mjs';
import { FirehoseBootWriter, WriteCheckError } from '../protocols/firehose-write.mjs';
import { checkProgrammer } from '../protocols/sahara.mjs';
import { fetchServed, servedFiles } from './served.mjs';

const KEY = 'wave-rooting-v1';
const STAGES = ['check', 'backup', 'save', 'patch', 'flash', 'finish', 'done'];
const VIEWS = ['intro', 'files', ...STAGES];
const progress = $('progress'), log = $('log'), activity = $('activity'), activityLog = $('activity-log');
const steps = [...document.querySelectorAll('[data-step]')];
const actions = [...document.querySelectorAll('[data-actions]')];
let state = load(), busy = false, urls = [];
let view = initialView(), activityView;
const files = { programmer: null, magisk: null, wave: null };
const LABELS = { programmer: 'Sonim programmer', magisk: 'Magisk 24.0 APK', wave: 'Wave APK' };
const SERVED = new URL('files/', document.baseURI);
let staged = {};
const fetched = {};
const has = name => !!(files[name] || staged[name]);
// With every file served by the site, there is nothing to choose: skip that step.
const allServed = () => !!(staged.programmer && staged.magisk && staged.wave);

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
      tx.onerror = tx.onabort = () => reject(tx.error ?? new Error('Browser storage failed.'));
    });
  } finally { handle.close(); }
}
const putImage = (name, bytes) => store('readwrite', s => s.put(bytes, name));
const clearImages = () => store('readwrite', s => s.clear());
async function getImage(name, hash) {
  const bytes = await store('readonly', s => s.get(name));
  if (!(bytes instanceof Uint8Array) || await sha256(bytes) !== hash) {
    throw new Error(name === 'stock'
      ? 'This browser no longer has the backup. Choose your saved boot image again in Save backup.'
      : 'This browser no longer has the patched image. Run Patch boot again.');
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
  if (step === 'files') return view !== 'intro';
  if (step === 'check' && state.stage === 'check' && view === 'intro') return false;
  return step === state.stage || (step === 'check' && state.stage === 'backup')
    || (step === 'patch' && state.stage === 'flash') || (step === 'finish' && state.stage === 'done');
}

function render() {
  const supported = isWebUsb();
  const can = stages => !busy && supported && stages.includes(state.stage);
  const hasApks = has('magisk') && has('wave');
  const missingFiles = (['backup', 'flash'].includes(view) && !has('programmer')) || (view === 'patch' && !hasApks);
  const reached = state.stage === 'check' ? VIEWS.indexOf(view) : VIEWS.indexOf(state.stage);
  $('where').hidden = supported;
  $('start').disabled = !supported || busy;
  $('start-over').hidden = initialView() === 'intro';
  $('choose-files').hidden = !missingFiles;
  $('choose-files').disabled = busy;
  $('back').hidden = state.stage !== 'check' || !['files', 'check'].includes(view);
  $('step-files-item').hidden = allServed() && view !== 'files';
  $('back').disabled = busy;
  $('resume').hidden = state.stage === 'check' || view === state.stage;
  $('resume').disabled = busy;
  $('continue').hidden = state.stage !== 'check';
  $('continue').disabled = !supported || busy;
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
  $('check').disabled = !can(['check', 'backup']);
  $('backup').disabled = !can(['backup']) || !has('programmer');
  $('saved').disabled = !can(['save']);
  $('patch').disabled = !can(['patch', 'flash']) || !hasApks;
  $('flash').disabled = !can(['flash']) || !has('programmer');
  $('finish').disabled = !can(['finish', 'done']);
  $('grant').disabled = $('open-wave').disabled = !can(['done']);
  for (const id of ['programmer', 'magisk', 'wave', 'start-over']) $(id).disabled = busy;
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
$('start').addEventListener('click', () => selectView(allServed() ? 'check' : 'files'));
$('continue').addEventListener('click', () => selectView('check'));
$('back').addEventListener('click', () => selectView(view === 'check' && !allServed() ? 'files' : 'intro'));
$('resume').addEventListener('click', () => selectView(state.stage));
$('choose-files').addEventListener('click', () => selectView('files'));

// ---- Files ----
async function readFile(input, check) {
  const file = input.files[0];
  if (!file) return null;
  const bytes = new Uint8Array(await file.arrayBuffer());
  await check(bytes);
  return bytes;
}
async function checkApk(bytes) {
  if (bytes.length < 22 || bytes[0] !== 0x50 || bytes[1] !== 0x4b) throw new Error('The Wave file is not an APK.');
}
for (const [id, check, label] of [['programmer', checkProgrammer, 'Programmer'], ['magisk', checkMagiskApk, 'Magisk 24.0'], ['wave', checkApk, 'Wave APK']]) {
  $(id).addEventListener('change', async () => {
    files[id] = null;
    render();
    try {
      files[id] = await readFile($(id), check);
      show($('files-status'), files[id] ? `${label} checked.` : '');
    } catch (error) {
      $(id).value = '';
      show($('files-status'), error.message, true);
    }
    render();
  });
}
// A build may serve the programmer, Magisk and Wave from this site (build.py).
async function loadStaged() {
  staged = await servedFiles(SERVED);
  for (const name of Object.keys(staged)) $(`${name}-pick`).hidden = true;
  const served = Object.keys(staged).filter(name => !files[name]);
  if (served.length && !allServed()) show($('files-status'), `This site provides the ${served.map(name => LABELS[name]).join(' and ')}.`);
  render();
}
async function fileFor(name) {
  if (files[name]) return files[name];
  const entry = staged[name];
  if (!entry) throw new Error(`Choose the ${LABELS[name]} in Choose files.`);
  if (!fetched[name]) {
    const bytes = await fetchServed(SERVED, entry);
    if (name === 'programmer') await checkProgrammer(bytes);
    if (name === 'magisk') await checkMagiskApk(bytes);
    fetched[name] = bytes;
  }
  return fetched[name];
}

const connect = say => connectPhone(say, { expect: state.serial ? { serial: state.serial, fingerprint: state.fingerprint } : undefined });
const openEdl = async say => openEdlWith(say, await fileFor('programmer'));

// ---- Steps ----
$('check').addEventListener('click', () => task(async say => {
  const { adb, serial, fingerprint, battery } = await connect(say);
  try {
    const rooted = await adb.shell('command -v su; ls /sbin/magisk /sbin/su 2>/dev/null');
    if (rooted.stdout.trim()) throw new Error('This phone already has su, so it is probably rooted. To start from stock, write its stock boot image back first (Recovery, linked under the steps).');
    note(`XP3800, serial ${serial}, battery ${battery}%.\n${fingerprint}`);
    await clearImages();
    save({ stage: 'backup', serial, fingerprint, stockSha: undefined, patchedSha: undefined });
    say('Restarting into EDL. The screen stays dark; then continue with Read boot.');
    await adb.reboot('edl');
  } finally { await adb.close(); }
}));

$('backup').addEventListener('click', () => task(async say => {
  const link = await openEdl(say);
  const reader = new FirehoseBootReader(link);
  let first;
  try {
    say('Checking the partition table.');
    await reader.configure();
    const { boot, partitions } = await reader.readGpt();
    note(`XP3800 partition table: ${partitions.length} entries, boot at sector ${boot.start}.`);
    say('Reading boot (1 of 2).');
    first = await reader.readBoot((done, total) => setProgress(done / total / 2));
    say('Reading boot again (2 of 2).');
    const second = await reader.readBoot((done, total) => setProgress(0.5 + done / total / 2));
    if (differs(first, second)) throw new Error('The two reads differ. Nothing was saved; check the cable and try again.');
  } catch (error) {
    await reader.reset().catch(() => {});
    throw error;
  }
  say('Restarting Android.');
  let resetNote = '';
  await reader.reset().catch(() => { resetNote = ' The restart was not acknowledged: if the screen stays dark, hold the power key.'; });
  const signature = await verifyBootSignature(first);
  if (signature.certSha256 !== xp3800BootCertSha256) throw new Error('This boot image is not signed with the XP3800’s stock certificate. Stopping.');
  if (await isMagiskPatched(first)) throw new Error('This boot image is already patched by Magisk, so it is not stock. Stopping.');
  const hash = await sha256(first);
  await putImage('stock', first);
  note(`Stock boot: ${first.length} bytes, SHA-256 ${hash}. Two reads match; stock signature verifies.`);
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
  if (file.size !== bootBytes) throw new Error('That file is not the 32 MiB boot image.');
  const bytes = new Uint8Array(await file.arrayBuffer());
  if (await sha256(bytes) !== state.stockSha) throw new Error('That file does not match the backup just read. Save the image again and choose the saved copy.');
  // A reload may have lost the browser's copy; the checked file replaces it.
  await putImage('stock', bytes);
  save({ stage: 'patch' });
  say('The saved file matches the backup byte for byte. Keep it. Continue with Patch boot once Android has started.');
}));

$('patch').addEventListener('click', () => task(async say => {
  const stock = await getImage('stock', state.stockSha);
  const magisk = await fileFor('magisk'), wave = await fileFor('wave');
  const { adb } = await connect(say);
  try {
    say('Patching on the phone. Keep it connected.');
    const patched = await patchOnPhone(adb, { stock, apk: magisk, log: note });
    say('Installing the Magisk app.');
    await installApk(adb, magisk, '/data/local/tmp/wave-magisk.apk', 'Magisk');
    if (await installedVersion(adb, MAGISK.package) !== MAGISK.version) throw new Error('The Magisk app did not install as version 24.0.');
    say('Installing Wave.');
    await installApk(adb, wave, '/data/local/tmp/wave-app.apk', 'Wave');
    const version = await installedVersion(adb, WAVE_PACKAGE);
    if (!version) throw new Error(`The chosen APK is not Wave (${WAVE_PACKAGE}). Choose the Wave APK and run this step again.`);
    note(`Installed Magisk ${MAGISK.version} and Wave ${version}.`);
    await putImage('patched', patched);
    save({ stage: 'flash', patchedSha: await sha256(patched), patchedSize: patched.length });
    say('Patched and checked. Restarting into EDL; then continue with Write boot.');
    await adb.reboot('edl');
  } finally { await adb.close(); }
}));

$('flash').addEventListener('click', () => task(async say => {
  const stock = await getImage('stock', state.stockSha), patched = await getImage('patched', state.patchedSha);
  await checkPatchedBoot(patched, stock);
  const link = await openEdl(say);
  const writer = new FirehoseBootWriter(link);
  let result;
  try {
    say('Checking the partition table.');
    await writer.configure();
    await writer.readGpt();
    const phases = { check: [0, 'Checking that boot still holds your backup.'], write: [1, 'Writing the patched boot. Keep the phone connected.'], verify: [2, 'Reading the whole partition back.'] };
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
      note(`${error.message} Writing your stock backup back.`);
      say('The write did not verify. Writing your stock backup back.');
      try {
        await writer.writeBoot(stock, { onProgress: (phase, done, total) => setProgress(done / total) });
        await writer.reset().catch(() => {});
        save({ stage: 'patch' });
        throw new Error('The patched write did not verify, so the stock image was written back and verified, and Android is restarting. Nothing changed; run Patch boot and Write boot again.');
      } catch (restoreError) {
        if (/written back and verified/.test(restoreError.message)) throw restoreError;
        throw new Error(`The patched write did not verify, and writing stock back failed too (${restoreError.message}). Don’t restart Android: take the battery out, start EDL cold, and write your saved backup on the Recovery page.`);
      }
    }
    if (!writer.touched) await writer.reset().catch(() => {});
    else throw new Error(`${error.message} The write may be incomplete. Take the battery out, start EDL cold, and write your saved backup on the Recovery page.`);
    throw error;
  }
  note(result.written ? `Wrote ${state.patchedSize} bytes at boot; the whole partition reads back as expected.` : 'boot already held the patched image; nothing was written.');
  save({ stage: 'finish' });
  say('Restarting Android.');
  await writer.reset().catch(() => note('The restart was not acknowledged: if the screen stays dark, hold the power key.'));
  say('Written and verified. The first rooted start takes a little longer; then continue with Verify root.');
}));

$('finish').addEventListener('click', () => task(async say => {
  const { adb } = await connect(say);
  try {
    const su = await adb.shell('ls -l /sbin/su');
    if (su.code !== 0 || !/->\s*\.?\/?magisk/.test(su.stdout)) {
      throw new Error('Magisk did not start: /sbin/su is missing. If Android started normally, the stock image may still be in boot; run Patch boot and Write boot again.');
    }
    note('Magisk is running from the patched boot (/sbin/su → magisk).');
    await adb.shell(`monkey -p ${MAGISK.package} -c android.intent.category.LAUNCHER 1`);
    save({ stage: 'done' });
    say('Root works. Magisk is open on the phone: if it shows “Requires Additional Setup”, choose OK and let the phone restart. Then open Wave.');
  } finally { await adb.close(); }
}));

$('grant').addEventListener('click', () => task(async say => {
  const { adb } = await connect(say);
  try {
    say('Answer Magisk’s prompt on the phone now: Grant (choose Forever to remember it).');
    const result = await adb.shell('su -c id', undefined, 40000);
    if (result.code !== 0 || !result.stdout.includes('uid=0(root)')) {
      throw new Error('Magisk did not grant root to USB debugging. You can allow Shell later in Magisk’s Superuser tab.');
    }
    const env = await adb.shell(`su -c 'test -f /data/adb/magisk/util_functions.sh && test -x /data/adb/magisk/busybox'`, undefined, 40000);
    say(env.code === 0 ? 'USB debugging can use root. Magisk’s setup is complete.'
      : 'USB debugging can use root, but Magisk’s setup is not finished: open Magisk and choose OK on “Requires Additional Setup”. Wave needs it for mods.');
  } finally { await adb.close(); }
}));

$('open-wave').addEventListener('click', () => task(async say => {
  const { adb } = await connect(say);
  try {
    const result = await adb.shell(`monkey -p ${WAVE_PACKAGE} -c android.intent.category.LAUNCHER 1`);
    if (result.code !== 0 || /No activities found|monkey aborted/i.test(result.stdout + result.stderr)) throw new Error('Could not open Wave. Find it on the phone’s launcher.');
    await clearImages();
    say('Wave is open on the phone. Your phone is rooted; keep the saved stock image. This page has forgotten its copies.');
  } finally { await adb.close(); }
}));

$('start-over').addEventListener('click', async () => {
  if (!confirm('Forget this page’s progress and its copies of the images? Your saved backup files are not touched.')) return;
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
loadStaged();
if (state.stage === 'save') getImage('stock', state.stockSha).then(offerBackup).catch(error => {
  if (state.stage !== 'save') return;
  activityView = 'save';
  show(activity, error.message, true);
  render();
});
