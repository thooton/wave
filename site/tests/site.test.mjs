import assert from 'node:assert/strict';
import test from 'node:test';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, readdirSync, rmSync, existsSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { fetchProgrammer, servedFiles } from '../src/root/served.mjs';
import { makeBackupManifest, parseBackupManifest } from '../src/root/backup-manifest.mjs';
import { backupName, makeBackupFile, readBackup } from '../src/root/backup-file.mjs';
import { homedir } from 'node:os';

const script = name => fileURLToPath(new URL('../scripts/' + name, import.meta.url));

const programmerName = 'prog_emmc_firehose_8909_ddr.mbn';
const programmerHash = 'e6f2dbcfbed3ca72f23b4e1f0a53c510a620108eee2ac6b18a7835f1440e2767';
const hash = data => createHash('sha256').update(data).digest('hex');

test('the default build includes the pinned programmer and only the reviewed site assets', () => {
  const temp = mkdtempSync(join(tmpdir(), 'wave-rooting-'));
  const site = join(temp, 'site');
  try {
    const run = (name, ...args) => spawnSync('python3', [script(name), ...args], { encoding: 'utf8' });
    let result = run('build.py', site);
    assert.equal(result.status, 0, result.stderr);
    result = run('check_pages.py', site);
    assert.equal(result.status, 0, result.stderr);
    for (const path of ['index.html', 'recovery/index.html', 'src/protocols/firehose-write.mjs',
      'src/root/magisk.mjs', 'src/root/backup-file.mjs', 'src/protocols/adb-sync.mjs']) assert.equal(existsSync(join(site, path)), true, path);
    for (const gone of ['src/protocols/tests', 'scripts', 'tests', 'mockups', 'AGENTS.md']) assert.equal(existsSync(join(site, gone)), false, gone);
    assert.match(readFileSync(join(site, 'index.html'), 'utf8'), /href="recovery\/"/);
    assert.match(readFileSync(join(site, 'recovery/index.html'), 'utf8'), /href="\.\.\/"/);
    // The read-only reader never carries a write request; only the writer module does.
    assert.doesNotMatch(readFileSync(join(site, 'src/protocols/firehose.mjs'), 'utf8'), /<program /);
    assert.match(readFileSync(join(site, 'src/protocols/firehose-write.mjs'), 'utf8'), /<program /);
    assert.deepEqual(readdirSync(site, { recursive: true }).filter(name => /\.(?:mbn|apk|img|zip)$/i.test(name)), [join('files', programmerName)]);
    const manifestPath = join(site, 'files/files.json');
    const programmerPath = join(site, 'files', programmerName);
    const bytes = readFileSync(programmerPath);
    const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
    assert.deepEqual(manifest, { programmer: { file: programmerName, size: 285524, sha256: programmerHash } });
    assert.equal(hash(bytes), programmerHash);
    // Require a real pinned loader, even when the binary and manifest are altered together.
    bytes[0] ^= 1;
    writeFileSync(programmerPath, bytes);
    assert.match(run('check_pages.py', site).stderr, /does not match files\.json/);
    manifest.programmer.sha256 = hash(bytes);
    writeFileSync(manifestPath, JSON.stringify(manifest));
    assert.match(run('check_pages.py', site).stderr, /invalid pinned Sonim programmer/);
    rmSync(manifestPath);
    assert.match(run('check_pages.py', site).stderr, /missing or invalid files\.json/);

    // APKs are staged only when supplied to the builder.
    const root = readFileSync(join(site, 'index.html'), 'utf8');
    const recovery = readFileSync(join(site, 'recovery/index.html'), 'utf8');
    const fileInputs = html => [...html.matchAll(/<input id="([^"]+)" type="file"/g)].map(match => match[1]);
    assert.deepEqual(fileInputs(root), []);
    assert.deepEqual(fileInputs(recovery), ['backup-file']);
    const fake = join(temp, 'fake.apk');
    writeFileSync(fake, 'PK fake');
    assert.notEqual(run('build.py', '--magisk-apk', fake, join(temp, 'x')).status, 0);
    const staged = join(temp, 'staged');
    assert.equal(run('build.py', '--wave-apk', fake, '--wave-version', '0.7.30', staged).status, 0);
    assert.equal(run('check_pages.py', staged).status, 0);
    const list = JSON.parse(readFileSync(join(staged, 'files/files.json'), 'utf8'));
    assert.deepEqual(Object.keys(list), ['programmer', 'wave']);
    assert.equal(list.wave.version, '0.7.30');
    writeFileSync(join(staged, 'files/wave.apk'), 'PK changed');
    assert.match(run('check_pages.py', staged).stderr, /does not match files\.json/);
  } finally { rmSync(temp, { recursive: true, force: true }); }
});

