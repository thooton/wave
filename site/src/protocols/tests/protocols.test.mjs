import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { constants, createHash, generateKeyPairSync, publicDecrypt } from 'node:crypto';
import test from 'node:test';
import { adbHeader, Adb } from '../adb.mjs';
import { install, pull, push } from '../adb-sync.mjs';
import { adbCredential } from '../adb-credential.mjs';
import { checkProgrammer, saharaExchange } from '../sahara.mjs';
import { FirehoseBootReader } from '../firehose.mjs';
import { bootBytes, checkBootImage } from '../boot-image.mjs';
import { parseXp3800Gpt, readGptHeader } from '../gpt.mjs';

const read = name => JSON.parse(readFileSync(new URL(name, import.meta.url)));
const b = hex => new Uint8Array(Buffer.from(hex, 'hex'));
const hex = bytes => Buffer.from(bytes).toString('hex');
const text = s => new TextEncoder().encode(s);

test('XP3800 capture: ADB handshake, shell_v2 status and sync push/pull', async () => {
  const frames = read('./xp3800-adb.json');
  let index = 0, pending = [], expect = [], didClose = false;
  const take = dir => {
    const frame = frames[index++];
    assert.equal(frame?.[0], dir, `frame ${index}`);
    const [, name, a, b0, payload] = frame;
    const data = b(payload);
    return [adbHeader(name, a, b0, data), ...(data.length ? [data] : [])];
  };
  const link = {
    async read(n) {
      if (!pending.length) pending = take('in');
      assert.equal(expect.length, 0);
      const next = pending[0];
      const size = Math.min(n, next.length, 19);  // also exercise split USB packets
      pending[0] = next.subarray(size);
      if (!pending[0].length) pending.shift();
      return next.subarray(0, size);
    },
    async write(bytes) {
      assert.equal(pending.length, 0);
      if (!expect.length) expect = take('out');
      assert.deepEqual(bytes, expect.shift());
    },
    async close() { didClose = true; },
  };
  const adb = await Adb.connect(link, { credential: { sign: () => new Uint8Array(256) } });
  assert.deepEqual(await adb.shell('printf wave; printf test >&2; exit 7'), { code: 7, stdout: 'wave', stderr: 'test' });
  const file = '/data/local/tmp/wave-usb-transport-test.txt';
  await push(adb, file, text('Wave transport\n'), 0);
  assert.deepEqual(await pull(adb, file), text('Wave transport\n'));
  assert.equal(index, frames.length);
  await adb.close();
  assert.equal(didClose, true);
});

