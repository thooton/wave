// Patch the phone's own stock boot image on the phone with Magisk 24.0, as
// docs/root.md and ROOTING.md step 4b do by hand: Magisk's own ARM binaries and
// boot_patch.sh run as the adb shell user, then the app's own SignBoot signs the
// result through dalvikvm. Nothing here needs root or writes outside the work folder.
import { quoteShell } from '../protocols/adb.mjs';
import { pull, push } from '../protocols/adb-sync.mjs';
import { bootBytes, sha256 } from '../protocols/boot-image.mjs';
import { checkPatchedBoot, parseBootHeader } from '../protocols/boot-signature.mjs';
import { zipDirectory, zipEntry } from '../protocols/zip.mjs';

export const MAGISK = Object.freeze({
  version: '24.0', versionCode: 24000, package: 'com.topjohnwu.magisk',
  size: 10440388, sha256: 'f7ac4e2a1a528ebaa1cdc20c0bd06a785e17b226e97823389779bf7c4d44346a',
});
export const WAVE_PACKAGE = 'com.wave.xp3';
export const workDir = '/data/local/tmp/wave-magisk';
const FILES = [
  ['assets/boot_patch.sh', 'boot_patch.sh'], ['assets/util_functions.sh', 'util_functions.sh'],
  ['lib/armeabi-v7a/libmagiskboot.so', 'magiskboot'], ['lib/armeabi-v7a/libmagiskinit.so', 'magiskinit'],
  ['lib/armeabi-v7a/libmagisk32.so', 'magisk32'], ['lib/armeabi-v7a/libbusybox.so', 'busybox'],
];
// 24.0's util_functions.sh names com.topjohnwu.signing.SignBoot, which is not in the APK.
const signer = `cd ${workDir} && ANDROID_DATA=${workDir}/art /system/bin/dalvikvm -Xnoimage-dex2oat -cp ${workDir}/magisk.apk com.topjohnwu.magisk.signing.SignBoot`;

export async function checkMagiskApk(bytes) {
  if (bytes.length !== MAGISK.size || await sha256(bytes) !== MAGISK.sha256) {
    throw new Error('This is not the official Magisk 24.0 APK (Magisk-v24.0.apk, SHA-256 f7ac4e2a…346a). Use exactly that release.');
  }
}

export async function magiskFiles(apk) {
  const entries = zipDirectory(apk);
  const out = [];
  for (const [entry, name] of FILES) out.push([name, await zipEntry(apk, entries, entry)]);
  return out;
}

// Is this boot image already patched by Magisk? Looks for Magisk's backup
// marker in a gzip ramdisk; returns undefined when the ramdisk isn't gzip.
export async function isMagiskPatched(image) {
  const header = parseBootHeader(image);
  const at = header.page + Math.ceil(header.kernel / header.page) * header.page;
  const ramdisk = image.subarray(at, at + header.ramdisk);
  if (ramdisk[0] !== 0x1f || ramdisk[1] !== 0x8b) return undefined;
  let cpio;
  try {
    const stream = new Blob([ramdisk]).stream().pipeThrough(new DecompressionStream('gzip'));
    cpio = new Uint8Array(await new Response(stream).arrayBuffer());
  } catch { return undefined; }
  const text = new TextDecoder('latin1').decode(cpio);
  return text.includes('.backup/.magisk') || text.includes('overlay.d/sbin/magisk');
}

async function run(adb, command, what, timeoutMs = 60000) {
  const result = await adb.shell(command, undefined, timeoutMs);
  if (result.code !== 0) {
    const detail = (result.stderr || result.stdout).trim().split('\n').slice(-3).join(' ');
    throw new Error(`${what} failed on the phone (exit ${result.code})${detail ? `: ${detail}` : '.'}`);
  }
  return result.stdout;
}

// Free space in KiB for the folder, from toybox df.
async function freeKiB(adb, path) {
  const line = (await run(adb, `df -k ${quoteShell(path)} | tail -n 1`, 'Checking free space')).trim().split(/\s+/);
  const free = Number(line[3]);
  if (!Number.isInteger(free)) throw new Error('Could not read the free space on the phone.');
  return free;
}