test('the browser loader verifies the bundled programmer independently of the manifest', async t => {
  const base = new URL('https://example.test/wave/files/');
  const bytes = readFileSync(new URL(`../assets/${programmerName}`, import.meta.url));
  const entry = { file: programmerName, size: bytes.length, sha256: programmerHash };
  t.mock.method(globalThis, 'fetch', async url => {
    if (url.href === new URL('files.json', base).href) return Response.json({ programmer: entry });
    assert.equal(url.href, new URL(programmerName, base).href);
    return new Response(bytes);
  });
  const list = await servedFiles(base);
  assert.deepEqual(await fetchProgrammer(base, list.programmer), new Uint8Array(bytes));
  await assert.rejects(fetchProgrammer(base), /missing the bundled Sonim programmer/);
  bytes[0] ^= 1;
  await assert.rejects(fetchProgrammer(base, entry), /does not match the listed SHA-256/);
  await assert.rejects(fetchProgrammer(base, { ...entry, sha256: hash(bytes) }), /recognized Sonim-signed/);
});

test('boot manifest round-trips and rejects another partition, size or invalid identity', () => {
  const sha = 'a'.repeat(64);
  const value = makeBackupManifest({ serial: 'demo_123', fingerprint: 'Sonim/XP3832/XP3800:8.1.0/test/22:user/release-keys', source: 'owner-entered' }, sha, new Date('2026-11-19T01:00:00.000Z'));
  assert.deepEqual(parseBackupManifest(JSON.stringify(value)), value);
  for (const changes of [{ partition: 'misc' }, { size: 123 }, { model: 'XP3832' }, { sha256: 'x'.repeat(64) },
    { serial: 'path/serial' }, { fingerprint: 'Sonim/XP3833/XP3833:8.1.0/test/22:user/release-keys' }, { date: '2026-02-30T12:00:00.000Z' }]) {
    assert.throws(() => parseBackupManifest(JSON.stringify({ ...value, ...changes })));
  }
});

test('format 2 manifests carry the disk GUID; format 1 still parses', () => {
  const identity = { serial: 'demo_123', fingerprint: 'Sonim/XP3832/XP3800:8.1.0/test/22:user/release-keys', source: 'android-usb' };
  const guid = '0a1b2c3d-4e5f-6071-8293-a4b5c6d7e8f9';
  const v2 = makeBackupManifest({ ...identity, diskGuid: guid }, 'a'.repeat(64), new Date('2026-11-19T01:00:00.000Z'));
  assert.equal(v2.format, 2);
  assert.equal(v2.diskGuid, guid);
  assert.deepEqual(parseBackupManifest(JSON.stringify(v2)), v2);
  assert.throws(() => parseBackupManifest(JSON.stringify({ ...v2, diskGuid: 'nope' })));
  assert.throws(() => parseBackupManifest(JSON.stringify({ ...v2, diskGuid: undefined })));
  assert.equal(makeBackupManifest(identity, 'a'.repeat(64)).format, 1);
  assert.equal(backupName(v2), 'xp3800-demo_123-stock-boot.wavebackup');
});

test('anything but a Wave backup is rejected as a file problem', async () => {
  for (const files of [[new File(['hello'], 'x.wavebackup')], [], [new File(['a'], 'a.img'), new File(['{}'], 'a.json')]]) {
    await assert.rejects(readBackup(files), error => error.kind === 'file');
  }
});

const backups = `${homedir()}/Documents/wave-phone-backups/8516067e/`;
test('a .wavebackup round-trips the real stock image, and a patched image is refused', { skip: !existsSync(`${backups}boot-stock.img`) }, async () => {
  const image = new Uint8Array(readFileSync(`${backups}boot-stock.img`));
  const manifest = makeBackupManifest({ serial: '8516067e', fingerprint: 'Sonim/XP3832/XP3800:8.1.0/test/22:user/release-keys',
    source: 'android-usb', diskGuid: '0a1b2c3d-4e5f-6071-8293-a4b5c6d7e8f9' }, hash(image));
  const file = makeBackupFile(manifest, image);
  assert.deepEqual(makeBackupFile(manifest, image), file, 'the same backup is the same bytes');
  const read = await readBackup([new File([file], backupName(manifest))]);
  assert.deepEqual(read.manifest, manifest);
  assert.deepEqual(read.image, image);
  // The old two-file backup still works.
  const pair = await readBackup([new File([image], 'boot.img'), new File([JSON.stringify(manifest)], 'boot.json')]);
  assert.deepEqual(pair.image, image);
  const other = image.slice();
  other[100] ^= 1;
  await assert.rejects(readBackup([new File([makeBackupFile(manifest, other)], 'x.wavebackup')]), error => error.kind === 'file');
  if (existsSync(`${backups}boot-magisk-partition.img`)) {
    const patched = new Uint8Array(readFileSync(`${backups}boot-magisk-partition.img`));
    const m = makeBackupManifest({ serial: '8516067e', fingerprint: manifest.fingerprint, source: 'android-usb' }, hash(patched));
    await assert.rejects(readBackup([new File([makeBackupFile(m, patched)], 'x.wavebackup')]), error => error.kind === 'stock');
  }
});
