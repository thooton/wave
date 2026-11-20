import assert from 'node:assert/strict';
import { createSign, createHash, generateKeyPairSync } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import { homedir } from 'node:os';
import test from 'node:test';
import { deflateRawSync, gzipSync } from 'node:zlib';
import { bootBytes } from '../boot-image.mjs';
import { checkPatchedBoot, verifyBootSignature, xp3800BootCertSha256 } from '../boot-signature.mjs';
import { FirehoseBootReader } from '../firehose.mjs';
import { FirehoseBootWriter, WriteCheckError } from '../firehose-write.mjs';
import { crc32, zipDirectory, zipEntry } from '../zip.mjs';
import { isMagiskPatched, magiskFiles, patchOnPhone, workDir } from '../../root/magisk.mjs';

const text = s => new TextEncoder().encode(s);
const b = hex => new Uint8Array(Buffer.from(hex, 'hex'));
const concat = parts => {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let at = 0;
  for (const part of parts) { out.set(part, at); at += part.length; }
  return out;
};
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const recorded = JSON.parse(readFileSync(new URL('./xp3800-firehose.json', import.meta.url)));

// ---- ZIP ----
function makeZip(entries) {
  const locals = [], central = [];
  let offset = 0;
  for (const [name, data, deflate] of entries) {
    const packed = deflate ? new Uint8Array(deflateRawSync(data)) : data, nameBytes = text(name);
    const local = new Uint8Array(30), v = new DataView(local.buffer);
    v.setUint32(0, 0x04034b50, true); v.setUint16(8, deflate ? 8 : 0, true); v.setUint32(14, crc32(data), true);
    v.setUint32(18, packed.length, true); v.setUint32(22, data.length, true); v.setUint16(26, nameBytes.length, true);
    const dir = new Uint8Array(46), w = new DataView(dir.buffer);
    w.setUint32(0, 0x02014b50, true); w.setUint16(10, deflate ? 8 : 0, true); w.setUint32(16, crc32(data), true);
    w.setUint32(20, packed.length, true); w.setUint32(24, data.length, true); w.setUint16(28, nameBytes.length, true); w.setUint32(42, offset, true);
    locals.push(local, nameBytes, packed);
    central.push(dir, nameBytes);
    offset += 30 + nameBytes.length + packed.length;
  }
  const dir = concat(central), end = new Uint8Array(22), v = new DataView(end.buffer);
  v.setUint32(0, 0x06054b50, true); v.setUint16(8, entries.length, true); v.setUint16(10, entries.length, true);
  v.setUint32(12, dir.length, true); v.setUint32(16, offset, true);
  return concat([...locals, dir, end]);
}

test('ZIP entries are read stored or deflated and CRC-checked', async () => {
  const big = text('magiskboot '.repeat(5000));
  const zip = makeZip([['assets/boot_patch.sh', text('#!/sbin/sh\n'), false], ['lib/armeabi-v7a/libmagiskboot.so', big, true]]);
  const entries = zipDirectory(zip);
  assert.deepEqual(await zipEntry(zip, entries, 'assets/boot_patch.sh'), text('#!/sbin/sh\n'));
  assert.deepEqual(await zipEntry(zip, entries, 'lib/armeabi-v7a/libmagiskboot.so'), big);
  await assert.rejects(zipEntry(zip, entries, 'missing'), /no missing/);
  const broken = zip.slice();
  broken[30 + 'assets/boot_patch.sh'.length + 2] ^= 1;
  await assert.rejects(zipEntry(broken, zipDirectory(broken), 'assets/boot_patch.sh'), /CRC/);
  assert.throws(() => zipDirectory(text('not a zip at all, just some text bytes')), /Not a ZIP/);
});