test('the ADB AUTH signature encodes a raw SHA-1 digest, not its double hash', () => {
  const { privateKey, publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
  const key = adbCredential(privateKey.export({ format: 'jwk' }));
  const token = createHash('sha1').update('untrusted adb test token').digest();
  const decoded = publicDecrypt({ key: publicKey, padding: constants.RSA_NO_PADDING }, key.sign(token));
  assert.equal(hex(decoded.subarray(0, 2)), '0001');
  assert.equal(hex(decoded.subarray(2, 220)), 'ff'.repeat(218));
  assert.equal(hex(decoded.subarray(220)), '003021300906052b0e03021a05000414' + hex(token));
  const pub = Buffer.from(new TextDecoder().decode(key.publicKey).split(' ')[0], 'base64');
  assert.equal(pub.length, 524);
  assert.equal(pub.readUInt32LE(0), 64);
  assert.equal(pub.readUInt32LE(520), 65537);
});

test('install checks pm result and removes the staged temporary APK', async () => {
  const calls = [];
  const mock = {
    async runService(name, action) {
      assert.equal(name, 'sync:');
      await action({
        async send(packet) {
          const data = new TextDecoder().decode(packet);
          if (data.startsWith('SEND')) calls.push(data.slice(8).split(',')[0]);
        },
        async take() { return text('OKAY\0\0\0\0'); },
        async drain() {},
      });
    },
    async shell(command) { calls.push(command); return { code: 1, stdout: 'Failure [not an APK]' }; },
  };
  await assert.rejects(install(mock, new Uint8Array(4)), /not an APK/);
  assert.match(calls[0], /^\/data\/local\/tmp\/wave-install-[0-9a-f-]+\.apk$/);
  assert.equal(calls[1], `pm install -r '${calls[0]}'`);
  assert.equal(calls[2], `rm -f '${calls[0]}'`);
});

test('XP3800 capture: Sahara hello, requested loader ranges and done hand-off', async () => {
  const packets = read('./xp3800-sahara.json').map(b);
  const image = Uint8Array.from({ length: 285524 }, (_, i) => (i ^ (i >> 8)) & 255);
  let last;
  const link = {
    async read() { return last = packets.shift(); },
    async write(bytes) {
      const command = last[0];
      if (command === 1) assert.equal(hex(bytes), '020000003000000002000000010000000000000000000000010000000200000003000000040000000500000006000000');
      else if (command === 3) {
        const view = new DataView(last.buffer);
        const start = view.getUint32(12, true);
        assert.deepEqual(bytes, image.subarray(start, start + view.getUint32(16, true)));
      } else if (command === 4) assert.equal(hex(bytes), '0500000008000000');
      else assert.fail('Unexpected write during Sahara replay');
    },
  };
  await assert.rejects(checkProgrammer(image), /recognized Sonim-signed/);
  await saharaExchange(link, image);
  assert.equal(packets.length, 0);
  const bad = read('./xp3800-sahara.json').slice(0, 2).map(b);
  await assert.rejects(saharaExchange({ read: async () => bad.shift(), write: async () => {} }, new Uint8Array(1)), /outside/);
});

function image() {
  const image = new Uint8Array(bootBytes);
  image.set(text('ANDROID!'));
  const header = new DataView(image.buffer);
  header.setUint32(8, 18_000_000, true);
  header.setUint32(16, 1_000_000, true);
  header.setUint32(36, 2048, true);
  return image;
}

test('XP3800 capture: eMMC configure, CRC-checked GPT and raw read phases', async () => {
  const d = read('./xp3800-firehose.json');
  const data = image();
  const jobs = [
    ['<configure ', [text(d.configure), text(d.postConfigureLog)]],
    ['start_sector="1"', [text(d.rawStart), b(d.header), new Uint8Array(), text(d.rawEnd)]],
    ['<getstorageinfo ', d.geometry.map(text)],
    ['start_sector="2"', [text(d.rawStart), b(d.table), text(d.rawEnd)]],
    ['start_sector="281696"', [text(d.rawStart), data, text(d.rawEnd)]],
    ['<power value="reset"', [text(d.geometry.at(-1))]],
  ];
  let queue = [], closed = false;
  const client = new FirehoseBootReader({
    async write(bytes) {
      assert.equal(queue.length, 0);
      const [needle, responses] = jobs.shift();
      const xml = new TextDecoder().decode(bytes);
      assert.ok(xml.includes(needle));
      if (needle.startsWith('<configure')) assert.ok(xml.includes('SkipWrite="1"'));
      assert.doesNotMatch(xml, /<(program|erase|patch)[ >]/);
      queue = responses;
    },
    async read(amount) {
      assert.ok(queue.length > 0);
      const packet = queue[0];
      const actual = packet.subarray(0, amount);
      if (actual.length === packet.length) queue.shift();
      else queue[0] = packet.subarray(actual.length);
      return actual;
    },
    async close() { closed = true; },
  });
  await client.configure();
  const gpt = await client.readGpt();
  assert.deepEqual(gpt.boot, { start: 281696, sectors: 65536 });
  assert.deepEqual(gpt.partitions[4], { name: 'boot', ...gpt.boot });
  assert.equal(gpt.partitions.length, 36);
  assert.deepEqual(await client.readBoot(), data);
  await client.reset();
  assert.ok(closed);
  assert.equal(jobs.length, 0);
  const table = b(d.table);
  table[40] ^= 1;
  assert.throws(() => parseXp3800Gpt(readGptHeader(b(d.header)), table), /CRC mismatch/);
  data[0] = 0;
  assert.throws(() => checkBootImage(data), /ANDROID!/);
});
