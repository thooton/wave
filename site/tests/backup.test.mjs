import assert from 'node:assert/strict';
import test from 'node:test';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, readdirSync, rmSync, existsSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { makeBackupManifest, parseBackupManifest } from '../src/root/backup-manifest.mjs';

const script = name => fileURLToPath(new URL('../scripts/' + name, import.meta.url));

test('the backup, rooting and stock-boot pages are staged and linked, without binaries unless named', () => {
  const temp = mkdtempSync(join(tmpdir(), 'wave-rooting-'));
  const site = join(temp, 'site');
  try {
    const run = (name, ...args) => spawnSync('python3', [script(name), ...args], { encoding: 'utf8' });
    let result = run('build.py', site);
    assert.equal(result.status, 0, result.stderr);
    result = run('check_pages.py', site);
    assert.equal(result.status, 0, result.stderr);
    for (const path of ['backup/index.html', 'rooting/index.html', 'stock/index.html', 'src/protocols/firehose-write.mjs',
      'src/root/magisk.mjs', 'src/protocols/adb-sync.mjs']) assert.equal(existsSync(join(site, path)), true, path);
    assert.equal(existsSync(join(site, 'src/protocols/tests')), false);
    const guide = readFileSync(join(site, 'root/index.html'), 'utf8');
    for (const page of ['backup', 'rooting', 'stock']) assert.match(guide, new RegExp(`href="\\.\\./${page}/"`));
    // The read-only reader never carries a write request; only the writer module does.
    assert.doesNotMatch(readFileSync(join(site, 'src/protocols/firehose.mjs'), 'utf8'), /<program /);
    assert.match(readFileSync(join(site, 'src/protocols/firehose-write.mjs'), 'utf8'), /<program /);
    assert.ok(readdirSync(site, { recursive: true }).every(name => !/\.(?:mbn|apk|img|zip)$/i.test(name)));
    // Staging refuses anything but the official Magisk 24.0, and check_pages verifies the list.
    const fake = join(temp, 'fake.apk');
    writeFileSync(fake, 'PK fake');
    assert.notEqual(run('build.py', '--magisk-apk', fake, join(temp, 'x')).status, 0);
    const staged = join(temp, 'staged');
    assert.equal(run('build.py', '--wave-apk', fake, '--wave-version', '0.7.30', staged).status, 0);
    assert.equal(run('check_pages.py', staged).status, 0);
    const list = JSON.parse(readFileSync(join(staged, 'rooting/files/files.json'), 'utf8'));
    assert.deepEqual(Object.keys(list), ['wave']);
    assert.equal(list.wave.version, '0.7.30');
    writeFileSync(join(staged, 'rooting/files/wave.apk'), 'PK changed');
    assert.match(run('check_pages.py', staged).stderr, /does not match files\.json/);
  } finally { rmSync(temp, { recursive: true, force: true }); }
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