// ---- Synthetic signed boot images (AOSP BootSignature layout) ----
const derLength = n => n < 128 ? [n] : n < 256 ? [0x81, n] : n < 65536 ? [0x82, n >> 8, n & 255] : [0x83, n >> 16, (n >> 8) & 255, n & 255];
const tlv = (tag, ...parts) => { const body = concat(parts); return concat([Uint8Array.of(tag, ...derLength(body.length)), body]); };
const derInt = n => {
  const bytes = [];
  do { bytes.unshift(n % 256); n = Math.floor(n / 256); } while (n);
  if (bytes[0] & 0x80) bytes.unshift(0);
  return tlv(2, Uint8Array.from(bytes));
};
const keys = generateKeyPairSync('rsa', { modulusLength: 2048 });
const spki = new Uint8Array(keys.publicKey.export({ type: 'spki', format: 'der' }));
const certificate = tlv(0x30, tlv(0x30, tlv(0xa0, derInt(2)), derInt(7), tlv(0x30), tlv(0x30), tlv(0x30), tlv(0x30), spki), tlv(0x30), tlv(3, Uint8Array.of(0)));
const testCert = sha(certificate);

function bootPayload(ramdisk, kernel = 5000) {
  const page = 2048, round = n => Math.ceil(n / page) * page;
  const image = new Uint8Array(page + round(kernel) + round(ramdisk.length));
  image.set(text('ANDROID!'));
  const h = new DataView(image.buffer);
  [kernel, 0x80008000, ramdisk.length, 0x82000000, 0, 0x81f00000, 0x80000100, page, 0, 0x10040132].forEach((v, i) => h.setUint32(8 + i * 4, v, true));
  image.set(text('console=ttyHSL0,115200,n8 androidboot.hardware=qcom'), 64);
  for (let i = 0; i < kernel; i++) image[page + i] = (i * 7) & 255;
  image.set(ramdisk, page + round(kernel));
  return image;
}
function signed(payload) {
  const attributes = tlv(0x30, tlv(0x13, text('/boot')), derInt(payload.length));
  const signer = createSign('RSA-SHA256');
  signer.update(payload);
  signer.update(attributes);
  return concat([payload, tlv(0x30, derInt(1), certificate, tlv(0x30, tlv(6, Uint8Array.of(42, 134, 72))), attributes, tlv(4, new Uint8Array(signer.sign(keys.privateKey))))]);
}
const stockRamdisk = new Uint8Array(gzipSync(text('070701 init sbin/adbd init.rc')));
const patchedRamdisk = new Uint8Array(gzipSync(text('070701 init .backup/.magisk overlay.d/sbin/magisk32.xz')));
const stockFull = (() => { const full = new Uint8Array(bootBytes); full.set(signed(bootPayload(stockRamdisk))); return full; })();
const patchedSigned = signed(bootPayload(patchedRamdisk));
const opts = { certSha256: testCert };

