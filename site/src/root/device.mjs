// Connecting to the XP3800 for the rooting and stock-boot pages: Android over
// adb (waiting for boot, checking it is the expected phone) and EDL with the
// pinned programmer.
import { validateIdentity } from './backup-manifest.mjs';
import { Adb } from '../protocols/adb.mjs';
import { newAdbCredential } from '../protocols/adb-credential.mjs';
import { startReadOnlyProgrammer } from '../protocols/sahara.mjs';
import { claimBulk, pickDevice, SONIM_ADB_FILTER } from '../protocols/webusb.mjs';

export const EDL = { vendorId: 0x05c6, productId: 0x9008 }, DUMP = { vendorId: 0x05c6, productId: 0x900e };
export const differs = (a, b) => a.length !== b.length || a.some((byte, i) => byte !== b[i]);
let credential;

async function androidDevice(serial) {
  // A phone already chosen in this browser comes back after a restart.
  const known = (await navigator.usb.getDevices()).filter(d => d.vendorId === SONIM_ADB_FILTER.vendorId && (!serial || d.serialNumber === serial));
  if (known.length === 1) return known[0];
  return pickDevice([SONIM_ADB_FILTER]);
}

const settled = 'Android started; reconnecting.';
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
// The XP3800 drops off USB and comes back once more just after boot completes
// (Android settles its USB configuration), and a link can break mid-command then.
const transient = error => error.usbDevice && (['NetworkError', 'NotFoundError', 'AbortError'].includes(error.name)
  || /cancelled|timed out|disconnected|closed/i.test(error.message));

// expect: { serial, fingerprint } the phone must have, if known. minBattery: refuse below.
export async function connectPhone(say, options = {}) {
  for (let attempt = 0; ; attempt++) {
    try { return await connectOnce(say, options); }
    catch (error) {
      if (attempt >= 4 || !transient(error)) throw error;
      say(error.message === settled ? 'Android has started. Connecting again.' : 'The phone reconnected to USB. Connecting again.');
      await sleep(5000);
    }
  }
}

async function connectOnce(say, { expect, minBattery = 40 }) {
  say('Select the Sonim phone in the USB chooser.');
  const device = await androidDevice(expect?.serial);
  try { return await connectTo(device, say, { expect, minBattery }); }
  catch (error) { error.usbDevice = true; throw error; }
}

async function connectTo(device, say, { expect, minBattery }) {
  // One key per page: after a restart Android asks again unless “Always allow” was set.
  credential ??= await newAdbCredential();
  const adb = await Adb.connect(await claimBulk(device, { filter: SONIM_ADB_FILTER }), {
    credential, onAuthorization: () => say('Accept “Allow USB debugging?” on the phone. Leave “Always allow” off.'),
  });
  try {
    for (let i = 0; ; i++) {
      const booted = await adb.shell('getprop sys.boot_completed');
      if (booted.stdout.trim() === '1') break;
      if (i >= 90) throw new Error('Android did not finish starting. Wait for the home screen and try again.');
      if (i === 0) say('Waiting for Android to finish starting.');
      await sleep(2000);
      // Once it has started, give USB time to settle, then start afresh.
      if ((await adb.shell('getprop sys.boot_completed')).stdout.trim() === '1') {
        await adb.close();
        await sleep(8000);
        throw Object.assign(new Error(settled), { name: 'NetworkError' });
      }
    }
    const result = await adb.shell(`getprop ro.product.model; getprop ro.build.version.sdk; getprop ro.product.cpu.abi; getprop ro.build.fingerprint; dumpsys battery | grep 'level:'`);
    const [model, sdk, abi, fingerprint, batteryLine] = result.stdout.trimEnd().split('\n').map(line => line.trim());
    const battery = Number(/^level:\s*(\d+)$/.exec(batteryLine ?? '')?.[1]);
    if (result.code !== 0 || model !== 'XP3800' || sdk !== '27' || abi !== 'armeabi-v7a' || !fingerprint?.startsWith('Sonim/')) {
      throw new Error('This is not a Sonim XP3800 on Android 8.1 (32-bit ARM). Stopping.');
    }
    const serial = device.serialNumber ?? '';
    validateIdentity(serial, fingerprint);
    if (expect && (serial !== expect.serial || fingerprint !== expect.fingerprint)) {
      throw new Error(`This is not the expected phone (serial ${expect.serial}, build ${expect.fingerprint}).`);
    }
    if (!Number.isInteger(battery) || battery < minBattery) throw new Error(`The battery is at ${battery}%. Charge it to at least ${minBattery}% first.`);
    return { adb, serial, fingerprint, battery };
  } catch (error) {
    await adb.close();
    throw error;
  }
}

async function edlDevice() {
  const loopback = ['localhost', '127.0.0.1', '[::1]'].includes(location.hostname);
  if (loopback && new URL(location.href).searchParams.get('policy') === '1') {
    // Hardware QA only: a managed policy grants exactly one EDL device to a loopback origin.
    const matches = (await navigator.usb.getDevices()).filter(d => d.vendorId === EDL.vendorId && [EDL.productId, DUMP.productId].includes(d.productId));
    if (matches.length !== 1) throw new Error('Loopback QA policy must grant exactly one EDL device.');
    return matches[0];
  }
  return pickDevice([EDL, DUMP]);
}

// programmer: bytes already checked with checkProgrammer. Returns a link with Firehose running.
export async function openEdl(say, programmer) {
  if (!programmer) throw new Error('Choose the service programmer first.');
  say('Select the XP3800 in EDL (Qualcomm, 05c6:9008) in the USB chooser.');
  let device;
  try { device = await edlDevice(); }
  catch (error) {
    if (error.name === 'NotFoundError') throw new Error('No EDL device chosen. If none was listed, wait a few seconds for the phone to restart into EDL, or see the USB guide.');
    throw error;
  }
  if (device.productId === DUMP.productId) throw new Error('05c6:900e is crash-dump mode, not EDL. Unplug, take the battery out, and use the cold start (hold both volume keys while refitting it and plugging in USB).');
  let link;
  try { link = await claimBulk(device, { filter: EDL, timeoutMs: 8000 }); }
  catch { throw new Error('Could not claim the EDL interface. Close other service tools; on Linux a kernel driver (qcserial) may hold it: see the USB guide.'); }
  say('Starting the Sonim service programmer.');
  try { await startReadOnlyProgrammer(link, programmer); }
  catch (error) {
    await link.close();
    throw new Error(`${error.message} If the phone was already in EDL from an earlier try, unplug it, take the battery out and start EDL again.`);
  }
  return link;
}