// stock: the phone's verified 32 MiB backup; apk: the Magisk APK, already
// checked with checkMagiskApk. log(message) reports each step. certSha256 is
// the stock signing certificate (tests use their own). Returns the signed image.
export async function patchOnPhone(adb, { stock, apk, log = () => {}, certSha256 }) {
  if (stock.length !== bootBytes) throw new Error('Patch only a full 32 MiB stock backup.');
  const files = await magiskFiles(apk);
  const apkSha = await sha256(apk);
  const props = (await run(adb, 'getprop ro.build.system_root_image; getprop ro.crypto.state', 'Reading the phone’s properties')).split('\n');
  if (props[0].trim() === 'true') throw new Error('This phone reports system-as-root, which the XP3800 does not use. Stopping.');
  // As the Magisk app decides: keep forced encryption on an encrypted phone.
  const keepEncrypt = props[1]?.trim() === 'encrypted';
  if (await freeKiB(adb, '/data/local/tmp') < 160 * 1024) throw new Error('The phone needs 160 MB free in its internal storage to patch.');
  await run(adb, `rm -rf ${workDir} && mkdir -p ${workDir}/art/dalvik-cache`, 'Making the work folder');
  try {
    log('Copying Magisk 24.0’s ARM tools to the phone.');
    for (const [name, bytes] of files) await push(adb, `${workDir}/${name}`, bytes);
    log('Copying the stock boot image and the Magisk app to the phone.');
    await push(adb, `${workDir}/boot.img`, stock);
    await push(adb, `${workDir}/magisk.apk`, apk);
    const sums = await run(adb, `cd ${workDir} && sha256sum boot.img magisk.apk`, 'Checking the copies');
    if (!sums.includes(`${await sha256(stock)}  boot.img`) || !sums.includes(`${apkSha}  magisk.apk`)) {
      throw new Error('The files copied to the phone do not match. Check the cable and try again.');
    }
    await run(adb, `cd ${workDir} && chmod 755 magiskboot magiskinit magisk32 busybox boot_patch.sh`, 'Preparing the tools');
    log('Patching the boot image with boot_patch.sh (about a minute).');
    const patchLog = await run(adb, `cd ${workDir} && KEEPVERITY=false KEEPFORCEENCRYPT=${keepEncrypt} RECOVERYMODE=false ASH_STANDALONE=1 ./busybox sh ./boot_patch.sh boot.img 2>&1 && test -s new-boot.img`, 'Patching', 300000);
    log(patchLog.trim().split('\n').filter(line => line.startsWith('- ')).join('\n') || 'Patched.');
    log('Signing the patched image with Magisk’s SignBoot (about a minute).');
    await run(adb, `${signer} -sign < new-boot.img > signed.img`, 'Signing', 300000);
    const verdict = await run(adb, `${signer} -verify < signed.img 2>&1`, 'Checking the signature', 300000);
    if (!verdict.includes('Signature is VALID')) throw new Error('Magisk’s SignBoot did not report a valid signature.');
    const length = Number((await run(adb, `stat -c %s ${workDir}/signed.img`, 'Reading the image size')).trim());
    if (!Number.isInteger(length) || length <= 0 || length >= bootBytes) throw new Error('The signed image has an unexpected size.');
    log('Copying the signed image back to this computer.');
    const signed = await pull(adb, `${workDir}/signed.img`, bootBytes);
    const phoneSum = (await run(adb, `sha256sum ${workDir}/signed.img`, 'Checking the copy')).split(/\s+/)[0];
    if (signed.length !== length || await sha256(signed) !== phoneSum) throw new Error('The signed image did not copy back intact.');
    await checkPatchedBoot(signed, stock, { certSha256 });
    if (await isMagiskPatched(signed) === false) throw new Error('The patched image’s ramdisk has no Magisk files.');
    log(`Signed Magisk image checked in this browser: ${signed.length} bytes, signature verifies with the stock certificate.`);
    return signed;
  } finally {
    await adb.shell(`rm -rf ${workDir}`).catch(() => {});
  }
}

export async function installApk(adb, apk, path, what, timeoutMs = 180000) {
  await push(adb, path, apk);
  try {
    const result = await adb.shell(`pm install -r ${quoteShell(path)}`, undefined, timeoutMs);
    if (result.code !== 0 || !result.stdout.trim().endsWith('Success')) {
      throw new Error(`Installing ${what} failed: ${(result.stderr || result.stdout).trim() || 'no reason given'}`);
    }
  } finally {
    await adb.shell(`rm -f ${quoteShell(path)}`).catch(() => {});
  }
}

export async function installedVersion(adb, pkg) {
  const result = await adb.shell(`dumpsys package ${quoteShell(pkg)} | grep -m 1 versionName=`);
  return /versionName=(\S+)/.exec(result.stdout)?.[1];
}