test('AVB 1.0 boot signatures verify, and only a signed patch of the same stock image passes', async () => {
  const result = await verifyBootSignature(stockFull);
  assert.equal(result.certSha256, testCert);
  assert.equal(await isMagiskPatched(stockFull), false);
  assert.equal(await isMagiskPatched(patchedSigned), true);
  await checkPatchedBoot(patchedSigned, stockFull, opts);
  // The default pin is the XP3800's real certificate, which this test key is not.
  await assert.rejects(checkPatchedBoot(patchedSigned, stockFull), /not signed with the XP3800 boot certificate/);
  const tampered = patchedSigned.slice();
  tampered[3000] ^= 1;
  await assert.rejects(checkPatchedBoot(tampered, stockFull, opts), /Boot signature verification failed/);
  await assert.rejects(checkPatchedBoot(bootPayload(patchedRamdisk), stockFull, opts), /Boot image is unsigned/);
  await assert.rejects(checkPatchedBoot(concat([patchedSigned, Uint8Array.of(0)]), stockFull, opts), /after the boot signature/);
  await assert.rejects(checkPatchedBoot(signed(bootPayload(stockRamdisk)), stockFull, opts), /stock ramdisk/);
  const otherHeader = bootPayload(patchedRamdisk);
  otherHeader.set(text('console=ttyMSM0'), 64);
  await assert.rejects(checkPatchedBoot(signed(otherHeader), stockFull, opts), /boot header doesn't match/);
  const forged = patchedSigned.slice();
  forged[forged.length - 10] ^= 0xff;
  await assert.rejects(checkPatchedBoot(forged, stockFull, opts), /Boot signature verification failed/);
});

const backups = `${homedir()}/Documents/wave-phone-backups/8516067e/`;
test('the real XP3800 stock and Magisk images pass, where this machine has them', { skip: !existsSync(`${backups}boot-magisk.img`) }, async () => {
  const stock = new Uint8Array(readFileSync(`${backups}boot-stock.img`)), magisk = new Uint8Array(readFileSync(`${backups}boot-magisk.img`));
  assert.equal((await verifyBootSignature(stock)).certSha256, xp3800BootCertSha256);
  await checkPatchedBoot(magisk, stock);
  assert.equal(await isMagiskPatched(stock), false);
  assert.equal(await isMagiskPatched(magisk), true);
});

// ---- Firehose writer against a simulated XP3800 programmer ----
function simulator(boot, { corrupt = false } = {}) {
  let queue = [], program;
  const sent = [], transfers = [];
  const queueText = (...items) => queue.push(...items.map(item => typeof item === 'string' ? text(item) : item));
  return {
    sent, transfers, boot,
    async write(bytes, { terminate = false } = {}) {
      assert.equal(queue.length, 0, 'host wrote before reading the reply');
      if (program) {
        transfers.push({ length: bytes.length, terminate });
        program.data.push(bytes.slice());
        program.received += bytes.length;
        assert.ok(program.received <= program.sectors * 512);
        if (program.received === program.sectors * 512) {
          const data = concat(program.data);
          if (corrupt) data[100] ^= 1;
          boot.set(data, (program.start - 281696) * 512);
          program = undefined;
          queueText(recorded.rawEnd);
        }
        return;
      }
      const xml = new TextDecoder().decode(bytes);
      sent.push(xml);
      const attr = name => Number(new RegExp(`${name}="(\\d+)"`).exec(xml)?.[1]);
      if (xml.includes('<configure ')) queueText(recorded.configure, recorded.postConfigureLog);
      else if (xml.includes('<getstorageinfo ')) queueText(...recorded.geometry);
      else if (xml.includes('<read ')) {
        const start = attr('start_sector'), count = attr('num_partition_sectors');
        const data = start === 1 ? b(recorded.header) : start === 2 ? b(recorded.table) : boot.slice((start - 281696) * 512, (start - 281696 + count) * 512);
        assert.equal(data.length, count * 512);
        queueText(recorded.rawStart, data, recorded.rawEnd);
      } else if (xml.includes('<program ')) {
        program = { start: attr('start_sector'), sectors: attr('num_partition_sectors'), received: 0, data: [] };
        assert.equal(program.start, 281696);
        queueText(recorded.rawStart);
      } else if (xml.includes('<power value="reset"')) queueText(recorded.geometry.at(-1));
      else assert.fail(`unexpected request ${xml}`);
    },
    async read(amount) {
      assert.ok(queue.length, 'host read with nothing pending');
      const packet = queue[0], part = packet.subarray(0, amount);
      if (part.length === packet.length) queue.shift(); else queue[0] = packet.subarray(part.length);
      return part;
    },
    async close() {},
  };
}

test('the writer checks the partition, writes a signed patch in ZLP-ended chunks and reads it all back', async () => {
  const device = simulator(stockFull.slice());
  const writer = new FirehoseBootWriter(device);
  await writer.configure();
  assert.ok(device.sent[0].includes('SkipWrite="0"'));
  const phases = new Set();
  const result = await writer.writeBoot(patchedSigned, { current: stockFull, certSha256: testCert, onProgress: phase => phases.add(phase) });
  assert.equal(result.written, true);
  assert.deepEqual([...phases], ['check', 'write', 'verify']);
  const sectors = Math.ceil(patchedSigned.length / 512);
  assert.ok(device.sent.some(xml => xml.includes(`<program SECTOR_SIZE_IN_BYTES="512" num_partition_sectors="${sectors}" physical_partition_number="0" start_sector="281696"/>`)));
  assert.ok(device.transfers.every(t => t.terminate && t.length <= 1048576));
  // The image, zero padding to the sector, then the untouched stock tail.
  assert.deepEqual(device.boot.subarray(0, patchedSigned.length), patchedSigned);
  assert.ok(device.boot.subarray(patchedSigned.length, sectors * 512).every(x => x === 0));
  assert.deepEqual(device.boot.subarray(sectors * 512), stockFull.subarray(sectors * 512));
  assert.deepEqual(result.after, device.boot);
  await writer.reset();
});

test('the writer refuses before writing when boot is not the backup, or the image is not a signed patch of it', async () => {
  const other = stockFull.slice();
  other[bootBytes - 1] = 1;
  const device = simulator(other);
  const writer = new FirehoseBootWriter(device);
  await writer.configure();
  await assert.rejects(writer.writeBoot(patchedSigned, { current: stockFull, certSha256: testCert }),
    error => error instanceof WriteCheckError && !error.written && /Nothing was written/.test(error.message));
  assert.equal(writer.touched, false);
  await assert.rejects(writer.writeBoot(patchedSigned, { certSha256: testCert }), /only be written over the stock image/);
  await assert.rejects(writer.writeBoot(bootPayload(patchedRamdisk), { current: other, certSha256: testCert }), /Boot image is unsigned/);
  assert.ok(device.sent.every(xml => !xml.includes('<program')));
  await writer.reset();
});

test('a write that reads back wrong is reported, and stock can then be written back in the same session', async () => {
  const device = simulator(stockFull.slice(), { corrupt: true });
  const writer = new FirehoseBootWriter(device);
  await writer.configure();
  await assert.rejects(writer.writeBoot(patchedSigned, { current: stockFull, certSha256: testCert }),
    error => error instanceof WriteCheckError && error.written);
  assert.equal(writer.touched, true);
  const restore = simulator(device.boot);
  const again = new FirehoseBootWriter(restore);
  await again.configure();
  const result = await again.writeBoot(stockFull);
  assert.equal(result.written, true);
  assert.deepEqual(restore.boot, stockFull);
  // Writing what is already there sends no program request.
  const same = await again.writeBoot(stockFull);
  assert.equal(same.written, false);
  assert.equal(restore.sent.filter(xml => xml.includes('<program')).length, 1);
});

test('the read-only reader still never configures writes', async () => {
  const device = simulator(stockFull.slice());
  const reader = new FirehoseBootReader(device);
  await reader.configure();
  assert.deepEqual(await reader.readBoot(), stockFull);
  assert.ok(device.sent[0].includes('SkipWrite="1"'));
  assert.ok(device.sent.every(xml => !/<(program|erase|patch)[ >]/.test(xml)));
});

// ---- On-phone patching over a fake adb ----
function fakePhone({ signedImage, failVerify = false }) {
  const fs = new Map(), commands = [];
  const adb = {
    commands, fs,
    async shell(command) {
      commands.push(command);
      const ok = (stdout = '') => ({ code: 0, stdout, stderr: '' });
      const file = name => fs.get(`${workDir}/${name}`);
      if (command.startsWith('getprop ro.build.system_root_image')) return ok('\nunencrypted\n');
      if (command.startsWith('df -k')) return ok('/dev/block/bootdevice/by-name/userdata 5444052 1760320 3376532 35% /data\n');
      if (command.startsWith(`rm -rf ${workDir} && mkdir`)) { fs.clear(); return ok(); }
      if (command.includes('sha256sum boot.img magisk.apk')) return ok(`${sha(file('boot.img'))}  boot.img\n${sha(file('magisk.apk'))}  magisk.apk\n`);
      if (command.includes('chmod 755')) return ok();
      if (command.includes('boot_patch.sh boot.img')) {
        const unsigned = new Uint8Array(bootBytes);
        unsigned.set(bootPayload(patchedRamdisk));
        fs.set(`${workDir}/new-boot.img`, unsigned);
        return ok('- Unpacking boot image\n- Patching ramdisk\n- Repacking boot image\n');
      }
      if (command.includes('-sign < new-boot.img > signed.img')) { fs.set(`${workDir}/signed.img`, signedImage); return ok(); }
      if (command.includes('-verify < signed.img')) return ok(failVerify ? 'Signature is INVALID\n' : 'Signature is VALID\n');
      if (command.startsWith('stat -c %s')) return ok(`${file('signed.img').length}\n`);
      if (command.startsWith(`sha256sum ${workDir}/signed.img`)) return ok(`${sha(file('signed.img'))}  ${workDir}/signed.img\n`);
      if (command === `rm -rf ${workDir}`) return ok();
      return { code: 1, stdout: '', stderr: `unexpected: ${command}` };
    },
    async runService(service, use) {
      assert.equal(service, 'sync:');
      let output = new Uint8Array(), path, chunks;
      const reply = (...parts) => { output = concat([output, ...parts]); };
      const header = (tag, size) => { const h = new Uint8Array(8); h.set(text(tag)); new DataView(h.buffer).setUint32(4, size, true); return h; };
      return use({
        async send(bytes) {
          const tag = new TextDecoder().decode(bytes.subarray(0, 4)), size = new DataView(bytes.buffer, bytes.byteOffset).getUint32(4, true);
          if (tag === 'SEND') { path = new TextDecoder().decode(bytes.subarray(8, 8 + size)).split(',')[0]; chunks = []; }
          else if (tag === 'DATA') chunks.push(bytes.slice(8, 8 + size));
          else if (tag === 'DONE') { fs.set(path, concat(chunks)); reply(header('OKAY', 0)); }
          else if (tag === 'RECV') {
            const data = fs.get(new TextDecoder().decode(bytes.subarray(8, 8 + size)));
            for (let at = 0; at < data.length; at += 65536) reply(header('DATA', Math.min(65536, data.length - at)), data.subarray(at, at + 65536));
            reply(header('DONE', 0));
          }
        },
        async take(n) { const part = output.slice(0, n); output = output.subarray(n); return part; },
        async drain() {},
      });
    },
  };
  return adb;
}
const magiskApk = makeZip([
  ['assets/boot_patch.sh', text('#!/system/bin/sh\n# boot_patch\n'), true], ['assets/util_functions.sh', text('# util\n'), true],
  ['lib/armeabi-v7a/libmagiskboot.so', text('ELF magiskboot'), false], ['lib/armeabi-v7a/libmagiskinit.so', text('ELF magiskinit'), true],
  ['lib/armeabi-v7a/libmagisk32.so', text('ELF magisk32'), true], ['lib/armeabi-v7a/libbusybox.so', text('ELF busybox'), true],
]);

test('patching runs Magisk’s own tools as the shell user and returns the checked, signed image', async () => {
  const phone = fakePhone({ signedImage: patchedSigned });
  const lines = [];
  const result = await patchOnPhone(phone, { stock: stockFull, apk: magiskApk, log: line => lines.push(line), certSha256: testCert });
  assert.deepEqual(result, patchedSigned);
  const patch = phone.commands.find(c => c.includes('boot_patch.sh boot.img'));
  assert.match(patch, /KEEPVERITY=false KEEPFORCEENCRYPT=false RECOVERYMODE=false ASH_STANDALONE=1 \.\/busybox sh \.\/boot_patch\.sh boot\.img/);
  assert.ok(phone.commands.some(c => c.includes('/system/bin/dalvikvm -Xnoimage-dex2oat -cp /data/local/tmp/wave-magisk/magisk.apk com.topjohnwu.magisk.signing.SignBoot -sign')));
  assert.equal(phone.commands.at(-1), `rm -rf ${workDir}`);
  assert.ok(phone.commands.every(c => !/\bsu\b/.test(c)), 'patching never asks for root');
  const files = await magiskFiles(magiskApk);
  assert.deepEqual(files.map(([name]) => name), ['boot_patch.sh', 'util_functions.sh', 'magiskboot', 'magiskinit', 'magisk32', 'busybox']);
  assert.ok(lines.some(line => line.includes('Repacking')));
});

test('patching stops on a bad signature verdict or an image that is not a patch of this stock', async () => {
  await assert.rejects(patchOnPhone(fakePhone({ signedImage: patchedSigned, failVerify: true }), { stock: stockFull, apk: magiskApk, certSha256: testCert }), /valid signature/);
  const unrelatedPayload = bootPayload(patchedRamdisk);
  unrelatedPayload.set(text('console=ttyMSM0'), 64);
  const unrelated = signed(unrelatedPayload);
  const phone = fakePhone({ signedImage: unrelated });
  await assert.rejects(patchOnPhone(phone, { stock: stockFull, apk: magiskApk, certSha256: testCert }), /boot header doesn't match/);
  assert.equal(phone.commands.at(-1), `rm -rf ${workDir}`, 'the work folder is removed after a failure too');
});

// ---- adb handshake after an abandoned session ----
import { Adb, adbHeader } from '../adb.mjs';
function adbPeer(script) {
  // script: on each host packet (name), the packets the phone answers with.
  let queue = [];
  const writes = [];
  return {
    writes,
    async write(bytes, options) {
      if (options?.terminate) return;  // a payload, not a header
      const name = new TextDecoder().decode(bytes.subarray(0, 4));
      writes.push(name === 'AUTH' ? `AUTH${new DataView(bytes.buffer, bytes.byteOffset).getUint32(4, true)}` : name);
      for (const [n, a, b0, payload = new Uint8Array()] of script.shift() ?? []) queue.push(adbHeader(n, a, b0, payload, true), ...(payload.length ? [payload] : []));
    },
    async read(amount) {
      const packet = queue[0], part = packet.subarray(0, amount);
      if (part.length === packet.length) queue.shift(); else queue[0] = packet.subarray(part.length);
      return part;
    },
    async close() {},
  };
}
const token = () => new Uint8Array(20).fill(7);
const cnxn = ['CNXN', 0x01000000, 4096, text('device::ro.product.model=XP3800;features=shell_v2\0')];

test('adb connect skips a previous session’s late packets and a stale AUTH token', async () => {
  const credential = { sign: () => new Uint8Array(256), publicKey: text('key\0') };
  // A late CLSE and WRTE from the last session, then a stale token whose signature fails.
  const peer = adbPeer([[['CLSE', 5, 1], ['WRTE', 5, 1, new Uint8Array(9000)], ['AUTH', 1, 0, token()]], [['AUTH', 1, 0, token()]], [cnxn]]);
  const adb = await Adb.connect(peer, { credential });
  assert.deepEqual(peer.writes, ['CNXN', 'AUTH2', 'AUTH2']);
  assert.ok(adb.features.has('shell_v2'));
  // A phone that doesn't know the key: two signatures, then the key is offered once.
  let asked = 0;
  const fresh = adbPeer([[['AUTH', 1, 0, token()]], [['AUTH', 1, 0, token()]], [['AUTH', 1, 0, token()]], [cnxn]]);
  await Adb.connect(fresh, { credential, onAuthorization: () => asked++ });
  assert.deepEqual(fresh.writes, ['CNXN', 'AUTH2', 'AUTH2', 'AUTH3']);
  assert.equal(asked, 1);
});
