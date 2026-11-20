import assert from 'node:assert/strict';
import test from 'node:test';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, readdirSync, rmSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { makeBackupManifest, parseBackupManifest } from '../src/root/backup-manifest.mjs';

const script = name => fileURLToPath(new URL('../scripts/' + name, import.meta.url));

test('device backup is staged and linked only by explicit opt-in, without binaries or write paths', () => {
  const temp = mkdtempSync(join(tmpdir(), 'wave-backup-'));
  const standard = join(temp, 'standard'), opted = join(temp, 'opted');
  try {
    const run = (name, ...args) => {
      const result = spawnSync('python3', [script(name), ...args], { encoding: 'utf8' });
      assert.equal(result.status, 0, result.stderr);
    };
    run('build.py', standard);
    run('build.py', '--with-device-backup', opted);
    run('check_pages.py', opted);
    assert.equal(existsSync(join(standard, 'backup')), false);
    const guide = pkg => readFileSync(join(pkg, 'root/index.html'), 'utf8');
    assert.doesNotMatch(guide(standard), /href="\.\.\/backup\//);
    assert.match(guide(opted), /href="\.\.\/backup\//);
    for (const name of ['adb-sync', 'tests']) assert.equal(existsSync(join(opted, 'src/protocols', name === 'tests' ? name : `${name}.mjs`)), false);
    assert.ok(readdirSync(opted, { recursive: true }).every(name => !/\.(?:mbn|apk|img|zip)$/i.test(name)));
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
