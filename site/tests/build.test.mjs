import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, existsSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const script = name => fileURLToPath(new URL('../scripts/' + name, import.meta.url));

test('bundle stays inside its allowlist and resolves links beneath the project prefix', () => {
  const temp = mkdtempSync(join(tmpdir(), 'wave-site-'));
  const out = join(temp, 'wave');
  try {
    const run = name => spawnSync('python3', [script(name), out], {encoding: 'utf8'});
    const build = run('build.py');
    assert.equal(build.status, 0, build.stderr);
    const check = run('check_pages.py');
    assert.equal(check.status, 0, check.stderr);
    for (const excluded of ['scripts', 'tests', '.git', 'AGENTS.md', '.oak', 'mockups']) {
      assert.equal(existsSync(join(out, excluded)), false, excluded);
    }
    for (const excluded of ['src/protocols/sahara.mjs', 'src/protocols/firehose.mjs', 'src/protocols/gpt.mjs',
      'src/protocols/adb-sync.mjs', 'public-assets']) {
      assert.equal(existsSync(join(out, excluded)), false, excluded);
    }
    for (const page of ['root', 'rescue', 'restore']) {
      assert.equal(existsSync(join(out, page, 'index.html')), true, page);
    }
    assert.notEqual(run('build.py').status, 0, 'never replace a directory');
    const index = join(out, 'index.html');
    writeFileSync(index, readFileSync(index, 'utf8') + '<a href="../bad">bad</a>');
    let invalid = run('check_pages.py');
    assert.notEqual(invalid.status, 0);
    assert.match(invalid.stderr, /invalid local URL: ..\/bad/);
    writeFileSync(index, readFileSync(index, 'utf8').replace('<a href="../bad">bad</a>', '<p id="main">dup</p>'));
    invalid = run('check_pages.py');
    assert.match(invalid.stderr, /duplicate id: main/);
    const probe = join(out, 'src/root/probe.mjs');
    writeFileSync(probe, readFileSync(probe, 'utf8') + `\nimport { missing } from './missing.mjs';\n`);
    writeFileSync(join(out, 'extra.bin'), Buffer.alloc(20 * 1024));
    invalid = run('check_pages.py');
    assert.match(invalid.stderr, /invalid local URL: .\/missing.mjs/);
    assert.match(invalid.stderr, /not reachable from index.html: extra.bin/);
    assert.match(invalid.stderr, /bundle .* exceeds/);
  } finally {
    rmSync(temp, { recursive: true, force: true });
  }
});
